package uif;

/**
 * Комплексное тяготение (версия комплексного отталкивания; Катющик, монография, стр. 15–17, 26–28, 35–40).
 * Все тела отталкивают друг друга, и удалённые массы — комплекс удалённых объектов (КУО) — тоже
 * отталкивают каждое тело. Пока тело одно, КУО со всех сторон одинаков и в сумме даёт ноль.
 * Тело j рядом с телом i «вырезано» из КУО в своём телесном угле: на той стороне в комплексе
 * разряженная область — дефицит массы, в точности равный массе тела j (масса сохраняется).
 * Отталкивание от КУО с противоположной стороны больше — тело i приталкивается к j.
 * Дефицит пропорционален массе и складывается по телам, поэтому сила от КУО на пару — та же
 * форма Q·N, что у W = Q·N, только направлена извне, от комплекса к телу.
 * <p>
 * Две шкалы: β_т — частное отталкивание тел, β_к — отталкивание (приталкивание) от КУО. Пара i–j:
 * −β_т·W_ij (от j) и +β_к·W_ij (к j); итог — (β_к − β_т)·W_ij = g·W_ij к j. Связанные шкалы
 * (β_к = 1 + β_т, как в монографии) дают g = 1 — итог ровно W = Q·N. Несвязанные — тяготение в g раз
 * сильнее (g < 0 — тела расталкиваются). Соотношения в монографии не заданы — свободные параметры,
 * 0…{@link #BETA_MAX}. Итог считается без вычитания огромных составляющих: W при β = 0 плюс (g − 1)·S,
 * поэтому при связанных шкалах он точно тот же, что в W = Q·N; составляющие для стрелок — отдельно,
 * только когда нужны. Полюса одного диполя — их собственная связь, от шкал не зависит. Внутри чужой
 * оболочки — та же N(r) = N·(r/R)³, что в W = Q·N. Силы зависят только от положений, поэтому
 * энергия (потенциал пар тоже ×g) и импульс сохраняются.
 * <p>
 * Замкнутый КУО (переключатель в теме «Тяготение», по умолчанию выключен). Бесконечный комплекс
 * держит запас N_КУО, которого хватает с избытком, поэтому приталкивание равно (1+β_т)·W. В замкнутом
 * локальные массы этот запас расходуют: доля q = ΣN/N_КУО, приталкивание ослабляется до β_к·(1 − q)·W,
 * итог пары — (β_к·(1 − q) − β_т)·W. При q = 1 − β_т/β_к итог ноль (равновесие, как в закрытой камере),
 * при больших q — отталкивание: тела не могут набрать массу больше запаса. При N_КУО → ∞ (или
 * closed = false) q → 0 и всё сходится к прежней модели.
 */
public final class Kuo {
    private final World w;
    /**
     * Наибольшая β: до ~4.5·10¹⁵ число 1 + β в double ещё отличается от β, дальше частное
     * отталкивание и приталкивание от КУО неразличимы.
     */
    public static final double BETA_MAX = 1e15;
    /** Частное отталкивание тел β_т, 0…BETA_MAX. */
    public double beta = 0.5;
    /**
     * Чип «КУО» в теме «Тяготение». true (по умолчанию) — комплексное: −β_т·W + приталкивание от КУО.
     * false — только частное отталкивание −β_т·W (без голубого push); два тела в покое разлетаются.
     */
    public boolean on = true;
    /**
     * Замкнутый КУО: у комплекса конечный запас N_КУО, и локальные массы его расходуют —
     * приталкивание ослабляется на q = ΣN/N_КУО. При closed = false запас бесконечный,
     * и модель в точности прежняя (q = 0).
     */
    public boolean closed;
    /** Заряд (масса) КУО в единицах N. Влияет только при {@link #closed}. */
    public double nKuo = 1e6;
    /** Шкала КУО связана с β_т: β_к = 1 + β_т, итог — ровно W. */
    public boolean linked = true;
    private double betaK = 1.5;
    private double[][] rep = new double[0][], push = new double[0][], tmp = new double[0][];
    /** Составляющие устарели: тела сдвинулись после последнего расчёта. */
    private boolean stale = true;

    Kuo(World w) {
        this.w = w;
    }

    /** Отталкивание от КУО β_к: при связанных шкалах 1 + β_т. */
    public double betaK() {
        return linked ? 1 + beta : betaK;
    }

    /** Задать β_к отдельно — шкалы больше не связаны. */
    public void setBetaK(double v) {
        betaK = v;
        linked = false;
    }

    /** Связать шкалы: β_к = 1 + β_т. */
    public void link() {
        linked = true;
        betaK = 1 + beta;
    }

    /** Итог пары в долях W: g = β_к − β_т; при связанных шкалах ровно 1. При {@code !on} — −β_т. */
    public double gain() {
        if (!on) return -beta;
        return linked ? 1 : betaK - beta;
    }

    /** Доля КУО, израсходованная локальными телами: q = ΣN/N_КУО, 0…1; в бесконечном КУО — 0. */
    public double q() {
        if (!on || !closed) return 0;
        double sum = 0;
        for (Body b : w.parts) sum += b.m;
        return Math.min(1, sum / nKuo);
    }

    /**
     * Итог пары в долях W с учётом замкнутости: β_к·(1 − q) − β_т. При closed = false совпадает
     * с {@link #gain()}; при q = 1 − β_т/β_к обращается в ноль, дальше — отталкивание.
     * При {@code !on} — −β_т (только частное отталкивание).
     */
    public double netGain() {
        if (!on) return -beta;
        return closed ? betaK() * (1 - q()) - beta : gain();
    }

    /** Во сколько раз короче время сближения пар при |g| > 1 (полюса диполя — не короче, оценка с запасом). */
    double dynScale() {
        double g = Math.abs(netGain());
        return g > 1 ? 1 / Math.sqrt(g) : 1;
    }

    /** Заполняет a ускорениями: при {@link #on} — комплексное (итог g·W); иначе — только −β_т·W. */
    double accelerate(double[][] a) {
        if (!on) return accelerateRepulsion(a);
        int n = w.parts.size();
        if (tmp.length != n) tmp = new double[n][3];
        double[][] r0 = new double[n][3];
        double dyn = w.forces.split(0, r0, tmp);
        double g1 = gain() - 1;
        double[][] r1 = null;
        if (g1 != 0) {
            r1 = new double[n][3];
            w.forces.split(1, r1, new double[n][3]);
        }
        double cut = closed ? betaK() * q() : 0; // приталкивание КУО ослаблено на q: −q·β_к·W
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                a[i][k] += r0[i][k] + tmp[i][k] - cut * tmp[i][k];
                if (r1 != null) a[i][k] += g1 * (r0[i][k] - r1[i][k]);
            }
        }
        stale = true;
        return dyn * dynScale();
    }

    /**
     * Только частное отталкивание −β_т·W (без приталкивания от КУО). Как {@link #compute} для rep
     * при g = −β: rep = rep₀ − β·S. Два тела в покое ускоряются врозь.
     */
    private double accelerateRepulsion(double[][] a) {
        int n = w.parts.size();
        if (tmp.length != n) tmp = new double[n][3];
        double[][] r0 = new double[n][3];
        double dyn = w.forces.split(0, r0, tmp);
        double b = beta;
        if (b == 0) {
            for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) a[i][k] += r0[i][k];
        } else {
            double[][] r1 = new double[n][3];
            w.forces.split(1, r1, new double[n][3]);
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 3; k++) {
                    double S = r0[i][k] - r1[i][k];
                    a[i][k] += r0[i][k] - b * S;
                }
            }
        }
        stale = true;
        return dyn * dynScale();
    }

    /** Частное отталкивание тел при текущих положениях (для стрелок и сводки). */
    public double[][] rep() {
        fresh();
        return rep;
    }

    /** Приталкивание от дефицита массы КУО при текущих положениях. */
    public double[][] push() {
        fresh();
        return push;
    }

    private void fresh() {
        int n = w.parts.size();
        if (!stale && rep.length == n) return;
        rep = new double[n][3];
        push = new double[n][3];
        if (!on) {
            compute(beta, -beta, rep, push, null); // push обнулим: КУО выкл
            for (double[] v : push) v[0] = v[1] = v[2] = 0;
        } else {
            compute(beta, gain(), rep, push, null);
            if (closed) { // стрелки показывают ослабленное приталкивание
                double s = 1 - q();
                for (double[] v : push) for (int k = 0; k < 3; k++) v[k] *= s;
            }
        }
        stale = false;
    }

    /**
     * Составляющие ускорений по парам при β_т = b и итоге g·W: rep — частное отталкивание −b·W,
     * push — приталкивание от дефицита массы КУО +(b + g)·W (у полюсов диполя — их отталкивание и
     * прижатие); total (если не null) — итог. Без потери точности при любом b: расчёт линеен по b,
     * поэтому rep = rep₀ − b·S, push = push₀ + (b + g − 1)·S, total = rep₀ + push₀ + (g − 1)·S, где
     * rep₀, push₀ — при b = 0, а S = rep₀ − rep₁ — сумма W_ij по парам не из одного диполя; при g = 1
     * итог от b не зависит и равен W = Q·N. Состояние модели не меняет. Перебор пар — в {@link Forces}.
     */
    public void compute(double b, double g, double[][] rep, double[][] push, double[][] total) {
        int n = w.parts.size();
        w.forces.split(0, rep, push);
        if (total != null) for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) total[i][k] = rep[i][k] + push[i][k];
        if (b == 0 && g == 1) return;
        double[][] r1 = new double[n][3], p1 = new double[n][3];
        w.forces.split(1, r1, p1);
        double up = g == 1 ? b : b + (g - 1); // связанные шкалы: ровно b, как прежде
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                double S = rep[i][k] - r1[i][k];
                rep[i][k] -= b * S;
                push[i][k] += up * S;
                if (total != null && g != 1) total[i][k] += (g - 1) * S;
            }
        }
    }

    /** Какую долю телесного угла тело радиуса R закрывает с расстояния r: Ω/4π (≈ R²/4r² вдали). */
    public static double solidAngleFraction(double R, double r) {
        return r <= R ? 1 : 0.5 * (1 - Math.sqrt(1 - R * R / (r * r)));
    }
}
