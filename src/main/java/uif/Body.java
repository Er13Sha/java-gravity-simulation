package uif;

/** Тело — однородная сфера: S = πr², h = 4r/3, V = hS; у частиц N = V·q, у астротел N и rad задаются раздельно. */
public final class Body {
    public final Species kind;
    public final int atom;  // номер атома, −1 — свободное тело
    int nuc = -1;           // номер нуклона, −1 — не нуклон
    /**
     * Визуальный класс астро-пресета ({@link AstroClass#NONE} у частиц и ручного CUSTOM —
     * без планетного ореола). Не влияет на N/rad и силы.
     */
    public AstroClass astro = AstroClass.NONE;
    public final double rad, q, S, h, V, N, m;
    public final double[] p, v;
    /** Знак полюса диполя: +1 у p, n, e и d+, −1 у d−. Сила W = Q·N в паре — × sᵢ·sⱼ. */
    public final int sign;
    /** Второй полюс того же диполя (null — не полюс); f(α) внутри диполя и сила экранирования χ, радиус экранирования. */
    public Body partner;
    public double fIn, chi, rs;
    public final Trail trail = new Trail(Formula.TRAIL_LEN);
    /** Свой цвет следа у каждого тела: оттенки по золотому углу, соседние номера не похожи. */
    public final int trailColor = nextTrailColor();

    private static int trailCount;

    private static synchronized int nextTrailColor() {
        float hue = (float) ((trailCount++ * 0.618033988749895) % 1.0);
        return java.awt.Color.HSBtoRGB(hue, 0.6f, 1f) & 0xFFFFFF;
    }

    Body(Species kind, int atom, double[] pos, double[] vel) {
        this(kind, atom, kind.rad, pos, vel);
    }

    /** Полюс диполя / своя частица: радиус из заданного N = V·q. */
    Body(Species kind, double N, double[] pos, double[] vel) {
        this(kind, -1, Formula.radOf(N), pos, vel);
    }

    /**
     * Астротело: заряд/масса N и геометрический радиус независимо (реальные доли R⊕ при массах
     * Солнце ≫ планеты ≫ спутники ≫ астероиды; у частиц по-прежнему N = V·q).
     */
    Body(Species kind, double N, double rad, double[] pos, double[] vel) {
        this.kind = kind;
        this.atom = -1;
        this.rad = rad;
        sign = kind == Species.DM ? -1 : 1;
        q = Formula.Q_DENS;
        S = Math.PI * rad * rad;
        h = 4 * rad / 3;
        V = h * S;
        this.N = N;
        m = Formula.K_V * N;
        p = pos.clone();
        v = vel.clone();
    }

    private Body(Species kind, int atom, double rad, double[] pos, double[] vel) {
        this.kind = kind;
        this.atom = atom;
        this.rad = rad;
        sign = kind == Species.DM ? -1 : 1;
        q = Formula.Q_DENS;
        S = Math.PI * rad * rad;
        h = 4 * rad / 3;
        V = h * S;
        N = V * q;
        m = Formula.K_V * N; // инерция = n_f = k_v·h·S·q (для тяготения заряд — масса)
        p = pos.clone();
        v = vel.clone();
    }

    public boolean isPole() {
        return partner != null;
    }

    public boolean isNucleon() {
        return nuc >= 0;
    }
}
