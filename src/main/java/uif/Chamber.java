package uif;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Замкнутая область — замкнутая упругая оболочка вокруг сцены; форма динамическая.
 * Стенка — M узлов на фиксированных направлениях n̂_k от центра c, у каждого свой радиус R_k.
 * Поверхность между узлами гладкая: R(n̂) = Σ w_k·R_k / Σ w_k, w_k = exp(s·(n̂·n̂_k − 1)).
 * Стенка — источник с зарядом N, действие со знаком WALL_SIGN = −1 (отталкивание): на тело на расстоянии
 * d = R_s − ρ (ρ = |r − c|) действует W = Q(d)·N; у самой стенки (d &lt; δ) — N(d) = N·(d/δ)³.
 * R_s = R̄ + (R(n̂) − R̄)·f(ρ/R̄), f(s) = 1 − (1 − s²)³ при s &lt; 1, иначе 1: у стенки и за ней —
 * её настоящая форма, к центру — средний радиус R̄, иначе луч из центра дал бы особую точку
 * (сила ∝ 1/ρ от прогиба). f гладкая: f(1) = 1, f'(1) = f''(1) = 0.
 * Силы — точный −∇U: на тело (по нормали и вбок, где стенка наклонена), на каждый узел
 * (по его доле w̃_k) и на центр c (действие = противодействие: импульс тел + замкнутой области сохраняется).
 * Упругость: каждый узел — пружина к R₀ (K/M), соседние узлы связаны натяжением T = τ·K/M.
 * Инерция: узел m/M по радиусу, центр — m = k_v·N.
 * <p>
 * Вязкий слой у стенки и затухание узлов — добавка модели, не формулы: энергия уходит
 * в {@link #lost}, так что E + потери = const.
 */
public final class Chamber {
    /** Знак действия стенки: −1 — отталкивание (добавка модели; f(α) в монографии — множитель проекции, не знак). */
    public static final double WALL_SIGN = -1;
    public static final int M = 162;

    /** Направления узлов (сфера Фибоначчи), соседи и рёбра натяжения. */
    public static final double[][] DIRS = new double[M][];
    public static final int[][] EDGES;
    static final int[][] ADJ = new int[M][];
    static final int MAX_DEG;
    /** Резкость веса: ширина ядра ≈ шаг между узлами. */
    static final double S = M / (4 * Math.PI);

    static {
        for (int k = 0; k < M; k++) {
            double z = 1 - (2 * k + 1.0) / M, r = Math.sqrt(1 - z * z), ph = k * Math.PI * (3 - Math.sqrt(5));
            DIRS[k] = new double[]{r * Math.cos(ph), r * Math.sin(ph), z};
        }
        List<Set<Integer>> adj = new ArrayList<>();
        for (int k = 0; k < M; k++) adj.add(new LinkedHashSet<>());
        for (int k = 0; k < M; k++) {
            final int kk = k;
            Integer[] idx = new Integer[M];
            for (int j = 0; j < M; j++) idx[j] = j;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(dot(DIRS[b], DIRS[kk]), dot(DIRS[a], DIRS[kk])));
            for (int j = 1; j <= 6; j++) {
                adj.get(k).add(idx[j]);
                adj.get(idx[j]).add(k);
            }
        }
        List<int[]> edges = new ArrayList<>();
        int deg = 0;
        for (int k = 0; k < M; k++) {
            ADJ[k] = adj.get(k).stream().mapToInt(Integer::intValue).toArray();
            deg = Math.max(deg, ADJ[k].length);
            for (int j : ADJ[k]) if (j > k) edges.add(new int[]{k, j});
        }
        EDGES = edges.toArray(int[][]::new);
        MAX_DEG = deg;
    }

    private final World w;
    public boolean on;
    public double[] c = {0, 0, 0}, vc = new double[3];
    public final double[] Rk = new double[M], Vk = new double[M];
    public double R0 = 5, N = 1e3, delta = 0.02;
    /** Упругость оболочки, 1/с²: K = m·κ, собственная частота √κ — от G не зависит (стенка — добавка модели). */
    public double kappa = 1;
    /** Внешнее давление на оболочку снаружи (сила на единицу площади), сжимает её; 0 — нет. */
    public double pExt = 0;
    /** Суммарная радиальная сила тел на стенку изнутри (последний accelerate) — для радиуса покоя. */
    public double bodyPush;
    public double tau = 0.1;   // натяжение между соседними узлами, в долях K/M (мало — стенка мнётся локально)
    public double hFrac = 0.2; // толщина вязкого слоя у стенки, доля R
    public double visc = 10;   // вязкость слоя в единицах собственной частоты стенки
    public double zeta = 1;    // затухание узлов (1 — критическое для пружины к R₀)
    public double lost;        // энергия, ушедшая в вязкость и затухание
    final double[] aR = new double[M], ac = new double[3]; // ускорения узлов и центра

    Chamber(World w) {
        this.w = w;
        java.util.Arrays.fill(Rk, R0);
    }

    public double mass() {
        return Formula.K_V * N;
    }

    public double stiffness() {
        return mass() * kappa;
    }

    /**
     * Радиус покоя сейчас: где упругость оболочки уравновешивает давление тел изнутри и внешнее
     * давление снаружи. K·(R − R₀) = F_тел − p·4πR²; без давлений — R₀.
     */
    public double restRadius() {
        double K = stiffness(), F = bodyPush;
        double R = pExt > 0 ? (-K + Math.sqrt(K * K + 16 * Math.PI * pExt * Math.max(0, K * R0 + F))) / (8 * Math.PI * pExt) : R0 + F / K;
        return Math.max(0.05 * R0, R);
    }

    /** Собственная частота пружины узла √((K/M)/(m/M)) = √κ. */
    public double omega() {
        return Math.sqrt(stiffness() / mass());
    }

    /** Средний радиус стенки. */
    public double R() {
        double s = 0;
        for (double r : Rk) s += r;
        return s / M;
    }

    public void reset() {
        c = w.centerOfMass();
        vc = new double[3];
        java.util.Arrays.fill(Rk, R0);
        java.util.Arrays.fill(Vk, 0);
        w.changed();
    }

    /** Все ползунки замкнутой области — к значениям по умолчанию (R₀ задаёт сцена), замкнутая область выключается. */
    public void defaults() {
        on = false;
        extremeOn = false;
        N = 1e3;
        delta = 0.02;
        kappa = 1;
        pExt = 0;
        tau = 0.1;
        hFrac = 0.2;
        visc = 10;
        zeta = 1;
        lost = 0;
    }

    public void setOn(boolean on) {
        this.on = on;
        reset();
        if (on && !w.parts.isEmpty()) {
            double m = 0;
            for (Body b : w.parts) m = Math.max(m, World.dist(b.p, c) + b.rad);
            ensureInner(m + 2);
        }
    }

    /**
     * Растянуть оболочку, чтобы радиус {@code inner} от центра умещался внутри 0.8·R
     * (лимит {@link #inside} / {@link World#addPlaced}). Растёт {@link #R0} и все {@code R_k}.
     * Без этого молекула H₂ (bond ≈ 3) не влезает в песочницу R₀ = 1.5 и рвётся у стенки.
     */
    public void ensureInner(double inner) {
        if (!on || !(inner > 0) || Double.isNaN(inner)) return;
        double needR = inner / 0.8;
        double cur = Math.max(R(), R0);
        if (needR <= cur + 1e-12) return;
        R0 = Math.max(R0, needR);
        for (int k = 0; k < M; k++) if (Rk[k] < needR) Rk[k] = needR;
        w.changed();
    }

    /** Новое тело кладётся не дальше 0.8·R(n̂) от центра, если замкнутая область включена. */
    double[] inside(double[] pos) {
        if (!on) return pos;
        double[] r = {pos[0] - c[0], pos[1] - c[1], pos[2] - c[2]};
        double rm = Math.sqrt(dot(r, r));
        if (rm == 0) return pos;
        double lim = 0.8 * surface(new double[]{r[0] / rm, r[1] / rm, r[2] / rm}, null, null);
        if (rm <= lim) return pos;
        return new double[]{c[0] + r[0] * lim / rm, c[1] + r[1] * lim / rm, c[2] + r[2] * lim / rm};
    }

    /** Настоящая форма стенки: R(n̂) в направлении n̂ (единичный вектор). */
    public double surfaceRadius(double[] n) {
        return surface(n, null, null);
    }

    /**
     * Радиус и единичная нормаль к поверхности r(n̂) = R(n̂)·n̂ в направлении n̂ (только для
     * отрисовки): для такой поверхности нормаль ∝ R·n̂ − ∇_S R, где ∇_S R — касательная часть
     * градиента. Возвращает {R, N_x, N_y, N_z}.
     */
    public double[] surfacePoint(double[] n) {
        double[] g = new double[3];
        double Rw = surface(n, null, g);
        double gn = dot(g, n);
        double[] N = new double[3];
        for (int k = 0; k < 3; k++) N[k] = Rw * n[k] - (g[k] - gn * n[k]);
        double l = Math.sqrt(dot(N, N));
        return new double[]{Rw, N[0] / l, N[1] / l, N[2] / l};
    }

    /**
     * Во сколько раз стенка давит на тело сильнее, чем его тянет ядро (только измерение):
     * ускорение от стенки Q(d)·N(d) — та же QN, что в accelerate, — к притяжению ядра Q(r)·N_ядра.
     * Внутри оболочки ядра — та же N(r) = N·(r/R)³. Тело за стенкой — 0.
     */
    public double pushRatio(Body b, World.Lump nucleus) {
        double d = gap(b.p, null, null);
        if (d <= 0) return 0;
        double r = World.dist(b.p, nucleus.c());
        double pull = w.QN(r, Math.max(b.rad, nucleus.R()), nucleus.N());
        return pull > 0 ? w.QN(d, delta, N) / pull : Double.POSITIVE_INFINITY;
    }

    /** Заряд стенки в экстремальном режиме. */
    public static final double EXTREME_N = 1e9;
    private boolean extremeOn;
    private double savedN, savedKappa, savedTau;

    /** «Экстремальная» сфера: стенка с колоссальным зарядом, давит внутрь многократно сильнее ядра. */
    public void extreme() {
        setExtreme(true);
    }

    /**
     * Вкл — запомнить N, κ, τ и поставить N = 10⁹, κ = 10, τ = 1 (замкнутая область включается);
     * выкл — вернуть запомненные значения. Если N сдвинули ползунком, режим считается выключенным.
     */
    public void setExtreme(boolean value) {
        if (value == isExtreme()) return;
        if (value) {
            savedN = N;
            savedKappa = kappa;
            savedTau = tau;
            N = EXTREME_N;
            kappa = 10;
            tau = 1;
            extremeOn = true;
            if (!on) setOn(true);
        } else {
            if (extremeOn) {
                N = savedN;
                kappa = savedKappa;
                tau = savedTau;
            }
            extremeOn = false;
        }
        w.changed();
    }

    public boolean isExtreme() {
        return extremeOn && N == EXTREME_N;
    }

    /** Радиус стенки R_s, видимый из точки pos (у стенки — её форма в этом направлении). */
    public double radiusAt(double[] pos) {
        return gap(pos, null, null) + World.dist(pos, c);
    }

    /**
     * d = R_s − ρ. Если dX != null — туда ∂d/∂x (при ρ = 0 — NaN: там направление не определено);
     * если dR != null — туда ∂d/∂R_k.
     */
    private double gap(double[] pos, double[] dX, double[] dR) {
        double[] x = {pos[0] - c[0], pos[1] - c[1], pos[2] - c[2]};
        double rho = Math.sqrt(dot(x, x)), Rm = R();
        if (rho == 0) {
            if (dX != null) dX[0] = Double.NaN;
            return Rm;
        }
        double[] n = {x[0] / rho, x[1] / rho, x[2] / rho}, g = dX != null ? new double[3] : null;
        double Rw = surface(n, dR, g), sv = rho / Rm, dev = Rw - Rm;
        double q = 1 - sv * sv, f = sv < 1 ? 1 - q * q * q : 1, fp = sv < 1 ? 6 * sv * q * q : 0;
        if (dX != null) {
            // f/ρ = s·(3 − 3s² + s⁴)/R̄ — конечна при ρ → 0
            double gn = dot(g, n), ft = sv < 1 ? sv * (3 - 3 * sv * sv + sv * sv * sv * sv) / Rm : 1 / rho, radial = dev * fp / Rm - 1;
            for (int k = 0; k < 3; k++) dX[k] = (g[k] - gn * n[k]) * ft + radial * n[k];
        }
        if (dR != null) {
            double common = (1 - f - dev * fp * sv / Rm) / M;
            for (int k = 0; k < M; k++) dR[k] = f * dR[k] + common;
        }
        return Rm + dev * f - rho;
    }

    /**
     * R(n̂) = Σ w̃_k·R_k. Если wt != null — туда доли w̃_k; если grad != null — туда
     * ∂R/∂n̂ = s·Σ w̃_k·(R_k − R)·n̂_k (до проекции на касательную плоскость).
     */
    private double surface(double[] n, double[] wt, double[] grad) {
        double s0 = 0, s1 = 0;
        double[] ww = wt != null ? wt : new double[M];
        for (int k = 0; k < M; k++) {
            ww[k] = Math.exp(S * (dot(n, DIRS[k]) - 1));
            s0 += ww[k];
            s1 += ww[k] * Rk[k];
        }
        double Rw = s1 / s0;
        for (int k = 0; k < M; k++) ww[k] /= s0;
        if (grad != null) {
            grad[0] = grad[1] = grad[2] = 0;
            for (int k = 0; k < M; k++) {
                double f = S * ww[k] * (Rk[k] - Rw);
                for (int i = 0; i < 3; i++) grad[i] += f * DIRS[k][i];
            }
        }
        return Rw;
    }

    /** Поле стенки на тела, реакция на узлы и центр, упругость узлов. */
    void accelerate(double[][] a) {
        double m = mass(), mk = m / M;
        double[] Fk = new double[M], Fc = new double[3], dR = new double[M], dX = new double[3];
        for (int i = 0; i < w.parts.size(); i++) {
            Body A = w.parts.get(i);
            double d = gap(A.p, dX, dR);
            if (d <= 0 || Double.isNaN(dX[0])) continue;
            // G = dU/dd = WALL_SIGN·k_v·N_i·Q(d)·N(d) — та же форма Q·N, что у тел; сила = −G·∂d/∂x
            double G = WALL_SIGN * Formula.K_V * A.N * w.QN(d, delta, N);
            for (int k = 0; k < 3; k++) {
                double F = -G * dX[k];
                a[i][k] += F / A.m;
                Fc[k] -= F; // d зависит от x − c: на центр — противодействие
            }
            for (int k = 0; k < M; k++) Fk[k] -= G * dR[k];
        }
        bodyPush = 0;
        for (double f : Fk) bodyPush += f;
        double Kn = stiffness() / M, T = tau * Kn;
        for (int k = 0; k < M; k++) {
            Fk[k] -= Kn * (Rk[k] - R0);
            Fk[k] -= pExt * 4 * Math.PI * Rk[k] * Rk[k] / M; // внешнее давление на площадь узла
        }
        for (int[] e : EDGES) {
            double f = T * (Rk[e[0]] - Rk[e[1]]);
            Fk[e[0]] -= f;
            Fk[e[1]] += f;
        }
        for (int k = 0; k < M; k++) aR[k] = Fk[k] / mk;
        for (int k = 0; k < 3; k++) ac[k] = Fc[k] / m;
    }

    void kick(double h) {
        for (int k = 0; k < M; k++) Vk[k] += aR[k] * h;
        for (int k = 0; k < 3; k++) vc[k] += ac[k] * h;
    }

    /**
     * Вязкий слой толщиной h = hFrac·R_s: скорость тела по нормали относительно местной
     * скорости стенки u = v·n̂ − v_c·n̂ − W (W = Σ ∂R_s/∂R_k·V_k) гасится с темпом
     * γ = visc·ω·(1 − d/h)²; импульс уходит в центр замкнутой области. Узлы гасятся с ζ.
     * <p>
     * Все тела слоя — одновременно, а не по очереди: раньше каждое меняло v_c, которую видело
     * следующее, и итог зависел от порядка тел в списке. Теперь — неявная схема для всех сразу:
     * импульс пары «тело — стенка» κ_i·u_i', κ_i = μ_i·(e^{γ_i·dt} − 1), μ_i = m_i·m/(m_i + m);
     * u_i' — после шага. Одно тело — ровно прежнее u' = u·e^{−γ·dt}. Для v_c' — система 3×3:
     * (m·I + Σ α_i n̂_i n̂_iᵀ)·v_c' = m·v_c + Σ α_i (v_i·n̂_i − W_i)·n̂_i, α_i = κ_i·m_i/(m_i + κ_i).
     * Импульс тел + стенки сохраняется точно. Потери — точная убыль кинетической энергии, копятся
     * в lost; при неподвижных узлах она ≥ 0 (dt > 0). Затухание идёт относительно скорости узлов,
     * а импульс уходит только в центр, поэтому при движущихся узлах lost за шаг может быть и
     * отрицательной; в среднем подшаге Йошиды (dt < 0) κ < 0 — схема та же. Так же считает
     * видеокарта (ch_damp1…3 в {@link Gpu}).
     */
    void damp(double dt) {
        double m = mass(), mk = m / M, om = omega();
        double[] wt = new double[M];
        int nb = w.parts.size();
        double[][] nn = new double[nb][];
        double[] sw = new double[nb], kap = new double[nb];
        double[] Am = new double[6], b = new double[3]; // m·I + Σ α n nᵀ (xx, yy, zz, xy, xz, yz) и правая часть
        Am[0] = Am[1] = Am[2] = m;
        for (int k = 0; k < 3; k++) b[k] = m * vc[k];
        for (int i = 0; i < nb; i++) {
            Body A = w.parts.get(i);
            double[] x = {A.p[0] - c[0], A.p[1] - c[1], A.p[2] - c[2]};
            double rho = Math.sqrt(dot(x, x));
            if (rho == 0) continue;
            double[] n = {x[0] / rho, x[1] / rho, x[2] / rho};
            double d = gap(A.p, null, wt), h = hFrac * (d + rho); // wt = ∂R_s/∂R_k — доли узлов
            if (d <= 0 || d >= h) continue;
            double W = 0;
            for (int k = 0; k < M; k++) W += wt[k] * Vk[k];
            double mu = A.m * m / (A.m + m), ka = mu * Math.expm1(visc * om * Math.pow(1 - d / h, 2) * dt);
            double al = ka * A.m / (A.m + ka), s = dot(A.v, n) - W;
            Am[0] += al * n[0] * n[0];
            Am[1] += al * n[1] * n[1];
            Am[2] += al * n[2] * n[2];
            Am[3] += al * n[0] * n[1];
            Am[4] += al * n[0] * n[2];
            Am[5] += al * n[1] * n[2];
            for (int k = 0; k < 3; k++) b[k] += al * s * n[k];
            nn[i] = n;
            sw[i] = s;
            kap[i] = ka;
        }
        double[] vc1 = solve3(Am, b);
        lost += 0.5 * m * (dot(vc, vc) - dot(vc1, vc1));
        vc = vc1;
        for (int i = 0; i < nb; i++) {
            if (nn[i] == null) continue;
            Body A = w.parts.get(i);
            double[] n = nn[i];
            double u = (sw[i] - dot(n, vc)) * A.m / (A.m + kap[i]); // u' после шага
            double ds = -kap[i] * u / A.m, s0 = dot(A.v, n);
            for (int k = 0; k < 3; k++) A.v[k] += ds * n[k];
            lost += 0.5 * A.m * (s0 * s0 - (s0 + ds) * (s0 + ds)); // меняется только нормальная составляющая
        }
        double f = Math.exp(-2 * zeta * om * dt);
        for (int k = 0; k < M; k++) {
            double v2 = Vk[k] * f;
            lost += 0.5 * mk * (Vk[k] * Vk[k] - v2 * v2);
            Vk[k] = v2;
        }
    }

    void drift(double dt) {
        for (int k = 0; k < 3; k++) c[k] += vc[k] * dt;
        for (int k = 0; k < M; k++) {
            Rk[k] += Vk[k] * dt;
            // страховка: узел с затуханием до нуля не доходит, но вырождаться ему нельзя
            if (Rk[k] < 0.05 * R0) {
                Rk[k] = 0.05 * R0;
                Vk[k] = Math.max(0, Vk[k]);
            }
        }
    }

    /** Самое короткое время: тело у стенки и самая быстрая мода узлов (пружина + натяжение). */
    double dynTime() {
        double t = Double.POSITIVE_INFINITY;
        for (Body A : w.parts) {
            double g = gap(A.p, null, null), d = Math.max(delta, Math.abs(g));
            t = Math.min(t, Math.sqrt(d * d * d / (w.kQ() * N)));
            // у центра замкнутой области сила стенки меняет направление скачком (давит к центру со всех сторон):
            // время пролёта зоны радиуса ρ под ускорением стенки — иначе при большом N шаг перепрыгивает центр
            if (g > 0) {
                double rho = Math.max(delta, World.dist(A.p, c)), acc = w.QN(g, delta, N);
                if (acc > 0) t = Math.min(t, Math.sqrt(rho / acc));
            }
        }
        double om2 = omega() * omega() * (1 + 2 * MAX_DEG * tau); // λ_max(лапласиан) ≤ 2·степень
        return Math.min(t, 1 / Math.sqrt(om2));
    }

    /** U = −WALL_SIGN·kQ·N_i·N·φ(max(d, 0)) у каждого тела + пружины и натяжение узлов + движение стенки. */
    double energy() {
        double E = 0;
        for (Body A : w.parts) {
            double d = gap(A.p, null, null);
            E -= WALL_SIGN * Formula.K_V * w.kQ() * A.N * N * Formula.phi(Math.max(d, 0), delta);
        }
        double Kn = stiffness() / M, T = tau * Kn, mk = mass() / M;
        for (int k = 0; k < M; k++) {
            E += 0.5 * Kn * (Rk[k] - R0) * (Rk[k] - R0) + 0.5 * mk * Vk[k] * Vk[k];
            E += pExt * 4 * Math.PI / 3 * Rk[k] * Rk[k] * Rk[k] / M; // работа против внешнего давления: p·V
        }
        for (int[] e : EDGES) E += 0.5 * T * (Rk[e[0]] - Rk[e[1]]) * (Rk[e[0]] - Rk[e[1]]);
        return E + 0.5 * mass() * dot(vc, vc);
    }

    /** Импульс замкнутой области m·v_c. */
    double[] momentum() {
        double m = mass();
        return new double[]{m * vc[0], m * vc[1], m * vc[2]};
    }

    /** Решение симметричной 3×3 системы (xx, yy, zz, xy, xz, yz)·x = b — по Крамеру. */
    static double[] solve3(double[] A, double[] b) {
        double a = A[0], e = A[1], i = A[2], bb = A[3], c = A[4], f = A[5];
        double c0 = e * i - f * f, c1 = c * f - bb * i, c2 = bb * f - c * e;
        double det = a * c0 + bb * c1 + c * c2;
        return new double[]{
                (c0 * b[0] + c1 * b[1] + c2 * b[2]) / det,
                (c1 * b[0] + (a * i - c * c) * b[1] + (bb * c - a * f) * b[2]) / det,
                (c2 * b[0] + (bb * c - a * f) * b[1] + (a * e - bb * bb) * b[2]) / det};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
