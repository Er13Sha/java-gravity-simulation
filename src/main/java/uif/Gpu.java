package uif;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Перебор пар на видеокарте NVIDIA — в double, теми же формулами, что ядра CPU в {@link Forces}
 * (строка в строку; --fmad=false — никаких неявных FMA, только явный fma() там же, где Math.fma в Java;
 * √ и деление в double по IEEE). Поэтому результат
 * совпадает с CPU до бита. Без сторонних библиотек: драйвер CUDA (nvcuda.dll) вызывается через
 * Foreign Function API Java, ядро компилируется при запуске NVRTC из CUDA Toolkit (CUDA_PATH).
 * Один поток видеокарты — одно тело i, сумма по j в том же порядке, что на CPU.
 */
final class Gpu {
    static final String SRC = """
            extern "C" __global__ void uif(int n, int mode, double a1, double a2, double b,
                const double* __restrict__ px, const double* __restrict__ py, const double* __restrict__ pz,
                const double* __restrict__ N, const double* __restrict__ rad, const double* __restrict__ sg,
                const double* __restrict__ fIn, const double* __restrict__ chi, const double* __restrict__ rs,
                const double* __restrict__ m, const int* __restrict__ partner,
                double* o0, double* o1, double* o2, double* o3, double* o4, double* o5, double* tmin, double* U)
            {
                int i = blockIdx.x * blockDim.x + threadIdx.x;
                if (i >= n) return;
                double xi = px[i], yi = py[i], zi = pz[i], NA = N[i], rA = rad[i], sA = sg[i], f = fIn[i], c = chi[i], rsA = rs[i], mA = m[i];
                int pA = partner[i];
                if (mode == 2) { // энергия: a1 = f(α)·k_v·kQ, пары j > i
                    double coef = a1, u = 0;
                    for (int j = i + 1; j < n; j++) {
                        double rx = px[j] - xi, ry = py[j] - yi, rz = pz[j] - zi;
                        double r = sqrt(fma(rx, rx, fma(ry, ry, rz * rz)));
                        double R = fmax(rA, rad[j]);
                        double phi = r >= R ? 1.0 / r : (3.0 * R * R - r * r) / (2.0 * R * R * R), p;
                        if (j == pA) {
                            double ps = r >= rsA ? 1.0 / r : (3.0 * rsA * rsA - r * r) / (2.0 * rsA * rsA * rsA);
                            p = f * (c * ps - phi);
                        } else {
                            p = sA * sg[j] * phi;
                        }
                        u += coef * NA * N[j] * p;
                    }
                    U[i] = u;
                    return;
                }
                double kq = a1, fa = a2, bn = 1.0 / 0.0, bd = 1.0;
                double s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    double rx = px[j] - xi, ry = py[j] - yi, rz = pz[j] - zi;
                    double r = sqrt(fma(rx, rx, fma(ry, ry, rz * rz)));
                    double R = fmax(rA, rad[j]), rt = fmax(R, r);
                    double sc = j == pA ? f * (1.0 + c) : 1.0;
                    double tn = rt * rt * rt, td = kq * sc * (NA + N[j]);
                    if (tn * bd < bn * td) { bn = tn; bd = td; }
                    if (r == 0) continue;
                    double M = fmax(r, R), s = kq * N[j] / (M * M * M);
                    if (mode == 0) { // прямое тяготение: сумма W
                        double q;
                        if (j == pA) {
                            double Ms = fmax(r, rsA);
                            q = f * (c * (kq * N[j] / (Ms * Ms * Ms)) - s);
                        } else {
                            q = sA * sg[j] * s;
                        }
                        s0 = fma(q, rx, s0);
                        s1 = fma(q, ry, s1);
                        s2 = fma(q, rz, s2);
                    } else { // комплексное: отталкивание и приталкивание от КУО
                        double ar, ap;
                        if (j == pA) {
                            double k = fa * NA * f / mA, Ms = fmax(r, rsA);
                            ar = -k * s;
                            ap = k * c * (kq * N[j] / (Ms * Ms * Ms));
                        } else {
                            double aW = sA * sg[j] * fa * NA * s / mA;
                            ar = -b * aW;
                            ap = (1.0 + b) * aW;
                        }
                        s0 = fma(ar, rx, s0);
                        s1 = fma(ar, ry, s1);
                        s2 = fma(ar, rz, s2);
                        s3 = fma(ap, rx, s3);
                        s4 = fma(ap, ry, s4);
                        s5 = fma(ap, rz, s5);
                    }
                }
                if (mode == 0) {
                    double F = fa * NA;
                    o0[i] = F * s0 / mA;
                    o1[i] = F * s1 / mA;
                    o2[i] = F * s2 / mA;
                } else {
                    o0[i] = s0;
                    o1[i] = s1;
                    o2[i] = s2;
                    o3[i] = s3;
                    o4[i] = s4;
                    o5[i] = s5;
                }
                tmin[i] = bn / bd;
            }
            """;

    /**
     * Быстрый приближённый режим «GPU float»: те же формулы во float (~7 верных знаков).
     * Кроме пары полюсов одного диполя: её сила — разность двух почти равных слагаемых, умноженная
     * на f(α) = 10⁶, во float она теряется целиком. Пара одна на тело, её считает хост в double.
     * kQ и 1/m в ядро не входят — их множитель на тело приложит хост в double (при честной G
     * kQ·N меньше наименьшего float). Координаты — от средней точки сцены, чтобы разность
     * соседних тел не терялась на больших координатах. Пары j читаются плитками по TX в общую
     * память блока; пары одного тела делят TY потоков (иначе при 10 000 тел занята восьмая часть видеокарты),
     * их суммы складываются в конце. dynTime: min r³/(kQ·sc·(N_i+N_j)) ⇔ max sc·(N_i+N_j)/max(r,R)³ — без деления.
     */
    /**
     * Barnes–Hut во float: дерево с хоста; обход + refresh COM на GPU (топология между rebuild).
     * uif_bh_ref — снизу вверх обновить листья/внутренние COM после сдвига тел (без host rebuild).
     */
    static final String SRC_BH = """
            extern "C" __global__ void uif_bh_ref(int nNodes,
                const float* __restrict__ px, const float* __restrict__ py, const float* __restrict__ pz,
                const float* __restrict__ N, const float* __restrict__ sg,
                float* __restrict__ ncx, float* __restrict__ ncy, float* __restrict__ ncz,
                float* __restrict__ nq, const float* __restrict__ mass,
                const int* __restrict__ child, const int* __restrict__ body)
            {
                // один поток: узлы нумеруются так, что дети > родителя
                if (blockIdx.x || threadIdx.x) return;
                for (int i = nNodes - 1; i >= 0; i--) {
                    int bj = body[i];
                    if (bj >= 0) {
                        ncx[i] = px[bj]; ncy[i] = py[bj]; ncz[i] = pz[bj];
                        nq[i] = sg[bj] * N[bj];
                    } else if (bj == -1) {
                        float m = 0.0f, qx = 0.0f, qy = 0.0f, qz = 0.0f, ch = 0.0f;
                        for (int o = 0; o < 8; o++) {
                            int c = child[8 * i + o];
                            if (c < 0) continue;
                            float mc = mass[c];
                            m += mc; qx += mc * ncx[c]; qy += mc * ncy[c]; qz += mc * ncz[c];
                            ch += nq[c];
                        }
                        if (m > 0.0f) { ncx[i] = qx / m; ncy[i] = qy / m; ncz[i] = qz / m; }
                        nq[i] = ch;
                    }
                    // body==-2: нет списка тел на GPU — COM до следующего topology-rebuild
                }
            }
            extern "C" __global__ void uif_bh_f(int n, int nNodes, int mode, float theta2, float b,
                const float* __restrict__ px, const float* __restrict__ py, const float* __restrict__ pz,
                const float* __restrict__ N, const float* __restrict__ rad, const float* __restrict__ sg,
                const int* __restrict__ partner,
                const float* __restrict__ ncx, const float* __restrict__ ncy, const float* __restrict__ ncz,
                const float* __restrict__ nq, const float* __restrict__ nw,
                const int* __restrict__ child, const int* __restrict__ body,
                float* o0, float* o1, float* o2, float* o3, float* o4, float* o5, float* best)
            {
                int i = blockIdx.x * blockDim.x + threadIdx.x;
                if (i >= n) return;
                float xi = px[i], yi = py[i], zi = pz[i], NA = N[i], rA = rad[i], sA = sg[i];
                int pA = partner[i];
                float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, bt = 0;
                int stack[64]; int sp = 0;
                if (nNodes > 0) stack[sp++] = 0;
                while (sp > 0) {
                    int ni = stack[--sp];
                    int bj = body[ni];
                    float rx = ncx[ni] - xi, ry = ncy[ni] - yi, rz = ncz[ni] - zi;
                    float r2 = rx * rx + ry * ry + rz * rz;
                    float width = nw[ni];
                    if (bj >= 0) {
                        if (bj == i || bj == pA) continue;
                        float Nj = N[bj], Rj = rad[bj], sj = sg[bj];
                        rx = px[bj] - xi; ry = py[bj] - yi; rz = pz[bj] - zi;
                        r2 = rx * rx + ry * ry + rz * rz;
                        float R = fmaxf(rA, Rj);
                        float inv = rsqrtf(fmaxf(r2, R * R)), inv3 = inv * inv * inv;
                        bt = fmaxf(bt, inv3 * (NA + Nj));
                        if (r2 == 0.0f) continue;
                        float w = sA * sj * Nj * inv3;
                        if (mode == 0) { s0 += w * rx; s1 += w * ry; s2 += w * rz; }
                        else {
                            float ar = -b * w, ap = (1.0f + b) * w;
                            s0 += ar * rx; s1 += ar * ry; s2 += ar * rz;
                            s3 += ap * rx; s4 += ap * ry; s5 += ap * rz;
                        }
                        continue;
                    }
                    if (bj == -2 || (r2 > 0.0f && width * width < theta2 * r2)) {
                        float inv = rsqrtf(fmaxf(r2, rA * rA)), inv3 = inv * inv * inv;
                        float Nj = fabsf(nq[ni]);
                        bt = fmaxf(bt, inv3 * (NA + Nj));
                        if (r2 == 0.0f) continue;
                        float w = sA * nq[ni] * inv3;
                        if (mode == 0) { s0 += w * rx; s1 += w * ry; s2 += w * rz; }
                        else {
                            float ar = -b * w, ap = (1.0f + b) * w;
                            s0 += ar * rx; s1 += ar * ry; s2 += ar * rz;
                            s3 += ap * rx; s4 += ap * ry; s5 += ap * rz;
                        }
                        continue;
                    }
                    for (int o = 0; o < 8; o++) {
                        int c = child[8 * ni + o];
                        if (c >= 0 && sp < 64) stack[sp++] = c;
                    }
                }
                o0[i] = s0; o1[i] = s1; o2[i] = s2;
                if (mode == 1) { o3[i] = s3; o4[i] = s4; o5[i] = s5; }
                best[i] = bt;
            }
            """;

    static final String SRC_F = """
            #define TX 64
            #define TY 8
            extern "C" __global__ void uif_f(int n, int mode, float b,
                const float* __restrict__ px, const float* __restrict__ py, const float* __restrict__ pz,
                const float* __restrict__ N, const float* __restrict__ rad, const float* __restrict__ sg,
                const int* __restrict__ partner,
                float* o0, float* o1, float* o2, float* o3, float* o4, float* o5, float* best)
            {
                __shared__ float sx[TY][TX], sy[TY][TX], sz[TY][TX], sN[TY][TX], sR[TY][TX], sS[TY][TX];
                __shared__ float red[7][TY][TX];
                int tx = threadIdx.x, ty = threadIdx.y;
                int i = blockIdx.x * TX + tx;
                bool act = i < n;
                float xi = 0, yi = 0, zi = 0, NA = 0, rA = 0, sA = 0;
                int pA = -1;
                if (act) { xi = px[i]; yi = py[i]; zi = pz[i]; NA = N[i]; rA = rad[i]; sA = sg[i]; pA = partner[i]; }
                float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, bt = 0;
                // строка ty берёт свою плитку из TX тел j; за круг — TY плиток
                for (int base = 0; base < n; base += TX * TY) {
                    int t0 = base + ty * TX, jj = t0 + tx;
                    if (jj < n) { sx[ty][tx] = px[jj]; sy[ty][tx] = py[jj]; sz[ty][tx] = pz[jj];
                                  sN[ty][tx] = N[jj]; sR[ty][tx] = rad[jj]; sS[ty][tx] = sg[jj]; }
                    __syncthreads();
                    int lim = min(TX, n - t0);
                    if (act) {
                        for (int k = 0; k < lim; k++) {
                            int j = t0 + k;
                            if (j == i || j == pA) continue; // полюс-партнёр — на хосте в double
                            float rx = sx[ty][k] - xi, ry = sy[ty][k] - yi, rz = sz[ty][k] - zi;
                            float r2 = rx * rx + ry * ry + rz * rz;
                            float R = fmaxf(rA, sR[ty][k]);
                            float inv = rsqrtf(fmaxf(r2, R * R)), inv3 = inv * inv * inv;
                            bt = fmaxf(bt, inv3 * (NA + sN[ty][k]));
                            if (r2 == 0.0f) continue;
                            float w = sA * sS[ty][k] * sN[ty][k] * inv3;
                            if (mode == 0) { s0 += w * rx; s1 += w * ry; s2 += w * rz; }
                            else {
                                float ar = -b * w, ap = (1.0f + b) * w;
                                s0 += ar * rx; s1 += ar * ry; s2 += ar * rz;
                                s3 += ap * rx; s4 += ap * ry; s5 += ap * rz;
                            }
                        }
                    }
                    __syncthreads();
                }
                red[0][ty][tx] = s0; red[1][ty][tx] = s1; red[2][ty][tx] = s2;
                red[3][ty][tx] = s3; red[4][ty][tx] = s4; red[5][ty][tx] = s5; red[6][ty][tx] = bt;
                __syncthreads();
                if (ty == 0 && act) {
                    float a0 = 0, a1 = 0, a2 = 0, a3 = 0, a4 = 0, a5 = 0, m = 0;
                    for (int y = 0; y < TY; y++) {
                        a0 += red[0][y][tx]; a1 += red[1][y][tx]; a2 += red[2][y][tx];
                        a3 += red[3][y][tx]; a4 += red[4][y][tx]; a5 += red[5][y][tx];
                        m = fmaxf(m, red[6][y][tx]);
                    }
                    o0[i] = a0; o1[i] = a1; o2[i] = a2;
                    if (mode == 1) { o3[i] = a3; o4[i] = a4; o5[i] = a5; }
                    best[i] = m;
                }
            }

            // ---- весь шаг на видеокарте (GPU float): состояние X, V, A в double, x/y/z блоками по n ----
            // подшаг Йошиды: kick(a, h/2) · drift(h) · силы · kick(a, h/2) — как World.substep

            // kick + drift + координаты во float от точки отсчёта (для uif_f)
            extern "C" __global__ void res_kd(int n, double hk, double hd, double r0x, double r0y, double r0z,
                double* X, double* V, const double* A, float* P)
            {
                int i = blockIdx.x * blockDim.x + threadIdx.x;
                if (i >= n) return;
                double r0[3] = {r0x, r0y, r0z};
                for (int c = 0; c < 3; c++) {
                    int k = c * n + i;
                    double v = hk != 0 ? V[k] + A[k] * hk : V[k]; // в начале A ещё не посчитано
                    double x = X[k] + v * hd;
                    V[k] = v;
                    X[k] = x;
                    P[k] = (float) (x - r0[c]);
                }
            }

            // суммы uif_f (O: x, y, z, best блоками по n) → ускорения в double + пара полюсов в double, tmin, kick
            extern "C" __global__ void res_fin(int n, double kq, double fa, double hk,
                const double* X, double* V, double* A, double* T,
                const double* N, const double* rad, const double* fIn, const double* chi, const double* rs, const double* m,
                const int* partner, const float* O)
            {
                int i = blockIdx.x * blockDim.x + threadIdx.x;
                if (i >= n) return;
                double k = fa * N[i] * kq / m[i];
                double ax = k * (double) O[i], ay = k * (double) O[n + i], az = k * (double) O[2 * n + i];
                double t = 1.0 / (kq * (double) O[3 * n + i]);
                int p = partner[i];
                if (p >= 0) { // полюс-партнёр — в double, как Gpu.computeFloat на хосте
                    double rx = X[p] - X[i], ry = X[n + p] - X[n + i], rz = X[2 * n + p] - X[2 * n + i];
                    double r = sqrt(rx * rx + ry * ry + rz * rz);
                    double R = fmax(rad[i], rad[p]), rt = fmax(R, r), fi = fIn[i], c = chi[i];
                    t = fmin(t, rt * rt * rt / (kq * fi * (1.0 + c) * (N[i] + N[p])));
                    if (r > 0) {
                        double M = fmax(r, R), s = kq * N[p] / (M * M * M);
                        double Ms = fmax(r, rs[i]), ss = kq * N[p] / (Ms * Ms * Ms);
                        double q = fa * N[i] * fi / m[i] * (c * ss - s);
                        ax += q * rx; ay += q * ry; az += q * rz;
                    }
                }
                A[i] = ax; A[n + i] = ay; A[2 * n + i] = az;
                T[i] = t;
                V[i] += ax * hk; V[n + i] += ay * hk; V[2 * n + i] += az * hk;
            }

            // min tmin по всем телам — один блок
            extern "C" __global__ void res_min(int n, const double* T, double* out)
            {
                __shared__ double s[256];
                double m = 1.0 / 0.0;
                for (int i = threadIdx.x; i < n; i += 256) m = fmin(m, T[i]);
                s[threadIdx.x] = m;
                __syncthreads();
                for (int w = 128; w > 0; w >>= 1) {
                    if (threadIdx.x < w) s[threadIdx.x] = fmin(s[threadIdx.x], s[threadIdx.x + w]);
                    __syncthreads();
                }
                if (threadIdx.x == 0) out[0] = s[0];
            }

            // ---- замкнутая область на видеокарте (те же формулы, что Chamber, в double; веса узлов — expf) ----
            // CH: 0..2 c, 3..5 v_c, 6..8 a_c, 9 потери, 10 R̄, 11 ΣV_k, 12 давление тел, 13..21 сумма для v_c' (вязкость),
            //     22..24 сила тел на центр, 25 Σ G·common, 26 min R_k, 27 max R_k;
            //     32 + k: R_k, 32 + CM + k: V_k, 32 + 2·CM + k: a_k, 32 + 3·CM + k: сила тел на узел (без common).
            // P: 0 N стенки, 1 δ, 2 kQ, 3 R₀, 4 K/M, 5 натяжение, 6 p внешн., 7 m/M, 8 m, 9 visc, 10 ω, 11 hFrac,
            //    12 2ζω, 13 s (резкость весов), 14 WALL_SIGN·k_v

            __device__ double warpSum(double v) {
                for (int o = 16; o > 0; o >>= 1) v += __shfl_down_sync(0xffffffff, v, o);
                return v;
            }

            struct Gap { bool ok; double d, rho, n0, n1, n2, f, s0inv, common, dX0, dX1, dX2, W; };

            // d = R_s − ρ и, по просьбе, ∂d/∂x и W = Σ ∂R_s/∂R_k·V_k — как Chamber.gap / surface
            __device__ void gapAt(double px, double py, double pz, const double* CH, const double* P, const double* DIR,
                                  bool wantDX, bool wantW, Gap& g)
            {
                double x = px - CH[0], y = py - CH[1], z = pz - CH[2];
                double rho = sqrt(x * x + y * y + z * z), Rm = CH[10];
                g.rho = rho;
                g.ok = rho > 0;
                g.d = Rm - rho;
                if (!g.ok) return;
                double n0 = x / rho, n1 = y / rho, n2 = z / rho;
                const double* Rk = CH + 32;
                const double* Vk = CH + 32 + CM;
                float S = (float) P[13];
                double s0 = 0, s1 = 0, gx = 0, gy = 0, gz = 0, hx = 0, hy = 0, hz = 0, sv = 0;
                for (int k = 0; k < CM; k++) {
                    double a = DIR[3 * k], b = DIR[3 * k + 1], c = DIR[3 * k + 2];
                    double w = (double) __expf(S * (float) (n0 * a + n1 * b + n2 * c - 1.0));
                    double R = Rk[k];
                    s0 += w;
                    s1 += w * R;
                    if (wantDX) { gx += w * R * a; gy += w * R * b; gz += w * R * c; hx += w * a; hy += w * b; hz += w * c; }
                    if (wantW) sv += w * Vk[k];
                }
                double Rw = s1 / s0, sv_ = rho / Rm, dev = Rw - Rm, q = 1 - sv_ * sv_;
                double f = sv_ < 1 ? 1 - q * q * q : 1, fp = sv_ < 1 ? 6 * sv_ * q * q : 0;
                g.d = Rm + dev * f - rho;
                g.f = f;
                g.s0inv = 1 / s0;
                g.common = (1 - f - dev * fp * sv_ / Rm) / CM;
                g.n0 = n0; g.n1 = n1; g.n2 = n2;
                if (wantDX) {
                    double Sd = P[13];
                    double r0 = Sd * (gx - Rw * hx) / s0, r1 = Sd * (gy - Rw * hy) / s0, r2 = Sd * (gz - Rw * hz) / s0;
                    double gn = r0 * n0 + r1 * n1 + r2 * n2;
                    double ft = sv_ < 1 ? sv_ * (3 - 3 * sv_ * sv_ + sv_ * sv_ * sv_ * sv_) / Rm : 1 / rho, radial = dev * fp / Rm - 1;
                    g.dX0 = (r0 - gn * n0) * ft + radial * n0;
                    g.dX1 = (r1 - gn * n1) * ft + radial * n1;
                    g.dX2 = (r2 - gn * n2) * ft + radial * n2;
                }
                if (wantW) g.W = f * sv / s0 + g.common * CH[11];
            }

            // kick (hk) и drift (hd) стенки, затем R̄, ΣV_k, min/max R_k — как Chamber.kick / drift. Один блок 256.
            extern "C" __global__ void ch_kd(double hk, double hd, double* CH, const double* P)
            {
                __shared__ double sR[256], sV[256], sMin[256], sMax[256];
                int k = threadIdx.x;
                double* Rk = CH + 32;
                double* Vk = CH + 32 + CM;
                const double* aR = CH + 32 + 2 * CM;
                double R = 0, V = 0, mn = 1e300, mx = -1e300;
                if (k < CM) {
                    double v = Vk[k] + aR[k] * hk, r = Rk[k];
                    if (hd != 0) {
                        r += v * hd;
                        if (r < 0.05 * P[3]) { r = 0.05 * P[3]; v = fmax(0.0, v); } // страховка, как в Chamber.drift
                    }
                    Rk[k] = r; Vk[k] = v;
                    R = r; V = v; mn = r; mx = r;
                }
                sR[k] = R; sV[k] = V; sMin[k] = mn; sMax[k] = mx;
                __syncthreads();
                for (int w = 128; w > 0; w >>= 1) {
                    if (k < w) { sR[k] += sR[k + w]; sV[k] += sV[k + w]; sMin[k] = fmin(sMin[k], sMin[k + w]); sMax[k] = fmax(sMax[k], sMax[k + w]); }
                    __syncthreads();
                }
                if (k == 0) {
                    for (int c = 0; c < 3; c++) { CH[3 + c] += CH[6 + c] * hk; CH[c] += CH[3 + c] * hd; }
                    CH[10] = sR[0] / CM; CH[11] = sV[0]; CH[26] = sMin[0]; CH[27] = sMax[0];
                }
            }

            // поле стенки на тела (в A), реакция на узлы и центр (суммы в CH), время стенки (в T) — Chamber.accelerate / dynTime
            extern "C" __global__ void ch_wall(int n, const double* X, double* A, double* T, const double* N, const double* m,
                double* CH, const double* P, const double* DIR)
            {
                __shared__ double part[4][CM + 4];
                int i = blockIdx.x * 128 + threadIdx.x, lane = threadIdx.x & 31, wp = threadIdx.x >> 5;
                double coef = 0, F0 = 0, F1 = 0, F2 = 0, cm = 0, n0 = 0, n1 = 0, n2 = 0;
                if (i < n) {
                    Gap g;
                    gapAt(X[i], X[n + i], X[2 * n + i], CH, P, DIR, true, false, g);
                    double kq = P[2], Nw = P[0], dl = P[1], d = g.d;
                    if (g.ok && d > 0) {
                        double qn = d >= dl ? kq * Nw / (d * d) : kq * Nw * d / (dl * dl * dl);
                        double G = P[14] * N[i] * qn;
                        A[i] -= G * g.dX0 / m[i];
                        A[n + i] -= G * g.dX1 / m[i];
                        A[2 * n + i] -= G * g.dX2 / m[i];
                        F0 = G * g.dX0; F1 = G * g.dX1; F2 = G * g.dX2;
                        cm = G * g.common;
                        coef = -G * g.f * g.s0inv;
                        n0 = g.n0; n1 = g.n1; n2 = g.n2;
                    }
                    double dd = fmax(dl, fabs(d)), t = dd * dd * dd / (kq * Nw);
                    if (d > 0) {
                        double rho = fmax(dl, g.rho), acc = d >= dl ? kq * Nw / (d * d) : kq * Nw * d / (dl * dl * dl);
                        if (acc > 0) t = fmin(t, rho / acc);
                    }
                    T[i] = fmin(T[i], t);
                }
                float S = (float) P[13];
                for (int k = 0; k < CM; k++) { // сила на узел k: Σ_i −G_i·f_i·w_ik/s0_i — по варпу, потом по блоку
                    double v = 0;
                    if (coef != 0) v = coef * (double) __expf(S * (float) (n0 * DIR[3 * k] + n1 * DIR[3 * k + 1] + n2 * DIR[3 * k + 2] - 1.0));
                    v = warpSum(v);
                    if (lane == 0) part[wp][k] = v;
                }
                F0 = warpSum(F0); F1 = warpSum(F1); F2 = warpSum(F2); cm = warpSum(cm);
                if (lane == 0) { part[wp][CM] = F0; part[wp][CM + 1] = F1; part[wp][CM + 2] = F2; part[wp][CM + 3] = cm; }
                __syncthreads();
                for (int k = threadIdx.x; k < CM + 4; k += 128) {
                    double s = part[0][k] + part[1][k] + part[2][k] + part[3][k];
                    if (s != 0) atomicAdd(k < CM ? &CH[32 + 3 * CM + k] : &CH[22 + (k - CM)], s);
                }
            }

            // узлы: сила тел, пружина к R₀, внешнее давление, натяжение соседей → a_k; a_c; давление тел. Один блок 256.
            extern "C" __global__ void ch_nodes(double* CH, const double* P, const int* ADJ)
            {
                __shared__ double red[256];
                int k = threadIdx.x;
                double* Rk = CH + 32;
                double* aR = CH + 32 + 2 * CM;
                double* Fk = CH + 32 + 3 * CM;
                double common = CH[25], bp = 0;
                if (k < CM) {
                    double fb = Fk[k] - common;
                    bp = fb;
                    double F = fb - P[4] * (Rk[k] - P[3]) - P[6] * 4 * 3.141592653589793 * Rk[k] * Rk[k] / CM;
                    for (int e = 0; e < CDEG; e++) {
                        int j = ADJ[k * CDEG + e];
                        if (j >= 0) F -= P[5] * (Rk[k] - Rk[j]);
                    }
                    aR[k] = F / P[7];
                }
                red[k] = bp;
                __syncthreads();
                for (int w = 128; w > 0; w >>= 1) {
                    if (k < w) red[k] += red[k + w];
                    __syncthreads();
                }
                if (k == 0) {
                    CH[12] = red[0];
                    for (int c = 0; c < 3; c++) CH[6 + c] = CH[22 + c] / P[8];
                }
                __syncthreads();
                if (k < CM) Fk[k] = 0;
                if (k == 0) { CH[22] = 0; CH[23] = 0; CH[24] = 0; CH[25] = 0; }
            }

            // вязкий слой, 1: у каждого тела в слое — κ, n̂, s = v·n̂ − W; суммы для системы 3×3 (Chamber.damp)
            extern "C" __global__ void ch_damp1(int n, double dt, const double* X, const double* V, const double* m,
                double* CH, const double* P, const double* DIR, double* D)
            {
                __shared__ double part[4][9];
                int i = blockIdx.x * 128 + threadIdx.x, lane = threadIdx.x & 31, wp = threadIdx.x >> 5;
                double a[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
                if (i < n) {
                    D[i] = 0;
                    double x = X[i] - CH[0], y = X[n + i] - CH[1], z = X[2 * n + i] - CH[2];
                    // в слое только при ρ > (1 − hFrac)·R_s ≥ (1 − hFrac)·min R_k — остальные без перебора узлов
                    if (sqrt(x * x + y * y + z * z) > (1 - P[11]) * CH[26]) {
                        Gap g;
                        gapAt(X[i], X[n + i], X[2 * n + i], CH, P, DIR, false, true, g);
                        double h = P[11] * (g.d + g.rho);
                        if (g.ok && g.d > 0 && g.d < h) {
                            double mi = m[i], mw = P[8], e = 1 - g.d / h;
                            double mu = mi * mw / (mi + mw), ka = mu * expm1(P[9] * P[10] * e * e * dt);
                            double al = ka * mi / (mi + ka);
                            double s = V[i] * g.n0 + V[n + i] * g.n1 + V[2 * n + i] * g.n2 - g.W;
                            a[0] = al * g.n0 * g.n0; a[1] = al * g.n1 * g.n1; a[2] = al * g.n2 * g.n2;
                            a[3] = al * g.n0 * g.n1; a[4] = al * g.n0 * g.n2; a[5] = al * g.n1 * g.n2;
                            a[6] = al * s * g.n0; a[7] = al * s * g.n1; a[8] = al * s * g.n2;
                            D[i] = ka; D[n + i] = g.n0; D[2 * n + i] = g.n1; D[3 * n + i] = g.n2; D[4 * n + i] = s;
                        }
                    }
                }
                for (int c = 0; c < 9; c++) {
                    double v = warpSum(a[c]);
                    if (lane == 0) part[wp][c] = v;
                }
                __syncthreads();
                if (threadIdx.x < 9) {
                    int c = threadIdx.x;
                    double s = part[0][c] + part[1][c] + part[2][c] + part[3][c];
                    if (s != 0) atomicAdd(&CH[13 + c], s);
                }
            }

            // вязкий слой, 2: v_c' из системы 3×3, потери центра; затухание узлов. Один блок 256.
            extern "C" __global__ void ch_damp2(double dt, double* CH, const double* P)
            {
                __shared__ double red[256];
                int k = threadIdx.x;
                double* Vk = CH + 32 + CM;
                double ls = 0;
                if (k < CM) {
                    double v = Vk[k], v2 = v * exp(-P[12] * dt);
                    ls = 0.5 * P[7] * (v * v - v2 * v2);
                    Vk[k] = v2;
                }
                red[k] = ls;
                __syncthreads();
                for (int w = 128; w > 0; w >>= 1) {
                    if (k < w) red[k] += red[k + w];
                    __syncthreads();
                }
                if (k == 0) {
                    double mw = P[8];
                    double a = mw + CH[13], e = mw + CH[14], ii = mw + CH[15], bb = CH[16], c = CH[17], f = CH[18];
                    double b0 = mw * CH[3] + CH[19], b1 = mw * CH[4] + CH[20], b2 = mw * CH[5] + CH[21];
                    double c0 = e * ii - f * f, c1 = c * f - bb * ii, c2 = bb * f - c * e;
                    double det = a * c0 + bb * c1 + c * c2;
                    double v0 = (c0 * b0 + c1 * b1 + c2 * b2) / det;
                    double v1 = (c1 * b0 + (a * ii - c * c) * b1 + (bb * c - a * f) * b2) / det;
                    double v2 = (c2 * b0 + (bb * c - a * f) * b1 + (a * e - bb * bb) * b2) / det;
                    double old = CH[3] * CH[3] + CH[4] * CH[4] + CH[5] * CH[5];
                    CH[9] += 0.5 * mw * (old - (v0 * v0 + v1 * v1 + v2 * v2)) + red[0];
                    CH[3] = v0; CH[4] = v1; CH[5] = v2;
                    for (int c = 13; c <= 21; c++) CH[c] = 0;
                }
            }

            // вязкий слой, 3: скорости тел по найденной v_c', потери тел
            extern "C" __global__ void ch_damp3(int n, double* V, const double* m, double* CH, const double* D)
            {
                __shared__ double part[4];
                int i = blockIdx.x * 128 + threadIdx.x, lane = threadIdx.x & 31, wp = threadIdx.x >> 5;
                double l = 0;
                if (i < n) {
                    double ka = D[i];
                    if (ka != 0) { // в среднем подшаге Йошиды dt < 0 и κ < 0 — тело всё равно в слое
                        double n0 = D[n + i], n1 = D[2 * n + i], n2 = D[3 * n + i], s = D[4 * n + i], mi = m[i];
                        double u = (s - (n0 * CH[3] + n1 * CH[4] + n2 * CH[5])) * mi / (mi + ka);
                        double ds = -ka * u / mi, s0 = V[i] * n0 + V[n + i] * n1 + V[2 * n + i] * n2;
                        V[i] += ds * n0; V[n + i] += ds * n1; V[2 * n + i] += ds * n2;
                        l = 0.5 * mi * (s0 * s0 - (s0 + ds) * (s0 + ds));
                    }
                }
                l = warpSum(l);
                if (lane == 0) part[wp] = l;
                __syncthreads();
                if (threadIdx.x == 0) {
                    double s = part[0] + part[1] + part[2] + part[3];
                    if (s != 0) atomicAdd(&CH[9], s);
                }
            }
            """;

    private static final int TX = 64, TY = 8; // float: тело — TX в блоке, его пары делят TY потоков
    private static final int BLOCK = 64; // 10 000 тел — 157 блоков на 56 мультипроцессоров
    private static final Linker L = Linker.nativeLinker();

    private final Arena arena = Arena.ofShared();
    private final SymbolLookup cuda;
    private final MethodHandle memAlloc, memFree, htod, dtoh, launch, sync, setCtx;
    private final MemorySegment ctx, fn, fnF, fnBh, fnBhRef, fnKd, fnFin, fnMin, fnChKd, fnWall, fnNodes, fnD1, fnD2, fnD3;
    private final int dev;
    private final String name;

    // буферы на видеокарте: координаты одним куском (x, y, z подряд — одна загрузка за расчёт),
    // 7 массивов свойств (загружаются, когда меняется состав), partner, выходы одним куском
    // (нужные выходы подряд — одна выгрузка). Указатели x/y/z и выходов сдвигаются под n.
    private static final int PROPS = 7, OUT = 8;
    private final long[] dProp = new long[PROPS];
    private long dPos, dOutAll, dPartner;
    private final MemorySegment[] posArg = new MemorySegment[3], outArg = new MemorySegment[OUT];
    private int cap;
    private long uploaded = -1;
    private MemorySegment host, hostInt, args;
    private MemorySegment aN, aMode, aA1, aA2, aB;
    // то же для float: координаты, 3 свойства (N, rad, sg), 7 выходов; partner общий
    private static final int PROPS_F = 3, OUT_F = 7;
    private final long[] dPropF = new long[PROPS_F];
    private long dPosF, dOutF;
    private final MemorySegment[] posArgF = new MemorySegment[3], outArgF = new MemorySegment[OUT_F];
    private long uploadedF = -1;
    private MemorySegment hostF, argsF, aBF;
    // Barnes–Hut: один float-блок (cx..mass) + один int-блок (child|body) — меньше HtoD
    private int capBh;
    private long dBhF, dBhI; // base
    private long dBhCx, dBhCy, dBhCz, dBhQ, dBhW, dBhMass, dBhChild, dBhBody;
    private MemorySegment hostBhF, hostBhI, argsBh, argsBhRef, aNNodes, aTheta2;
    /** Начало координат последнего upload дерева — позиции между rebuild берут его же. */
    private double bhOx, bhOy, bhOz;
    /** Staging выгрузки сил BH (без getAtIndex на каждый элемент). */
    private float[] bhOutHost = new float[0];
    // весь шаг: X, V, A (3·cap double), T (cap), минимум; параметры ядер
    private long dX, dV, dA, dT, dMin;
    private MemorySegment argsKd, argsFin, argsMin, one;
    private final MemorySegment sHk, sHd, sHk2, sKq, sFa, sR0x, sR0y, sR0z;
    private int rn; // число тел в идущем шаге
    // замкнутая область: состояние CH, параметры P, направления узлов, соседи; черновик вязкости D (5·cap)
    private static final int CH_LEN = 32 + 4 * Chamber.M, P_LEN = 15;
    private long dCH, dP, dDir, dAdj, dD;
    private MemorySegment argsChKd, argsWall, argsNodes, argsD1, argsD2, argsD3, chHost;
    private final MemorySegment sChHk, sChHd, sDt;
    private Chamber chamber; // не null — шаги с замкнутой областью
    private double nodeT; // квадрат самого короткого времени мод узлов (из Chamber.dynTime)

    private Gpu() throws Throwable {
        cuda = SymbolLookup.libraryLookup("nvcuda", arena);
        MethodHandle init = h(cuda, "cuInit", JAVA_INT);
        ok((int) init.invoke(0), "cuInit");
        MemorySegment d = arena.allocate(JAVA_INT);
        ok((int) h(cuda, "cuDeviceGet", ADDRESS, JAVA_INT).invoke(d, 0), "cuDeviceGet");
        dev = d.get(JAVA_INT, 0);
        MemorySegment nm = arena.allocate(256);
        ok((int) h(cuda, "cuDeviceGetName", ADDRESS, JAVA_INT, JAVA_INT).invoke(nm, 256, dev), "cuDeviceGetName");
        name = nm.getString(0);
        MethodHandle attr = h(cuda, "cuDeviceGetAttribute", ADDRESS, JAVA_INT, JAVA_INT);
        MemorySegment v = arena.allocate(JAVA_INT);
        ok((int) attr.invoke(v, 75, dev), "compute capability");
        int major = v.get(JAVA_INT, 0);
        ok((int) attr.invoke(v, 76, dev), "compute capability");
        int minor = v.get(JAVA_INT, 0);

        MemorySegment c = arena.allocate(ADDRESS);
        ok((int) h(cuda, "cuDevicePrimaryCtxRetain", ADDRESS, JAVA_INT).invoke(c, dev), "cuDevicePrimaryCtxRetain");
        ctx = c.get(ADDRESS, 0);
        setCtx = h(cuda, "cuCtxSetCurrent", ADDRESS);
        ok((int) setCtx.invoke(ctx), "cuCtxSetCurrent");

        String arch = "--gpu-architecture=compute_" + major + minor;
        fn = load(compile(SRC, arch, "--fmad=false", "--prec-div=true", "--prec-sqrt=true"), "uif");
        String defs = "#define CM " + Chamber.M + "\n#define CDEG " + Chamber.MAX_DEG + "\n";
        MemorySegment ptxF = compile(defs + SRC_F, arch, "--use_fast_math"); // fast math трогает только float; double — по IEEE
        fnF = load(ptxF, "uif_f");
        fnKd = load(ptxF, "res_kd");
        fnFin = load(ptxF, "res_fin");
        fnMin = load(ptxF, "res_min");
        fnChKd = load(ptxF, "ch_kd");
        fnWall = load(ptxF, "ch_wall");
        fnNodes = load(ptxF, "ch_nodes");
        fnD1 = load(ptxF, "ch_damp1");
        fnD2 = load(ptxF, "ch_damp2");
        fnD3 = load(ptxF, "ch_damp3");
        MemorySegment ptxBh = compile(SRC_BH, arch, "--use_fast_math");
        fnBh = load(ptxBh, "uif_bh_f");
        fnBhRef = load(ptxBh, "uif_bh_ref");

        memAlloc = h(cuda, "cuMemAlloc_v2", ADDRESS, JAVA_LONG);
        memFree = h(cuda, "cuMemFree_v2", JAVA_LONG);
        htod = h(cuda, "cuMemcpyHtoD_v2", JAVA_LONG, ADDRESS, JAVA_LONG);
        dtoh = h(cuda, "cuMemcpyDtoH_v2", ADDRESS, JAVA_LONG, JAVA_LONG);
        launch = h(cuda, "cuLaunchKernel", ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        sync = h(cuda, "cuCtxSynchronize");
        aN = arena.allocate(JAVA_INT);
        aMode = arena.allocate(JAVA_INT);
        aA1 = arena.allocate(JAVA_DOUBLE);
        aA2 = arena.allocate(JAVA_DOUBLE);
        aB = arena.allocate(JAVA_DOUBLE);
        aBF = arena.allocate(JAVA_FLOAT);
        aNNodes = arena.allocate(JAVA_INT);
        aTheta2 = arena.allocate(JAVA_FLOAT);
        sHk = arena.allocate(JAVA_DOUBLE);
        sHd = arena.allocate(JAVA_DOUBLE);
        sHk2 = arena.allocate(JAVA_DOUBLE);
        sKq = arena.allocate(JAVA_DOUBLE);
        sFa = arena.allocate(JAVA_DOUBLE);
        sR0x = arena.allocate(JAVA_DOUBLE);
        sR0y = arena.allocate(JAVA_DOUBLE);
        sR0z = arena.allocate(JAVA_DOUBLE);
        one = arena.allocate(JAVA_DOUBLE);
        sChHk = arena.allocate(JAVA_DOUBLE);
        sChHd = arena.allocate(JAVA_DOUBLE);
        sDt = arena.allocate(JAVA_DOUBLE);
        // замкнутая область: буферы не зависят от числа тел, кроме черновика D
        MemorySegment dd = arena.allocate(JAVA_LONG);
        ok((int) memAlloc.invoke(dd, 8L * CH_LEN), "cuMemAlloc");
        dCH = dd.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(dd, 8L * P_LEN), "cuMemAlloc");
        dP = dd.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(dd, 8L * 3 * Chamber.M), "cuMemAlloc");
        dDir = dd.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(dd, 4L * Chamber.M * Chamber.MAX_DEG), "cuMemAlloc");
        dAdj = dd.get(JAVA_LONG, 0);
        chHost = arena.allocate(JAVA_DOUBLE, CH_LEN);
        for (int k = 0; k < Chamber.M; k++) for (int q = 0; q < 3; q++) chHost.setAtIndex(JAVA_DOUBLE, 3L * k + q, Chamber.DIRS[k][q]);
        ok((int) htod.invoke(dDir, chHost, 8L * 3 * Chamber.M), "cuMemcpyHtoD");
        MemorySegment adj = arena.allocate(JAVA_INT, (long) Chamber.M * Chamber.MAX_DEG);
        for (int k = 0; k < Chamber.M; k++) {
            for (int e = 0; e < Chamber.MAX_DEG; e++) adj.setAtIndex(JAVA_INT, (long) k * Chamber.MAX_DEG + e, e < Chamber.ADJ[k].length ? Chamber.ADJ[k][e] : -1);
        }
        ok((int) htod.invoke(dAdj, adj, 4L * Chamber.M * Chamber.MAX_DEG), "cuMemcpyHtoD");
        argsChKd = params(sChHk, sChHd, ptr(dCH), ptr(dP));
        argsNodes = params(ptr(dCH), ptr(dP), ptr(dAdj));
        argsD2 = params(sDt, ptr(dCH), ptr(dP));
    }

    private final java.util.Map<MemorySegment, MemorySegment> modules = new java.util.HashMap<>();

    /** Модуль из PTX (один на PTX) и ядро в нём. */
    private MemorySegment load(MemorySegment ptx, String kernel) throws Throwable {
        MemorySegment module = modules.get(ptx);
        if (module == null) {
            MemorySegment mod = arena.allocate(ADDRESS);
            ok((int) h(cuda, "cuModuleLoadData", ADDRESS, ADDRESS).invoke(mod, ptx), "cuModuleLoadData");
            module = mod.get(ADDRESS, 0);
            modules.put(ptx, module);
        }
        MemorySegment f = arena.allocate(ADDRESS);
        ok((int) h(cuda, "cuModuleGetFunction", ADDRESS, ADDRESS, ADDRESS).invoke(f, module, arena.allocateFrom(kernel)), "cuModuleGetFunction");
        return f.get(ADDRESS, 0);
    }

    /** Открыть видеокарту и собрать ядро; бросает исключение с причиной, если CUDA нет. */
    static Gpu open() throws Throwable {
        try {
            return new Gpu();
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("драйвер NVIDIA (nvcuda.dll) не найден");
        } catch (IllegalCallerException e) {
            throw new RuntimeException("нужен запуск с --enable-native-access=ALL-UNNAMED");
        }
    }

    String name() {
        return name;
    }

    private static MethodHandle h(SymbolLookup lib, String fn, MemoryLayout... args) {
        return L.downcallHandle(lib.find(fn).orElseThrow(() -> new RuntimeException("нет функции " + fn)),
                FunctionDescriptor.of(JAVA_INT, args));
    }

    private static void ok(int r, String what) {
        if (r != 0) throw new RuntimeException(what + ": ошибка CUDA " + r);
    }

    /** NVRTC из CUDA Toolkit: CUDA_PATH\bin\x64 (CUDA 13) или CUDA_PATH\bin; берётся самая новая. */
    private MemorySegment compile(String src, String... opts) throws Throwable {
        String home = System.getenv("CUDA_PATH");
        if (home == null) throw new RuntimeException("CUDA Toolkit не найден (нет CUDA_PATH)");
        List<Path> found = new ArrayList<>();
        for (Path dir : List.of(Path.of(home, "bin", "x64"), Path.of(home, "bin"))) {
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().matches("nvrtc64_\\d+(_0)?\\.dll")).forEach(found::add);
            }
        }
        Path dll = found.stream().max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow(() -> new RuntimeException("NVRTC не найден в " + home));
        SymbolLookup nv = SymbolLookup.libraryLookup(dll, arena);
        MemorySegment prog = arena.allocate(ADDRESS);
        ok((int) h(nv, "nvrtcCreateProgram", ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
                .invoke(prog, arena.allocateFrom(src), arena.allocateFrom("uif.cu"), 0, MemorySegment.NULL, MemorySegment.NULL), "nvrtcCreateProgram");
        MemorySegment p = prog.get(ADDRESS, 0);
        MemorySegment optv = arena.allocate(ADDRESS, opts.length);
        for (int i = 0; i < opts.length; i++) optv.setAtIndex(ADDRESS, i, arena.allocateFrom(opts[i]));
        int r = (int) h(nv, "nvrtcCompileProgram", ADDRESS, JAVA_INT, ADDRESS).invoke(p, opts.length, optv);
        if (r != 0) {
            MemorySegment ls = arena.allocate(JAVA_LONG);
            h(nv, "nvrtcGetProgramLogSize", ADDRESS, ADDRESS).invoke(p, ls);
            MemorySegment log = arena.allocate(Math.max(1, ls.get(JAVA_LONG, 0)));
            h(nv, "nvrtcGetProgramLog", ADDRESS, ADDRESS).invoke(p, log);
            throw new RuntimeException("NVRTC: " + log.getString(0));
        }
        MemorySegment sz = arena.allocate(JAVA_LONG);
        ok((int) h(nv, "nvrtcGetPTXSize", ADDRESS, ADDRESS).invoke(p, sz), "nvrtcGetPTXSize");
        MemorySegment ptx = arena.allocate(sz.get(JAVA_LONG, 0));
        ok((int) h(nv, "nvrtcGetPTX", ADDRESS, ADDRESS).invoke(p, ptx), "nvrtcGetPTX");
        return ptx;
    }

    private void ensure(int n) throws Throwable {
        if (n <= cap) return;
        free();
        cap = Math.max(n, cap * 3 / 2);
        MemorySegment d = arena.allocate(JAVA_LONG);
        ok((int) memAlloc.invoke(d, 8L * 3 * cap), "cuMemAlloc");
        dPos = d.get(JAVA_LONG, 0);
        for (int k = 0; k < PROPS; k++) {
            ok((int) memAlloc.invoke(d, 8L * cap), "cuMemAlloc");
            dProp[k] = d.get(JAVA_LONG, 0);
        }
        ok((int) memAlloc.invoke(d, 8L * OUT * cap), "cuMemAlloc");
        dOutAll = d.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(d, 4L * cap), "cuMemAlloc");
        dPartner = d.get(JAVA_LONG, 0);
        host = arena.allocate(JAVA_DOUBLE, (long) OUT * cap);
        hostInt = arena.allocate(JAVA_INT, cap);
        // параметры ядра: n, mode, a1, a2, b, px, py, pz, 7 свойств, partner, 8 выходов
        args = arena.allocate(ADDRESS, 5 + 3 + PROPS + 1 + OUT);
        args.setAtIndex(ADDRESS, 0, aN);
        args.setAtIndex(ADDRESS, 1, aMode);
        args.setAtIndex(ADDRESS, 2, aA1);
        args.setAtIndex(ADDRESS, 3, aA2);
        args.setAtIndex(ADDRESS, 4, aB);
        int k = 5;
        for (int c = 0; c < 3; c++) args.setAtIndex(ADDRESS, k++, posArg[c] = ptr(dPos));
        for (long p : dProp) args.setAtIndex(ADDRESS, k++, ptr(p));
        args.setAtIndex(ADDRESS, k++, ptr(dPartner));
        for (int c = 0; c < OUT; c++) args.setAtIndex(ADDRESS, k++, outArg[c] = ptr(dOutAll));
        uploaded = -1;
        // float: n, mode, b, px, py, pz, 3 свойства, partner, 7 выходов
        ok((int) memAlloc.invoke(d, 4L * 3 * cap), "cuMemAlloc");
        dPosF = d.get(JAVA_LONG, 0);
        for (int c = 0; c < PROPS_F; c++) {
            ok((int) memAlloc.invoke(d, 4L * cap), "cuMemAlloc");
            dPropF[c] = d.get(JAVA_LONG, 0);
        }
        ok((int) memAlloc.invoke(d, 4L * OUT_F * cap), "cuMemAlloc");
        dOutF = d.get(JAVA_LONG, 0);
        hostF = arena.allocate(JAVA_FLOAT, (long) OUT_F * cap);
        argsF = arena.allocate(ADDRESS, 3 + 3 + PROPS_F + 1 + OUT_F);
        argsF.setAtIndex(ADDRESS, 0, aN);
        argsF.setAtIndex(ADDRESS, 1, aMode);
        argsF.setAtIndex(ADDRESS, 2, aBF);
        k = 3;
        for (int c = 0; c < 3; c++) argsF.setAtIndex(ADDRESS, k++, posArgF[c] = ptr(dPosF));
        for (long p : dPropF) argsF.setAtIndex(ADDRESS, k++, ptr(p));
        argsF.setAtIndex(ADDRESS, k++, ptr(dPartner));
        for (int c = 0; c < OUT_F; c++) argsF.setAtIndex(ADDRESS, k++, outArgF[c] = ptr(dOutF));
        uploadedF = -1;
        // весь шаг
        ok((int) memAlloc.invoke(d, 8L * 3 * cap), "cuMemAlloc");
        dX = d.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(d, 8L * 3 * cap), "cuMemAlloc");
        dV = d.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(d, 8L * 3 * cap), "cuMemAlloc");
        dA = d.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(d, 8L * cap), "cuMemAlloc");
        dT = d.get(JAVA_LONG, 0);
        ok((int) memAlloc.invoke(d, 8L), "cuMemAlloc");
        dMin = d.get(JAVA_LONG, 0);
        argsKd = params(aN, sHk, sHd, sR0x, sR0y, sR0z, ptr(dX), ptr(dV), ptr(dA), ptr(dPosF));
        argsFin = params(aN, sKq, sFa, sHk2, ptr(dX), ptr(dV), ptr(dA), ptr(dT),
                ptr(dProp[0]), ptr(dProp[1]), ptr(dProp[3]), ptr(dProp[4]), ptr(dProp[5]), ptr(dProp[6]), ptr(dPartner), ptr(dOutF));
        argsMin = params(aN, ptr(dT), ptr(dMin));
        ok((int) memAlloc.invoke(d, 8L * 5 * cap), "cuMemAlloc");
        dD = d.get(JAVA_LONG, 0);
        argsWall = params(aN, ptr(dX), ptr(dA), ptr(dT), ptr(dProp[0]), ptr(dProp[6]), ptr(dCH), ptr(dP), ptr(dDir));
        argsD1 = params(aN, sDt, ptr(dX), ptr(dV), ptr(dProp[6]), ptr(dCH), ptr(dP), ptr(dDir), ptr(dD));
        argsD3 = params(aN, ptr(dV), ptr(dProp[6]), ptr(dCH), ptr(dD));
    }

    private MemorySegment params(MemorySegment... p) {
        MemorySegment a = arena.allocate(ADDRESS, p.length);
        for (int i = 0; i < p.length; i++) a.setAtIndex(ADDRESS, i, p[i]);
        return a;
    }

    private MemorySegment ptr(long devPtr) {
        MemorySegment s = arena.allocate(JAVA_LONG);
        s.set(JAVA_LONG, 0, devPtr);
        return s;
    }

    private void up(long dst, double[] src, int n) throws Throwable {
        MemorySegment.copy(src, 0, host, JAVA_DOUBLE, 0, n);
        ok((int) htod.invoke(dst, host, 8L * n), "cuMemcpyHtoD");
    }

    /**
     * Один проход ядра: mode 0 — ускорения W = Q·N (o0..o2), 1 — отталкивание (o0..o2) и
     * приталкивание (o3..o5), 2 — энергия пар (U). tmin — по телу наименьшее r³/(kQ·…) для dynTime.
     */
    synchronized void compute(Forces f, int mode, double a1, double a2, double b,
                              double[] o0, double[] o1, double[] o2, double[] o3, double[] o4, double[] o5,
                              double[] tmin, double[] U) throws Throwable {
        int n = f.n;
        if (n == 0) return;
        ok((int) setCtx.invoke(ctx), "cuCtxSetCurrent"); // вызов может прийти из другого потока
        ensure(n);
        upProps(f);
        // координаты: x, y, z подряд по n — одна загрузка
        MemorySegment.copy(f.px, 0, host, JAVA_DOUBLE, 0, n);
        MemorySegment.copy(f.py, 0, host, JAVA_DOUBLE, 8L * n, n);
        MemorySegment.copy(f.pz, 0, host, JAVA_DOUBLE, 16L * n, n);
        ok((int) htod.invoke(dPos, host, 24L * n), "cuMemcpyHtoD");
        for (int c = 0; c < 3; c++) posArg[c].set(JAVA_LONG, 0, dPos + 8L * c * n);
        // выходы: нужные подряд по n — одна выгрузка. mode 0: o0..o2, tmin; 1: o0..o5, tmin; 2: U
        int used = mode == 0 ? 4 : mode == 1 ? 7 : 1;
        int[] slot = mode == 0 ? new int[]{0, 1, 2, -1, -1, -1, 3, -1} : mode == 1 ? new int[]{0, 1, 2, 3, 4, 5, 6, -1} : new int[]{-1, -1, -1, -1, -1, -1, -1, 0};
        for (int c = 0; c < OUT; c++) outArg[c].set(JAVA_LONG, 0, dOutAll + 8L * Math.max(0, slot[c]) * n);
        aN.set(JAVA_INT, 0, n);
        aMode.set(JAVA_INT, 0, mode);
        aA1.set(JAVA_DOUBLE, 0, a1);
        aA2.set(JAVA_DOUBLE, 0, a2);
        aB.set(JAVA_DOUBLE, 0, b);
        ok((int) launch.invoke(fn, (n + BLOCK - 1) / BLOCK, 1, 1, BLOCK, 1, 1, 0, MemorySegment.NULL, args, MemorySegment.NULL), "cuLaunchKernel");
        ok((int) sync.invoke(), "cuCtxSynchronize");
        ok((int) dtoh.invoke(host, dOutAll, 8L * used * n), "cuMemcpyDtoH");
        double[][] dst = {o0, o1, o2, o3, o4, o5, tmin, U};
        for (int c = 0; c < OUT; c++) {
            if (slot[c] >= 0) MemorySegment.copy(host, JAVA_DOUBLE, 8L * slot[c] * n, dst[c], 0, n);
        }
    }

    private void upProps(Forces f) throws Throwable {
        if (uploaded == f.propsVersion) return;
        double[][] props = {f.N, f.rad, f.sg, f.fIn, f.chi, f.rs, f.m};
        for (int k = 0; k < PROPS; k++) up(dProp[k], props[k], f.n);
        upPartner(f);
        uploaded = f.propsVersion;
    }

    private void upPropsF(Forces f) throws Throwable {
        if (uploadedF == f.propsVersion) return;
        double[][] props = {f.N, f.rad, f.sg};
        for (int k = 0; k < PROPS_F; k++) {
            toF(props[k], f.n, 0, 0);
            ok((int) htod.invoke(dPropF[k], hostF, 4L * f.n), "cuMemcpyHtoD");
        }
        upPartner(f);
        uploadedF = f.propsVersion;
    }

    private void upPartner(Forces f) throws Throwable {
        MemorySegment.copy(f.partner, 0, hostInt, JAVA_INT, 0, f.n);
        ok((int) htod.invoke(dPartner, hostInt, 4L * f.n), "cuMemcpyHtoD");
    }

    /** float-копия массива со сдвигом shift (координаты — от средней точки), в hostF с позиции at. */
    private void toF(double[] src, int n, double shift, long at) {
        for (int i = 0; i < n; i++) hostF.setAtIndex(JAVA_FLOAT, at + i, (float) (src[i] - shift));
    }

    /**
     * Приближённый проход во float: mode 0 — ускорения (o0..o2), 1 — отталкивание и
     * приталкивание (o0..o5). Ядро даёт суммы без kQ; хост умножает их на f(α)·k_v·kQ·N_i/m_i
     * в double, tmin = 1/(kQ·best).
     */
    synchronized void computeFloat(Forces f, int mode, double kq, double fa, double b,
                                   double[] o0, double[] o1, double[] o2, double[] o3, double[] o4, double[] o5,
                                   double[] tmin) throws Throwable {
        int n = f.n;
        if (n == 0) return;
        ok((int) setCtx.invoke(ctx), "cuCtxSetCurrent");
        ensure(n);
        upPropsF(f);
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            cx += f.px[i];
            cy += f.py[i];
            cz += f.pz[i];
        }
        toF(f.px, n, cx / n, 0);
        toF(f.py, n, cy / n, n);
        toF(f.pz, n, cz / n, 2L * n);
        ok((int) htod.invoke(dPosF, hostF, 12L * n), "cuMemcpyHtoD");
        for (int c = 0; c < 3; c++) posArgF[c].set(JAVA_LONG, 0, dPosF + 4L * c * n);
        int used = mode == 0 ? 4 : 7; // выходы подряд: o0..o2 (o0..o5), затем best
        for (int c = 0; c < 6; c++) outArgF[c].set(JAVA_LONG, 0, dOutF + 4L * (mode == 0 ? Math.min(c, 2) : c) * n);
        outArgF[6].set(JAVA_LONG, 0, dOutF + 4L * (used - 1) * n);
        aN.set(JAVA_INT, 0, n);
        aMode.set(JAVA_INT, 0, mode);
        aBF.set(JAVA_FLOAT, 0, (float) b);
        ok((int) launch.invoke(fnF, (n + TX - 1) / TX, 1, 1, TX, TY, 1, 0, MemorySegment.NULL, argsF, MemorySegment.NULL), "cuLaunchKernel");
        ok((int) sync.invoke(), "cuCtxSynchronize");
        ok((int) dtoh.invoke(hostF, dOutF, 4L * used * n), "cuMemcpyDtoH");
        double[][] dst = mode == 0 ? new double[][]{o0, o1, o2} : new double[][]{o0, o1, o2, o3, o4, o5};
        for (int i = 0; i < n; i++) {
            double k = fa * f.N[i] * kq / f.m[i];
            for (int c = 0; c < dst.length; c++) dst[c][i] = k * hostF.getAtIndex(JAVA_FLOAT, (long) c * n + i);
            double t = 1 / (kq * hostF.getAtIndex(JAVA_FLOAT, (long) (used - 1) * n + i));
            int p = f.partner[i];
            if (p >= 0) { // полюс-партнёр в double — те же формулы, что в Forces.directRange / splitRange
                double rx = f.px[p] - f.px[i], ry = f.py[p] - f.py[i], rz = f.pz[p] - f.pz[i];
                double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double R = Math.max(f.rad[i], f.rad[p]), rt = Math.max(R, r), fi = f.fIn[i], c = f.chi[i];
                t = Math.min(t, rt * rt * rt / (kq * fi * (1 + c) * (f.N[i] + f.N[p])));
                if (r > 0) {
                    double M = Math.max(r, R), s = kq * f.N[p] / (M * M * M);
                    double Ms = Math.max(r, f.rs[i]), ss = kq * f.N[p] / (Ms * Ms * Ms);
                    double kk = fa * f.N[i] * fi / f.m[i];
                    if (mode == 0) {
                        double q = kk * (c * ss - s);
                        o0[i] += q * rx;
                        o1[i] += q * ry;
                        o2[i] += q * rz;
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
            tmin[i] = t;
        }
    }

    private void ensureBh(int nNodes) throws Throwable {
        if (nNodes <= capBh) return;
        if (capBh > 0) {
            memFree.invoke(dBhF);
            memFree.invoke(dBhI);
        }
        capBh = Math.max(nNodes, Math.max(16, capBh * 2));
        MemorySegment d = arena.allocate(JAVA_LONG);
        // 6 float-массивов подряд: cx,cy,cz,q,w,mass
        ok((int) memAlloc.invoke(d, 4L * 6 * capBh), "cuMemAlloc");
        dBhF = d.get(JAVA_LONG, 0);
        dBhCx = dBhF;
        dBhCy = dBhF + 4L * capBh;
        dBhCz = dBhF + 8L * capBh;
        dBhQ = dBhF + 12L * capBh;
        dBhW = dBhF + 16L * capBh;
        dBhMass = dBhF + 20L * capBh;
        // child[8·cap] | body[cap]
        ok((int) memAlloc.invoke(d, 4L * 9 * capBh), "cuMemAlloc");
        dBhI = d.get(JAVA_LONG, 0);
        dBhChild = dBhI;
        dBhBody = dBhI + 4L * 8 * capBh;
        hostBhF = arena.allocate(JAVA_FLOAT, (long) 6 * capBh);
        hostBhI = arena.allocate(JAVA_INT, (long) 9 * capBh);
        // n, nNodes, mode, theta2, b, px..pz, N,rad,sg, partner, ncx..nw, child, body, o0..o5, best
        argsBh = arena.allocate(ADDRESS, 5 + 3 + 3 + 1 + 5 + 2 + 7);
        int k = 0;
        argsBh.setAtIndex(ADDRESS, k++, aN);
        argsBh.setAtIndex(ADDRESS, k++, aNNodes);
        argsBh.setAtIndex(ADDRESS, k++, aMode);
        argsBh.setAtIndex(ADDRESS, k++, aTheta2);
        argsBh.setAtIndex(ADDRESS, k++, aBF);
        for (int c = 0; c < 3; c++) argsBh.setAtIndex(ADDRESS, k++, posArgF[c]);
        for (long p : dPropF) argsBh.setAtIndex(ADDRESS, k++, ptr(p));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dPartner));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhCx));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhCy));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhCz));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhQ));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhW));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhChild));
        argsBh.setAtIndex(ADDRESS, k++, ptr(dBhBody));
        for (int c = 0; c < 6; c++) argsBh.setAtIndex(ADDRESS, k++, outArgF[c]);
        argsBh.setAtIndex(ADDRESS, k, outArgF[6]);
        // refresh: nNodes, px..pz, N, sg, ncx..nq, mass, child, body
        argsBhRef = arena.allocate(ADDRESS, 1 + 3 + 2 + 4 + 1 + 2);
        k = 0;
        argsBhRef.setAtIndex(ADDRESS, k++, aNNodes);
        for (int c = 0; c < 3; c++) argsBhRef.setAtIndex(ADDRESS, k++, posArgF[c]);
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dPropF[0])); // N
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dPropF[2])); // sg
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhCx));
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhCy));
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhCz));
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhQ));
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhMass));
        argsBhRef.setAtIndex(ADDRESS, k++, ptr(dBhChild));
        argsBhRef.setAtIndex(ADDRESS, k, ptr(dBhBody));
    }

    /**
     * Barnes–Hut во float: дерево с хоста, обход на GPU. Масштаб kQ·fa·N/m и полюса — на хосте
     * (как {@link #computeFloat}). Не трогает ядро uif_f / resident.
     * {@code uploadTree} — false, если топология уже на карте (только позиции).
     */
    synchronized void computeFloatBh(Forces f, BarnesHut.Tree tree, boolean uploadTree, boolean refreshCom,
                                     int mode, double kq, double fa, double b,
                                     double[] o0, double[] o1, double[] o2, double[] o3, double[] o4, double[] o5,
                                     double[] tmin) throws Throwable {
        int n = f.n, nn = tree.nNodes;
        if (n == 0) return;
        ok((int) setCtx.invoke(ctx), "cuCtxSetCurrent");
        ensure(n);
        int prevCap = capBh;
        ensureBh(Math.max(1, nn));
        if (capBh != prevCap) uploadTree = true; // после realloc буферы пустые
        upPropsF(f);
        double cx, cy, cz;
        if (uploadTree) {
            cx = cy = cz = 0;
            for (int i = 0; i < n; i++) {
                cx += f.px[i];
                cy += f.py[i];
                cz += f.pz[i];
            }
            cx /= n;
            cy /= n;
            cz /= n;
            bhOx = cx;
            bhOy = cy;
            bhOz = cz;
        } else {
            // та же точка, что при upload дерева — иначе мультиполи съедут относительно тел
            cx = bhOx;
            cy = bhOy;
            cz = bhOz;
        }
        toF(f.px, n, cx, 0);
        toF(f.py, n, cy, n);
        toF(f.pz, n, cz, 2L * n);
        ok((int) htod.invoke(dPosF, hostF, 12L * n), "cuMemcpyHtoD");
        for (int c = 0; c < 3; c++) posArgF[c].set(JAVA_LONG, 0, dPosF + 4L * c * n);
        aNNodes.set(JAVA_INT, 0, nn);
        if (uploadTree) {
            // host layout = device layout (stride capBh) → один float HtoD + 2 int
            float ox = (float) cx, oy = (float) cy, oz = (float) cz;
            int cap = capBh;
            for (int i = 0; i < nn; i++) {
                hostBhF.setAtIndex(JAVA_FLOAT, i, tree.cx[i] - ox);
                hostBhF.setAtIndex(JAVA_FLOAT, (long) cap + i, tree.cy[i] - oy);
                hostBhF.setAtIndex(JAVA_FLOAT, 2L * cap + i, tree.cz[i] - oz);
                hostBhF.setAtIndex(JAVA_FLOAT, 3L * cap + i, tree.q[i]);
                hostBhF.setAtIndex(JAVA_FLOAT, 4L * cap + i, tree.w[i]);
                hostBhF.setAtIndex(JAVA_FLOAT, 5L * cap + i, tree.mass[i]);
            }
            ok((int) htod.invoke(dBhF, hostBhF, 4L * 6 * cap), "cuMemcpyHtoD");
            MemorySegment.copy(tree.child, 0, hostBhI, JAVA_INT, 0, 8 * nn);
            ok((int) htod.invoke(dBhChild, hostBhI, 4L * 8 * nn), "cuMemcpyHtoD");
            MemorySegment.copy(tree.body, 0, hostBhI, JAVA_INT, 0, nn);
            ok((int) htod.invoke(dBhBody, hostBhI, 4L * nn), "cuMemcpyHtoD");
        } else if (refreshCom && nn > 0) {
            // редкий refresh COM (не каждый шаг — serial kernel иначе тормозит)
            for (int c = 0; c < 3; c++) argsBhRef.setAtIndex(ADDRESS, 1 + c, posArgF[c]);
            ok((int) launch.invoke(fnBhRef, 1, 1, 1, 1, 1, 1, 0, MemorySegment.NULL, argsBhRef, MemorySegment.NULL), "cuLaunchKernel");
        }
        int used = mode == 0 ? 4 : 7;
        for (int c = 0; c < 6; c++) outArgF[c].set(JAVA_LONG, 0, dOutF + 4L * (mode == 0 ? Math.min(c, 2) : c) * n);
        outArgF[6].set(JAVA_LONG, 0, dOutF + 4L * (used - 1) * n);
        for (int c = 0; c < 6; c++) argsBh.setAtIndex(ADDRESS, 5 + 3 + 3 + 1 + 5 + 2 + c, outArgF[c]);
        argsBh.setAtIndex(ADDRESS, 5 + 3 + 3 + 1 + 5 + 2 + 6, outArgF[6]);
        for (int c = 0; c < 3; c++) argsBh.setAtIndex(ADDRESS, 5 + c, posArgF[c]);
        aN.set(JAVA_INT, 0, n);
        aMode.set(JAVA_INT, 0, mode);
        aTheta2.set(JAVA_FLOAT, 0, BarnesHut.THETA * BarnesHut.THETA);
        aBF.set(JAVA_FLOAT, 0, (float) b);
        ok((int) launch.invoke(fnBh, (n + 127) / 128, 1, 1, 128, 1, 1, 0, MemorySegment.NULL, argsBh, MemorySegment.NULL), "cuLaunchKernel");
        ok((int) sync.invoke(), "cuCtxSynchronize");
        ok((int) dtoh.invoke(hostF, dOutF, 4L * used * n), "cuMemcpyDtoH");
        int need = used * n;
        if (bhOutHost.length < need) bhOutHost = new float[Math.max(need, 64)];
        MemorySegment.copy(hostF, JAVA_FLOAT, 0, bhOutHost, 0, need);
        double[][] dst = mode == 0 ? new double[][]{o0, o1, o2} : new double[][]{o0, o1, o2, o3, o4, o5};
        for (int i = 0; i < n; i++) {
            double kMul = fa * f.N[i] * kq / f.m[i];
            for (int c = 0; c < dst.length; c++) dst[c][i] = kMul * bhOutHost[c * n + i];
            double t = 1 / (kq * bhOutHost[(used - 1) * n + i]);
            BarnesHut.addPartner(f, i, mode, kq, fa, o0, o1, o2, o3, o4, o5);
            int p = f.partner[i];
            if (p >= 0) {
                double rx = f.px[p] - f.px[i], ry = f.py[p] - f.py[i], rz = f.pz[p] - f.pz[i];
                double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double R = Math.max(f.rad[i], f.rad[p]), rt = Math.max(R, r), fi = f.fIn[i], chi = f.chi[i];
                t = Math.min(t, rt * rt * rt / (kq * fi * (1 + chi) * (f.N[i] + f.N[p])));
            }
            tmin[i] = t;
        }
    }

    // ---------- весь шаг на видеокарте (GPU float) ----------

    private void launch(MemorySegment kernel, int grid, int bx, int by, MemorySegment a) throws Throwable {
        ok((int) launch.invoke(kernel, grid, 1, 1, bx, by, 1, 0, MemorySegment.NULL, a, MemorySegment.NULL), "cuLaunchKernel");
    }

    private void upBlocks(long dst, double[] x, double[] y, double[] z, int n) throws Throwable {
        MemorySegment.copy(x, 0, host, JAVA_DOUBLE, 0, n);
        MemorySegment.copy(y, 0, host, JAVA_DOUBLE, 8L * n, n);
        MemorySegment.copy(z, 0, host, JAVA_DOUBLE, 16L * n, n);
        ok((int) htod.invoke(dst, host, 24L * n), "cuMemcpyHtoD");
    }

    private void downBlocks(long src, double[] x, double[] y, double[] z, int n, int k) throws Throwable {
        for (int c = 0; c < 3; c++) ok((int) dtoh.invoke(host.asSlice(8L * c * k), src + 8L * c * n, 8L * k), "cuMemcpyDtoH");
        MemorySegment.copy(host, JAVA_DOUBLE, 0, x, 0, k);
        MemorySegment.copy(host, JAVA_DOUBLE, 8L * k, y, 0, k);
        MemorySegment.copy(host, JAVA_DOUBLE, 16L * k, z, 0, k);
    }

    /** Подшаг Йошиды целиком на видеокарте: kick(h/2) · drift(h) · силы во float · kick(h/2). */
    private void substep(double hk, double hd, double hk2) throws Throwable {
        int grid = (rn + 127) / 128;
        sHk.set(JAVA_DOUBLE, 0, hk);
        sHd.set(JAVA_DOUBLE, 0, hd);
        sHk2.set(JAVA_DOUBLE, 0, hk2);
        launch(fnKd, grid, 128, 1, argsKd);
        launch(fnF, (rn + TX - 1) / TX, TX, TY, argsF);
        launch(fnFin, grid, 128, 1, argsFin);
    }

    private double dynMin() throws Throwable {
        launch(fnMin, 1, 256, 1, argsMin);
        ok((int) dtoh.invoke(one, dMin, 8L), "cuMemcpyDtoH");
        double t = one.get(JAVA_DOUBLE, 0);
        if (chamber != null) t = Math.min(t, nodeT);
        return Math.sqrt(t); // √ монотонен: √min = min √
    }

    private void chKd(double hk, double hd) throws Throwable {
        sChHk.set(JAVA_DOUBLE, 0, hk);
        sChHd.set(JAVA_DOUBLE, 0, hd);
        launch(fnChKd, 1, 256, 1, argsChKd);
    }

    /** Вязкий слой за dt: три прохода (тела → v_c' и узлы → тела), как Chamber.damp. */
    private void damp(double dt) throws Throwable {
        int grid = (rn + 127) / 128;
        sDt.set(JAVA_DOUBLE, 0, dt);
        launch(fnD1, grid, 128, 1, argsD1);
        launch(fnD2, 1, 256, 1, argsD2);
        launch(fnD3, grid, 128, 1, argsD3);
    }

    /** Силы в текущих положениях: пары во float, затем стенка (тела, узлы, центр). */
    private void forcesChamber() throws Throwable {
        int grid = (rn + 127) / 128;
        sHk2.set(JAVA_DOUBLE, 0, 0);
        launch(fnF, (rn + TX - 1) / TX, TX, TY, argsF);
        launch(fnFin, grid, 128, 1, argsFin);
        launch(fnWall, grid, 128, 1, argsWall);
        launch(fnNodes, 1, 256, 1, argsNodes);
    }

    /** Подшаг с замкнутой областью — порядок World.substep: kick · damp · drift · damp · силы · kick. */
    private void substepChamber(double h) throws Throwable {
        int grid = (rn + 127) / 128;
        sHk.set(JAVA_DOUBLE, 0, 0.5 * h); // kick тел (drift 0)
        sHd.set(JAVA_DOUBLE, 0, 0);
        launch(fnKd, grid, 128, 1, argsKd);
        chKd(0.5 * h, 0);
        damp(0.5 * h);
        sHk.set(JAVA_DOUBLE, 0, 0); // drift тел (и координаты во float)
        sHd.set(JAVA_DOUBLE, 0, h);
        launch(fnKd, grid, 128, 1, argsKd);
        chKd(0, h);
        damp(0.5 * h);
        forcesChamber();
        sHk.set(JAVA_DOUBLE, 0, 0.5 * h);
        sHd.set(JAVA_DOUBLE, 0, 0);
        launch(fnKd, grid, 128, 1, argsKd);
        chKd(0.5 * h, 0);
    }

    /** Состояние и параметры замкнутой области — на видеокарту. */
    private void upChamber(Chamber ch, double kq) throws Throwable {
        double[] P = {ch.N, ch.delta, kq, ch.R0, ch.stiffness() / Chamber.M, ch.tau * ch.stiffness() / Chamber.M, ch.pExt,
                ch.mass() / Chamber.M, ch.mass(), ch.visc, ch.omega(), ch.hFrac, 2 * ch.zeta * ch.omega(), Chamber.S,
                Chamber.WALL_SIGN * Formula.K_V};
        MemorySegment.copy(P, 0, chHost, JAVA_DOUBLE, 0, P_LEN);
        ok((int) htod.invoke(dP, chHost, 8L * P_LEN), "cuMemcpyHtoD");
        double[] s = new double[CH_LEN];
        System.arraycopy(ch.c, 0, s, 0, 3);
        System.arraycopy(ch.vc, 0, s, 3, 3);
        System.arraycopy(ch.ac, 0, s, 6, 3);
        s[9] = ch.lost;
        s[12] = ch.bodyPush;
        int M = Chamber.M;
        System.arraycopy(ch.Rk, 0, s, 32, M);
        System.arraycopy(ch.Vk, 0, s, 32 + M, M);
        System.arraycopy(ch.aR, 0, s, 32 + 2 * M, M);
        MemorySegment.copy(s, 0, chHost, JAVA_DOUBLE, 0, CH_LEN);
        ok((int) htod.invoke(dCH, chHost, 8L * CH_LEN), "cuMemcpyHtoD");
        double om2 = ch.omega() * ch.omega() * (1 + 2 * Chamber.MAX_DEG * ch.tau); // как Chamber.dynTime
        nodeT = 1 / om2;
    }

    private void downChamber(Chamber ch) throws Throwable {
        ok((int) dtoh.invoke(chHost, dCH, 8L * CH_LEN), "cuMemcpyDtoH");
        double[] s = new double[CH_LEN];
        MemorySegment.copy(chHost, JAVA_DOUBLE, 0, s, 0, CH_LEN);
        int M = Chamber.M;
        System.arraycopy(s, 0, ch.c, 0, 3);
        System.arraycopy(s, 3, ch.vc, 0, 3);
        System.arraycopy(s, 6, ch.ac, 0, 3);
        ch.lost = s[9];
        ch.bodyPush = s[12];
        System.arraycopy(s, 32, ch.Rk, 0, M);
        System.arraycopy(s, 32 + M, ch.Vk, 0, M);
        System.arraycopy(s, 32 + 2 * M, ch.aR, 0, M);
    }

    /**
     * Начать шаги на видеокарте: координаты и скорости тел (f.px…, f.vx…) — на видеокарту,
     * ускорения в начальных положениях. Возвращает dynTime. До resEnd состояние живёт только там.
     */
    synchronized double resBegin(Forces f, double kq, double fa, Chamber ch) throws Throwable {
        int n = f.n;
        ok((int) setCtx.invoke(ctx), "cuCtxSetCurrent");
        ensure(n);
        upProps(f);
        upPropsF(f);
        rn = n;
        upBlocks(dX, f.px, f.py, f.pz, n);
        upBlocks(dV, f.vx, f.vy, f.vz, n);
        double cx = 0, cy = 0, cz = 0; // точка отсчёта для float — средняя точка сцены
        for (int i = 0; i < n; i++) {
            cx += f.px[i];
            cy += f.py[i];
            cz += f.pz[i];
        }
        sR0x.set(JAVA_DOUBLE, 0, cx / n);
        sR0y.set(JAVA_DOUBLE, 0, cy / n);
        sR0z.set(JAVA_DOUBLE, 0, cz / n);
        aN.set(JAVA_INT, 0, n);
        aMode.set(JAVA_INT, 0, 0);
        aBF.set(JAVA_FLOAT, 0, 0f);
        sKq.set(JAVA_DOUBLE, 0, kq);
        sFa.set(JAVA_DOUBLE, 0, fa);
        for (int c = 0; c < 3; c++) posArgF[c].set(JAVA_LONG, 0, dPosF + 4L * c * n);
        for (int c = 0; c < 6; c++) outArgF[c].set(JAVA_LONG, 0, dOutF + 4L * Math.min(c, 2) * n);
        outArgF[6].set(JAVA_LONG, 0, dOutF + 4L * 3 * n);
        chamber = ch;
        if (ch == null) {
            substep(0, 0, 0); // только силы в текущих положениях
        } else {
            upChamber(ch, kq);
            sHk.set(JAVA_DOUBLE, 0, 0);
            sHd.set(JAVA_DOUBLE, 0, 0);
            launch(fnKd, (n + 127) / 128, 128, 1, argsKd); // координаты во float
            chKd(0, 0); // R̄, ΣV_k, min/max R_k
            forcesChamber();
        }
        return dynMin();
    }

    /** Один шаг Йошиды (три подшага); возвращает dynTime в конце шага. */
    synchronized double resStep(double dt, double y1, double y0) throws Throwable {
        for (double h : new double[]{y1 * dt, y0 * dt, y1 * dt}) {
            if (chamber != null) substepChamber(h);
            else substep(0.5 * h, h, 0.5 * h);
        }
        return dynMin();
    }

    /** Координаты первых k тел — в f.px, f.py, f.pz (для следов). */
    synchronized void resPositions(Forces f, int k) throws Throwable {
        downBlocks(dX, f.px, f.py, f.pz, rn, k);
    }

    /** Закончить: координаты и скорости — обратно в f.px…, f.vx…. */
    synchronized void resEnd(Forces f) throws Throwable {
        downBlocks(dX, f.px, f.py, f.pz, rn, rn);
        downBlocks(dV, f.vx, f.vy, f.vz, rn, rn);
        if (chamber != null) downChamber(chamber);
    }

    private void free() {
        try {
            if (cap > 0) {
                memFree.invoke(dPos);
                for (long p : dProp) memFree.invoke(p);
                memFree.invoke(dOutAll);
                memFree.invoke(dPartner);
                memFree.invoke(dPosF);
                for (long p : dPropF) memFree.invoke(p);
                memFree.invoke(dOutF);
                for (long p : new long[]{dX, dV, dA, dT, dMin, dD}) memFree.invoke(p);
            }
            if (capBh > 0) {
                memFree.invoke(dBhF);
                memFree.invoke(dBhI);
            }
        } catch (Throwable ignored) {
            // освобождение при закрытии — ошибки не важны
        }
        cap = 0;
        capBh = 0;
    }

    void close() {
        free();
    }
}
