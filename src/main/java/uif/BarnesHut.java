package uif;

/**
 * Barnes–Hut O(N log N) для сил W = Q·N: октудерево на хосте, обход на CPU или
 * во float на видеокарте ({@link Gpu#computeFloatBh}). Критерий s/d &lt; θ;
 * полюса диполей — точно на хосте. Энергия сюда не идёт.
 * <p>
 * Построение переиспользует пул узлов и буферы дерева (без аллокаций на каждый шаг).
 */
final class BarnesHut {
    static final float THETA = 0.55f;
    private static final int MAX_DEPTH = 28;
    private static final int STACK = 64;

    private BarnesHut() {}

    /** Плоское дерево после {@link #build}. */
    static final class Tree {
        int nNodes;
        /** Половина стороны корня (для порога «грязности» на хосте). */
        double rootHalf;
        float[] cx, cy, cz, q, w, mass; // COM, заряд Σ s·N, ширина, Σ N (для GPU refresh)
        int[] child;              // 8·nNodes
        int[] body;               // ≥0 лист-тело; −1 внутренний; −2 слитый мультиполь
    }

    /** Переиспользуемый scratch: без new на каждый rebuild. */
    static final class Scratch {
        Node[] nodes = new Node[0];
        int nNodes;
        int[] ids = new int[0];
        int[] order = new int[0];
        final int[] start = new int[MAX_DEPTH * 8];
        final int[] end = new int[MAX_DEPTH * 8];
        final int[] hist = new int[MAX_DEPTH * 8];
        final Tree tree = new Tree();

        Node alloc() {
            if (nNodes >= nodes.length) {
                int cap = Math.max(16, nodes.length == 0 ? 64 : nodes.length * 2);
                Node[] n = new Node[cap];
                System.arraycopy(nodes, 0, n, 0, nodes.length);
                for (int i = nodes.length; i < cap; i++) n[i] = new Node();
                nodes = n;
            }
            Node nd = nodes[nNodes++];
            nd.reset();
            return nd;
        }
    }

    private static final class Node {
        double cx, cy, cz, half, mass, charge;
        int body = -1;
        final int[] child = {-1, -1, -1, -1, -1, -1, -1, -1};

        void reset() {
            cx = cy = cz = half = mass = charge = 0;
            body = -1;
            for (int i = 0; i < 8; i++) child[i] = -1;
        }
    }

    /** Построить дерево в {@code s.tree}, переиспользуя пул. */
    static Tree build(Forces f, Scratch s) {
        int n = f.n;
        s.nNodes = 0;
        Tree out = s.tree;
        if (n == 0) {
            out.nNodes = 0;
            out.rootHalf = 1;
            ensureTree(out, 0);
            return out;
        }
        if (s.ids.length < n) {
            s.ids = new int[Math.max(n, 16)];
            s.order = new int[s.ids.length];
        }
        double minX = f.px[0], maxX = minX, minY = f.py[0], maxY = minY, minZ = f.pz[0], maxZ = minZ;
        for (int i = 1; i < n; i++) {
            double x = f.px[i], y = f.py[i], z = f.pz[i];
            if (x < minX) minX = x;
            else if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            else if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            else if (z > maxZ) maxZ = z;
        }
        double cx = 0.5 * (minX + maxX), cy = 0.5 * (minY + maxY), cz = 0.5 * (minZ + maxZ);
        double half = 0.5 * Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (!(half > 0)) half = 1;
        half *= 1.01;
        out.rootHalf = half;

        for (int i = 0; i < n; i++) s.ids[i] = i;
        s.alloc(); // root = 0
        fill(f, s, 0, 0, n, cx, cy, cz, half, 0);

        int nn = s.nNodes;
        ensureTree(out, nn);
        out.nNodes = nn;
        for (int i = 0; i < nn; i++) {
            Node nd = s.nodes[i];
            out.cx[i] = (float) nd.cx;
            out.cy[i] = (float) nd.cy;
            out.cz[i] = (float) nd.cz;
            out.q[i] = (float) nd.charge;
            out.w[i] = (float) (2 * nd.half);
            out.mass[i] = (float) nd.mass;
            out.body[i] = nd.body;
            System.arraycopy(nd.child, 0, out.child, 8 * i, 8);
        }
        return out;
    }

    private static void ensureTree(Tree out, int nn) {
        if (out.cx != null && out.cx.length >= nn) return;
        int cap = Math.max(nn, out.cx == null ? 16 : out.cx.length * 2);
        out.cx = new float[cap];
        out.cy = new float[cap];
        out.cz = new float[cap];
        out.q = new float[cap];
        out.w = new float[cap];
        out.mass = new float[cap];
        out.body = new int[cap];
        out.child = new int[8 * cap];
    }

    private static void fill(Forces f, Scratch s, int node, int i0, int i1,
                             double cx, double cy, double cz, double half, int depth) {
        Node nd = s.nodes[node];
        nd.half = half;
        int nBodies = i1 - i0;
        if (nBodies == 1 || depth >= MAX_DEPTH || half < 1e-18) {
            if (nBodies == 1) {
                int b = s.ids[i0];
                nd.body = b;
                nd.cx = f.px[b];
                nd.cy = f.py[b];
                nd.cz = f.pz[b];
                nd.mass = f.N[b];
                nd.charge = f.sg[b] * f.N[b];
            } else {
                nd.body = -2;
                double m = 0, qx = 0, qy = 0, qz = 0, ch = 0;
                for (int k = i0; k < i1; k++) {
                    int b = s.ids[k];
                    double Nj = f.N[b];
                    m += Nj;
                    qx += Nj * f.px[b];
                    qy += Nj * f.py[b];
                    qz += Nj * f.pz[b];
                    ch += f.sg[b] * Nj;
                }
                nd.mass = m;
                nd.charge = ch;
                if (m > 0) {
                    nd.cx = qx / m;
                    nd.cy = qy / m;
                    nd.cz = qz / m;
                } else {
                    nd.cx = cx;
                    nd.cy = cy;
                    nd.cz = cz;
                }
            }
            return;
        }
        int base = depth * 8;
        int[] hist = s.hist, start = s.start, end = s.end, ids = s.ids, order = s.order;
        for (int o = 0; o < 8; o++) hist[base + o] = 0;
        for (int k = i0; k < i1; k++) {
            int b = ids[k], o = 0;
            if (f.px[b] >= cx) o |= 1;
            if (f.py[b] >= cy) o |= 2;
            if (f.pz[b] >= cz) o |= 4;
            hist[base + o]++;
        }
        int p = 0;
        for (int o = 0; o < 8; o++) {
            start[base + o] = p;
            p += hist[base + o];
            end[base + o] = p;
            hist[base + o] = start[base + o];
        }
        for (int k = i0; k < i1; k++) {
            int b = ids[k], o = 0;
            if (f.px[b] >= cx) o |= 1;
            if (f.py[b] >= cy) o |= 2;
            if (f.pz[b] >= cz) o |= 4;
            order[i0 + hist[base + o]++] = b;
        }
        System.arraycopy(order, i0, ids, i0, nBodies);

        nd.body = -1;
        double h2 = 0.5 * half;
        double m = 0, qx = 0, qy = 0, qz = 0, ch = 0;
        for (int o = 0; o < 8; o++) {
            if (start[base + o] == end[base + o]) {
                nd.child[o] = -1;
                continue;
            }
            int child = s.nNodes;
            s.alloc();
            nd.child[o] = child;
            double ox = ((o & 1) != 0 ? 1 : -1) * h2;
            double oy = ((o & 2) != 0 ? 1 : -1) * h2;
            double oz = ((o & 4) != 0 ? 1 : -1) * h2;
            fill(f, s, child, i0 + start[base + o], i0 + end[base + o],
                    cx + ox, cy + oy, cz + oz, h2, depth + 1);
            Node c = s.nodes[child];
            m += c.mass;
            qx += c.mass * c.cx;
            qy += c.mass * c.cy;
            qz += c.mass * c.cz;
            ch += c.charge;
        }
        nd.mass = m;
        nd.charge = ch;
        if (m > 0) {
            nd.cx = qx / m;
            nd.cy = qy / m;
            nd.cz = qz / m;
        } else {
            nd.cx = cx;
            nd.cy = cy;
            nd.cz = cz;
        }
    }

    static void evaluate(Forces f, Tree tree, int mode, double kq, double fa, double beta,
                         double[] o0, double[] o1, double[] o2, double[] o3, double[] o4, double[] o5,
                         double[] tmin) {
        int n = f.n;
        float th2 = THETA * THETA;
        int[] stack = new int[STACK];
        for (int i = 0; i < n; i++) {
            float xi = (float) f.px[i], yi = (float) f.py[i], zi = (float) f.pz[i];
            float NA = (float) f.N[i], rA = (float) f.rad[i], sA = (float) f.sg[i];
            int pA = f.partner[i];
            float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, bt = 0;
            int sp = 0;
            if (tree.nNodes > 0) stack[sp++] = 0;
            while (sp > 0) {
                int ni = stack[--sp];
                int bj = tree.body[ni];
                float rx = tree.cx[ni] - xi, ry = tree.cy[ni] - yi, rz = tree.cz[ni] - zi;
                float r2 = rx * rx + ry * ry + rz * rz;
                float width = tree.w[ni];
                if (bj >= 0) {
                    if (bj == i || bj == pA) continue;
                    float Nj = (float) f.N[bj], Rj = (float) f.rad[bj], sj = (float) f.sg[bj];
                    rx = (float) f.px[bj] - xi;
                    ry = (float) f.py[bj] - yi;
                    rz = (float) f.pz[bj] - zi;
                    r2 = rx * rx + ry * ry + rz * rz;
                    float R = Math.max(rA, Rj);
                    float inv = 1f / (float) Math.sqrt(Math.max(r2, R * R));
                    float inv3 = inv * inv * inv;
                    bt = Math.max(bt, inv3 * (NA + Nj));
                    if (r2 == 0f) continue;
                    float ww = sA * sj * Nj * inv3;
                    if (mode == 0) {
                        s0 += ww * rx;
                        s1 += ww * ry;
                        s2 += ww * rz;
                    } else {
                        float ar = (float) (-beta) * ww, ap = (float) (1 + beta) * ww;
                        s0 += ar * rx;
                        s1 += ar * ry;
                        s2 += ar * rz;
                        s3 += ap * rx;
                        s4 += ap * ry;
                        s5 += ap * rz;
                    }
                    continue;
                }
                if (bj == -2 || (r2 > 0 && width * width < th2 * r2)) {
                    float R = rA;
                    float inv = 1f / (float) Math.sqrt(Math.max(r2, R * R));
                    float inv3 = inv * inv * inv;
                    float Nj = Math.abs(tree.q[ni]);
                    bt = Math.max(bt, inv3 * (NA + Nj));
                    if (r2 == 0f) continue;
                    float ww = sA * tree.q[ni] * inv3;
                    if (mode == 0) {
                        s0 += ww * rx;
                        s1 += ww * ry;
                        s2 += ww * rz;
                    } else {
                        float ar = (float) (-beta) * ww, ap = (float) (1 + beta) * ww;
                        s0 += ar * rx;
                        s1 += ar * ry;
                        s2 += ar * rz;
                        s3 += ap * rx;
                        s4 += ap * ry;
                        s5 += ap * rz;
                    }
                    continue;
                }
                for (int o = 0; o < 8; o++) {
                    int c = tree.child[8 * ni + o];
                    if (c >= 0 && sp < STACK) stack[sp++] = c;
                }
            }
            double k = fa * f.N[i] * kq / f.m[i];
            o0[i] = k * s0;
            o1[i] = k * s1;
            o2[i] = k * s2;
            if (mode != 0) {
                o3[i] = k * s3;
                o4[i] = k * s4;
                o5[i] = k * s5;
            }
            double t = bt > 0 ? 1 / (kq * bt) : Double.POSITIVE_INFINITY;
            addPartner(f, i, mode, kq, fa, o0, o1, o2, o3, o4, o5);
            if (pA >= 0) {
                double rxp = f.px[pA] - f.px[i], ryp = f.py[pA] - f.py[i], rzp = f.pz[pA] - f.pz[i];
                double r = Math.sqrt(rxp * rxp + ryp * ryp + rzp * rzp);
                double R = Math.max(f.rad[i], f.rad[pA]), rt = Math.max(R, r), fi = f.fIn[i], c = f.chi[i];
                t = Math.min(t, rt * rt * rt / (kq * fi * (1 + c) * (f.N[i] + f.N[pA])));
            }
            tmin[i] = t;
        }
    }

    static void addPartner(Forces f, int i, int mode, double kq, double fa,
                           double[] o0, double[] o1, double[] o2, double[] o3, double[] o4, double[] o5) {
        int pA = f.partner[i];
        if (pA < 0) return;
        double rx = f.px[pA] - f.px[i], ry = f.py[pA] - f.py[i], rz = f.pz[pA] - f.pz[i];
        double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (!(r > 0)) return;
        double R = Math.max(f.rad[i], f.rad[pA]);
        double M = Math.max(r, R), s = kq * f.N[pA] / (M * M * M);
        double Ms = Math.max(r, f.rs[i]), ss = kq * f.N[pA] / (Ms * Ms * Ms);
        double fi = f.fIn[i], c = f.chi[i];
        double kk = fa * f.N[i] * fi / f.m[i];
        if (mode == 0) {
            double qq = kk * (c * ss - s);
            o0[i] += qq * rx;
            o1[i] += qq * ry;
            o2[i] += qq * rz;
        } else {
            o0[i] -= kk * s * rx;
            o1[i] -= kk * s * ry;
            o2[i] -= kk * s * rz;
            o3[i] += kk * c * ss * rx;
            o4[i] += kk * c * ss * ry;
            o5[i] += kk * c * ss * rz;
        }
    }
}
