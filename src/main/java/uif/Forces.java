package uif;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Перебор всех пар тел — самая тяжёлая часть расчёта (n² на каждый расчёт сил). Координаты и
 * свойства тел перед расчётом складываются в плотные массивы (так процессор не ищет каждое тело
 * по памяти), а дальше считает один из путей: один поток CPU, все ядра CPU или видеокарта (CUDA,
 * {@link Gpu}). Формулы везде одни и те же, записаны в одном порядке операций (fma — только явный, Math.fma ↔ fma) — поэтому
 * все пути дают один и тот же результат до бита (проверяется тестом).
 * <p>
 * Через W = Q·N: QN(r, R)/r = kQ·N_j / max(r, R)³ — то же, что N(r) = N·(r/R)³ внутри шара.
 * Пара — sᵢsⱼ·QN; полюса одного диполя — f(α)·(χ·QN(r, R_s) − QN(r, R)) (см. World.pairQN).
 */
public final class Forces {
    /**
     * На чём считать: AUTO — по числу тел (1 поток, все ядра, видеокарта в double), CPU — только
     * процессор, GPU — видеокарта в double, GPU_FLOAT — видеокарта во float (точные пары, ~7 знаков),
     * GPU_FLOAT_BH — Barnes–Hut O(N log N) во float (грубее, быстрее на больших N).
     * Энергия для ΔE/E всегда в double. AUTO float/BH не выбирает.
     */
    public enum Device {
        AUTO("Авто"), CPU("CPU"), GPU("GPU double"), GPU_FLOAT("GPU float"),
        GPU_FLOAT_BH("GPU float Barnes–Hut");

        public final String label;

        Device(String label) {
            this.label = label;
        }
    }

    /** С какого числа тел потоки быстрее одного (замер на i5-13400F: 30 тел ×1.06, 100 тел ×3.8). */
    public static final int PAR_MIN = 64;
    /** С какого числа тел в режиме AUTO считает видеокарта (замер: 1000 тел — GPU 876 мкс, 16 потоков 943 мкс; запуск GPU ~40 мкс). */
    public static final int GPU_MIN = 1000;

    /** Старт и «перезагрузить»: CPU (точный путь на процессоре; видеокарту можно выбрать в «Расчёте»). */
    public Device device = Device.CPU;
    /** Порог потоков (тесты ставят Integer.MAX_VALUE, чтобы сравнить с одним потоком). */
    int parMin = PAR_MIN;
    /** Каким путём посчитан последний расчёт: «1 поток», «CPU ×15», «GPU» — для сводки. */
    public String lastPath = "—";

    private final World w;
    private Gpu gpu;
    private boolean gpuTried;
    private String gpuStatus = "не проверялась";

    // плотные массивы: координаты — каждый расчёт, свойства — когда изменился состав
    int n;
    double[] px = new double[0], py = px, pz = px, N = px, rad = px, sg = px, fIn = px, chi = px, rs = px, m = px;
    /** Скорости — только для шага целиком на видеокарте. */
    double[] vx = px, vy = px, vz = px;
    int[] partner = new int[0];
    private Body[] packed = new Body[0];
    private boolean propsDirty = true;
    /** Растёт при каждой перекладке свойств — видеокарта по нему решает, пора ли их загрузить заново. */
    long propsVersion;
    // выходы
    private double[] o0 = new double[0], o1 = o0, o2 = o0, o3 = o0, o4 = o0, o5 = o0, tmin = o0, U = o0;

    // ---------- только GPU_FLOAT_BH: кэш дерева (не трогает GPU float / CPU) ----------
    /** Топологию дерева — редко; между rebuild GPU иногда обновляет COM. */
    static final int BH_REBUILD_STEPS = 48;
    /** Доля rootHalf: сильный сдвиг → rebuild топологии. */
    static final double BH_DIRTY_FRAC = 0.35;
    /** Шаг выборки в dirty-проверке (полный скан слишком дорог). */
    static final int BH_DIRTY_STRIDE = 16;
    /** Как часто между topology-rebuild запускать GPU COM-refresh (1 = каждый шаг — дорого). */
    static final int BH_REFRESH_EVERY = 4;
    private final BarnesHut.Scratch bhScratch = new BarnesHut.Scratch();
    private BarnesHut.Tree bhTree;
    private long bhPropsAt = -1;
    private int bhNAt = -1;
    private int bhAge;
    private double[] bhSnapX = new double[0], bhSnapY = bhSnapX, bhSnapZ = bhSnapX;

    Forces(World w) {
        this.w = w;
    }

    /** Сложить тела в массивы: координаты всегда, свойства — если тела не те же, что в прошлый раз. */
    void pack() {
        List<Body> parts = w.parts;
        int k = parts.size();
        if (k != n || packed.length < k) {
            int cap = Math.max(k, 16);
            if (px.length < k) {
                cap = Math.max(k, px.length * 3 / 2);
                px = new double[cap];
                py = new double[cap];
                pz = new double[cap];
                N = new double[cap];
                rad = new double[cap];
                sg = new double[cap];
                fIn = new double[cap];
                chi = new double[cap];
                rs = new double[cap];
                m = new double[cap];
                vx = new double[cap];
                vy = new double[cap];
                vz = new double[cap];
                partner = new int[cap];
                o0 = new double[cap];
                o1 = new double[cap];
                o2 = new double[cap];
                o3 = new double[cap];
                o4 = new double[cap];
                o5 = new double[cap];
                tmin = new double[cap];
                U = new double[cap];
                packed = new Body[cap];
            }
            n = k;
            propsDirty = true;
        }
        for (int i = 0; i < k; i++) {
            Body b = parts.get(i);
            px[i] = b.p[0];
            py[i] = b.p[1];
            pz[i] = b.p[2];
            if (packed[i] != b) propsDirty = true;
        }
        if (!propsDirty) {
            // партнёр мог смениться у тех же тел (диполь собран заново) — проверяем по ссылкам
            for (int i = 0; i < k; i++) {
                Body pa = packed[i].partner;
                if ((pa == null) != (partner[i] < 0) || (pa != null && (partner[i] >= k || packed[partner[i]] != pa))) {
                    propsDirty = true;
                    break;
                }
            }
        }
        if (propsDirty) {
            java.util.IdentityHashMap<Body, Integer> idx = new java.util.IdentityHashMap<>();
            for (int i = 0; i < k; i++) idx.put(parts.get(i), i);
            for (int i = 0; i < k; i++) {
                Body b = parts.get(i);
                packed[i] = b;
                N[i] = b.N;
                rad[i] = b.rad;
                sg[i] = b.sign;
                m[i] = b.m;
                Integer pj = b.partner == null ? null : idx.get(b.partner);
                partner[i] = pj == null ? -1 : pj;
                fIn[i] = b.fIn;
                chi[i] = b.chi;
                rs[i] = b.rs;
            }
            propsDirty = false;
            propsVersion++;
        }
        // удерживаемое мышью тело — призрак: не тянет и не получает силу (N = 0 в расчёте пар)
        if (w.held != null) {
            for (int i = 0; i < k; i++) {
                if (parts.get(i) == w.held) {
                    N[i] = 0;
                    break;
                }
            }
        }
    }

    /** Свойства (N/rad/…) переложить при следующей упаковке — смена held или состава. */
    void markPropsDirty() {
        propsDirty = true;
    }

    // ---------- выбор пути ----------

    private enum Path { SERIAL, PARALLEL, GPU, GPU_FLOAT, GPU_FLOAT_BH }

    /** exact — нужен точный путь (энергия): float/BH тогда считают в double на видеокарте/CPU. */
    private Path path(boolean exact) {
        if (device == Device.GPU_FLOAT_BH && !exact && n >= 2) return Path.GPU_FLOAT_BH;
        if (device == Device.GPU_FLOAT && !exact && n >= 2 && gpu() != null) return Path.GPU_FLOAT;
        // энергия (exact) и GPU double / AUTO — прежние точные пути; BH сюда не попадает
        if (device != Device.CPU && n >= (device == Device.AUTO ? GPU_MIN : 2) && gpu() != null) return Path.GPU;
        return n >= parMin ? Path.PARALLEL : Path.SERIAL;
    }

    /** Видеокарта: открывается при первом обращении; если CUDA нет — null, считает CPU. */
    Gpu gpu() {
        if (!gpuTried) {
            gpuTried = true;
            try {
                gpu = Gpu.open();
                gpuStatus = gpu.name();
            } catch (Throwable e) {
                gpu = null;
                gpuStatus = "нет: " + e.getMessage();
            }
        }
        return gpu;
    }

    /** Название видеокарты или почему её нет. */
    public String gpuStatus() {
        gpu();
        return gpuStatus;
    }

    /** Пробовали ли уже открыть видеокарту (чтобы панель не открывала её ради надписи). */
    public boolean gpuChecked() {
        return gpuTried;
    }

    public boolean gpuAvailable() {
        return gpu() != null;
    }

    private void gpuFailed(Throwable e) {
        gpuStatus = "отключена после ошибки: " + e.getMessage();
        if (gpu != null) gpu.close();
        gpu = null;
    }

    private void run(Path p, Kernel k) {
        if (p == Path.SERIAL) {
            k.range(0, n);
            lastPath = "1 поток";
            return;
        }
        int chunks = Math.min(n, 4 * java.util.concurrent.ForkJoinPool.getCommonPoolParallelism());
        IntStream.range(0, chunks).parallel().forEach(c -> k.range((int) ((long) n * c / chunks), (int) ((long) n * (c + 1) / chunks)));
        lastPath = "CPU ×" + java.util.concurrent.ForkJoinPool.getCommonPoolParallelism();
    }

    private interface Kernel {
        void range(int i0, int i1);
    }

    private double minDyn() {
        double t = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) if (tmin[i] < t) t = tmin[i];
        return Math.sqrt(t); // √ монотонен: √min = min √ до бита
    }

    // ---------- расчёты ----------

    /** Ускорения прямого тяготения W = Q·N в a; возвращает dynTime самой тесной пары. */
    double direct(double[][] a) {
        pack();
        double kq = w.kQ(), fa = Formula.F_ALPHA * Formula.K_V;
        Path p = path(false);
        boolean done = false;
        if (p == Path.GPU_FLOAT_BH) {
            done = runBh(0, kq, fa, 0);
            if (!done) p = n >= parMin ? Path.PARALLEL : Path.SERIAL;
        } else if (p == Path.GPU_FLOAT || p == Path.GPU) {
            try {
                if (p == Path.GPU_FLOAT) gpu.computeFloat(this, 0, kq, fa, 0, o0, o1, o2, null, null, null, tmin);
                else gpu.compute(this, 0, kq, fa, 0, o0, o1, o2, null, null, null, tmin, null);
                lastPath = p == Path.GPU_FLOAT ? "GPU float" : "GPU";
                done = true;
            } catch (Throwable e) {
                gpuFailed(e);
                p = n >= parMin ? Path.PARALLEL : Path.SERIAL;
            }
        }
        if (!done) run(p, (i0, i1) -> directRange(i0, i1, kq, fa));
        for (int i = 0; i < n; i++) {
            a[i][0] = o0[i];
            a[i][1] = o1[i];
            a[i][2] = o2[i];
        }
        return minDyn();
    }

    /**
     * Комплексное тяготение: rep — частное отталкивание −β·W, push — приталкивание от КУО (1+β)·W;
     * у полюсов диполя — их отталкивание и прижатие. Возвращает dynTime.
     */
    double split(double beta, double[][] rep, double[][] push) {
        pack();
        double kq = w.kQ(), fa = Formula.F_ALPHA * Formula.K_V;
        Path p = path(false);
        boolean done = false;
        if (p == Path.GPU_FLOAT_BH) {
            done = runBh(1, kq, fa, beta);
            if (!done) p = n >= parMin ? Path.PARALLEL : Path.SERIAL;
        } else if (p == Path.GPU_FLOAT || p == Path.GPU) {
            try {
                if (p == Path.GPU_FLOAT) gpu.computeFloat(this, 1, kq, fa, beta, o0, o1, o2, o3, o4, o5, tmin);
                else gpu.compute(this, 1, kq, fa, beta, o0, o1, o2, o3, o4, o5, tmin, null);
                lastPath = p == Path.GPU_FLOAT ? "GPU float" : "GPU";
                done = true;
            } catch (Throwable e) {
                gpuFailed(e);
                p = n >= parMin ? Path.PARALLEL : Path.SERIAL;
            }
        }
        if (!done) run(p, (i0, i1) -> splitRange(i0, i1, kq, fa, beta));
        for (int i = 0; i < n; i++) {
            rep[i][0] = o0[i];
            rep[i][1] = o1[i];
            rep[i][2] = o2[i];
            push[i][0] = o3[i];
            push[i][1] = o4[i];
            push[i][2] = o5[i];
        }
        return minDyn();
    }

    /**
     * Barnes–Hut: дерево на хосте; обход во float на GPU при наличии, иначе CPU.
     * Rebuild раз в {@link #BH_REBUILD_STEPS} или по dirty-порогу; между ними — только позиции на GPU.
     * Существующий {@link Device#GPU_FLOAT} не затрагивается. Возвращает false → точный CPU.
     */
    private boolean runBh(int mode, double kq, double fa, double beta) {
        boolean rebuild = bhTree == null || n != bhNAt || propsVersion != bhPropsAt
                || bhAge >= BH_REBUILD_STEPS || bhDirty();
        if (rebuild) {
            bhTree = BarnesHut.build(this, bhScratch);
            bhNAt = n;
            bhPropsAt = propsVersion;
            bhAge = 0;
            if (bhSnapX.length < n) {
                int cap = Math.max(n, 16);
                bhSnapX = new double[cap];
                bhSnapY = new double[cap];
                bhSnapZ = new double[cap];
            }
            System.arraycopy(px, 0, bhSnapX, 0, n);
            System.arraycopy(py, 0, bhSnapY, 0, n);
            System.arraycopy(pz, 0, bhSnapZ, 0, n);
        } else {
            bhAge++;
        }
        BarnesHut.Tree tree = bhTree;
        // refresh только периодически: полный serial bottom-up на GPU дороже host rebuild
        boolean refresh = !rebuild && bhAge > 0 && (bhAge % BH_REFRESH_EVERY == 0);
        if (gpu() != null) {
            try {
                gpu.computeFloatBh(this, tree, rebuild, refresh, mode, kq, fa, beta, o0, o1, o2, o3, o4, o5, tmin);
                lastPath = "GPU float Barnes–Hut";
                return true;
            } catch (Throwable e) {
                // BH на GPU сбой — CPU BH; карту для GPU float не отключаем
            }
        }
        BarnesHut.evaluate(this, tree, mode, kq, fa, beta, o0, o1, o2, o3, o4, o5, tmin);
        lastPath = "Barnes–Hut";
        return true;
    }

    /** Для бенча/отладки BH-кэша. */
    double bhRootHalf() {
        return bhTree == null ? 1 : bhTree.rootHalf;
    }

    /**
     * Выборочная dirty-проверка: каждый {@link #BH_DIRTY_STRIDE}-й + первые/последние тела.
     * Полный O(n) скан на каждом шаге сам съедал выигрыш от кэша.
     */
    private boolean bhDirty() {
        if (bhTree == null || n == 0) return true;
        double lim = BH_DIRTY_FRAC * bhTree.rootHalf;
        double lim2 = lim * lim;
        int stride = n <= 64 ? 1 : BH_DIRTY_STRIDE;
        for (int i = 0; i < n; i += stride) {
            double dx = px[i] - bhSnapX[i], dy = py[i] - bhSnapY[i], dz = pz[i] - bhSnapZ[i];
            if (dx * dx + dy * dy + dz * dz > lim2) return true;
        }
        if (stride > 1) {
            int last = n - 1;
            double dx = px[last] - bhSnapX[last], dy = py[last] - bhSnapY[last], dz = pz[last] - bhSnapZ[last];
            if (dx * dx + dy * dy + dz * dz > lim2) return true;
        }
        return false;
    }

    /** Потенциальная энергия всех пар: Σ_i U_i, U_i = Σ_{j>i} f(α)·k_v·kQ·N_i·N_j·pairPhi (со знаком +; E −= U). */
    double pairEnergy() {
        pack();
        double coef = Formula.F_ALPHA * Formula.K_V * w.kQ();
        Path p = path(true);
        boolean done = false;
        if (p == Path.GPU) {
            try {
                gpu.compute(this, 2, coef, 0, 0, null, null, null, null, null, null, null, U);
                done = true;
            } catch (Throwable e) {
                gpuFailed(e);
                p = n >= parMin ? Path.PARALLEL : Path.SERIAL;
            }
        }
        if (!done) run(p, (i0, i1) -> energyRange(i0, i1, coef));
        double s = 0;
        for (int i = 0; i < n; i++) s += U[i]; // в одном порядке на любом пути
        return s;
    }

    /** Потенциальная энергия только пар полюсов диполей (так же, как в energyRange), O(n). */
    double partnerEnergy() {
        pack();
        double coef = Formula.F_ALPHA * Formula.K_V * w.kQ(), s = 0;
        for (int i = 0; i < n; i++) {
            int j = partner[i];
            if (j <= i) continue;
            double rx = px[j] - px[i], ry = py[j] - py[i], rz = pz[j] - pz[i];
            double r = Math.sqrt(Math.fma(rx, rx, Math.fma(ry, ry, rz * rz)));
            double R = Math.max(rad[i], rad[j]), rsA = rs[i];
            double phi = r >= R ? 1 / r : (3 * R * R - r * r) / (2 * R * R * R);
            double ps = r >= rsA ? 1 / r : (3 * rsA * rsA - r * r) / (2 * rsA * rsA * rsA);
            s += coef * N[i] * N[j] * (fIn[i] * (chi[i] * ps - phi));
        }
        return s;
    }

    /** Только dynTime (для World.dynTime): тот же проход, что у direct. */
    double dyn() {
        double[][] a = new double[w.parts.size()][3];
        return direct(a);
    }

    // ---------- весь шаг на видеокарте (GPU float) ----------

    /** Тесты выключают, чтобы сравнить с шагом на процессоре и силами на видеокарте. */
    boolean residentOn = true;

    /** lastPath, когда шаг целиком прошёл на видеокарте. */
    public static final String RESIDENT = "GPU float, весь шаг";

    /**
     * Считать шаг целиком на видеокарте: «GPU float» и видеокарта есть (с замкнутой областью тоже).
     * КУО с несвязанными шкалами (итог g·W, g ≠ 1) видеокарта целиком не считает — шаг по силам.
     */
    boolean resident() {
        return device == Device.GPU_FLOAT && residentOn && w.held == null && w.stabilize == null
                && !w.tidalEquator
                && w.parts.size() >= 2
                && (w.mode != World.Mode.KUO || w.kuo.gain() == 1) && gpu() != null;
    }

    /** Тела — на видеокарту, силы в начале; возвращает dynTime. */
    double resBegin() throws Throwable {
        pack();
        List<Body> parts = w.parts;
        for (int i = 0; i < n; i++) {
            double[] v = parts.get(i).v;
            vx[i] = v[0];
            vy[i] = v[1];
            vz[i] = v[2];
        }
        return gpu.resBegin(this, w.kQ(), Formula.F_ALPHA * Formula.K_V, w.chamber.on ? w.chamber : null);
    }

    double resStep(double dt, double y1, double y0) throws Throwable {
        return gpu.resStep(dt, y1, y0);
    }

    /** Точка следа с видеокарты — отбор через {@link World#sampleTrails}. */
    void resTrail(int k) throws Throwable {
        gpu.resPositions(this, k);
        w.sampleTrails(px, py, pz);
    }

    /** Координаты и скорости — обратно в тела. */
    void resEnd() throws Throwable {
        gpu.resEnd(this);
        List<Body> parts = w.parts;
        for (int i = 0; i < n; i++) {
            Body b = parts.get(i);
            b.p[0] = px[i];
            b.p[1] = py[i];
            b.p[2] = pz[i];
            b.v[0] = vx[i];
            b.v[1] = vy[i];
            b.v[2] = vz[i];
        }
    }

    void resFailed(Throwable e) {
        gpuFailed(e);
    }

    // ---------- ядра CPU (то же, что в Gpu.SRC, строка в строку) ----------

    private void directRange(int i0, int i1, double kq, double fa) {
        for (int i = i0; i < i1; i++) {
            double xi = px[i], yi = py[i], zi = pz[i], NA = N[i], rA = rad[i], sA = sg[i], f = fIn[i], c = chi[i], rsA = rs[i];
            int pA = partner[i];
            double Wx = 0, Wy = 0, Wz = 0, bn = Double.POSITIVE_INFINITY, bd = 1;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double rx = px[j] - xi, ry = py[j] - yi, rz = pz[j] - zi;
                double r = Math.sqrt(Math.fma(rx, rx, Math.fma(ry, ry, rz * rz)));
                double R = Math.max(rA, rad[j]), rt = Math.max(R, r);
                double sc = j == pA ? f * (1 + c) : 1;
                double tn = rt * rt * rt, td = kq * sc * (NA + N[j]);
                if (tn * bd < bn * td) { bn = tn; bd = td; }
                if (r == 0) continue;
                double M = Math.max(r, R), s = kq * N[j] / (M * M * M), q;
                if (j == pA) {
                    double Ms = Math.max(r, rsA);
                    q = f * (c * (kq * N[j] / (Ms * Ms * Ms)) - s);
                } else {
                    q = sA * sg[j] * s;
                }
                Wx = Math.fma(q, rx, Wx);
                Wy = Math.fma(q, ry, Wy);
                Wz = Math.fma(q, rz, Wz);
            }
            double F = fa * NA;
            o0[i] = F * Wx / m[i];
            o1[i] = F * Wy / m[i];
            o2[i] = F * Wz / m[i];
            tmin[i] = bn / bd;
        }
    }

    private void splitRange(int i0, int i1, double kq, double fa, double b) {
        for (int i = i0; i < i1; i++) {
            double xi = px[i], yi = py[i], zi = pz[i], NA = N[i], rA = rad[i], sA = sg[i], f = fIn[i], c = chi[i], rsA = rs[i], mA = m[i];
            int pA = partner[i];
            double rx_ = 0, ry_ = 0, rz_ = 0, px_ = 0, py_ = 0, pz_ = 0, bn = Double.POSITIVE_INFINITY, bd = 1;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double rx = px[j] - xi, ry = py[j] - yi, rz = pz[j] - zi;
                double r = Math.sqrt(Math.fma(rx, rx, Math.fma(ry, ry, rz * rz)));
                double R = Math.max(rA, rad[j]), rt = Math.max(R, r);
                double sc = j == pA ? f * (1 + c) : 1;
                double tn = rt * rt * rt, td = kq * sc * (NA + N[j]);
                if (tn * bd < bn * td) { bn = tn; bd = td; }
                if (r == 0) continue;
                double M = Math.max(r, R), s = kq * N[j] / (M * M * M), ar, ap;
                if (j == pA) {
                    double k = fa * NA * f / mA, Ms = Math.max(r, rsA);
                    ar = -k * s;
                    ap = k * c * (kq * N[j] / (Ms * Ms * Ms));
                } else {
                    double aW = sA * sg[j] * fa * NA * s / mA;
                    ar = -b * aW;
                    ap = (1 + b) * aW;
                }
                rx_ = Math.fma(ar, rx, rx_);
                ry_ = Math.fma(ar, ry, ry_);
                rz_ = Math.fma(ar, rz, rz_);
                px_ = Math.fma(ap, rx, px_);
                py_ = Math.fma(ap, ry, py_);
                pz_ = Math.fma(ap, rz, pz_);
            }
            o0[i] = rx_;
            o1[i] = ry_;
            o2[i] = rz_;
            o3[i] = px_;
            o4[i] = py_;
            o5[i] = pz_;
            tmin[i] = bn / bd;
        }
    }

    private void energyRange(int i0, int i1, double coef) {
        for (int i = i0; i < i1; i++) {
            double xi = px[i], yi = py[i], zi = pz[i], NA = N[i], rA = rad[i], sA = sg[i], f = fIn[i], c = chi[i], rsA = rs[i];
            int pA = partner[i];
            double u = 0;
            for (int j = i + 1; j < n; j++) {
                double rx = px[j] - xi, ry = py[j] - yi, rz = pz[j] - zi;
                double r = Math.sqrt(Math.fma(rx, rx, Math.fma(ry, ry, rz * rz)));
                double R = Math.max(rA, rad[j]);
                double phi = r >= R ? 1 / r : (3 * R * R - r * r) / (2 * R * R * R), p;
                if (j == pA) {
                    double ps = r >= rsA ? 1 / r : (3 * rsA * rsA - r * r) / (2 * rsA * rsA * rsA);
                    p = f * (c * ps - phi);
                } else {
                    p = sA * sg[j] * phi;
                }
                u += coef * NA * N[j] * p;
            }
            U[i] = u;
        }
    }
}
