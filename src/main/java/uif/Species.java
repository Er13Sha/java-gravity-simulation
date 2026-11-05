package uif;

/** Виды частиц: радиус из N = V·q, цвета и подписи для отрисовки. */
public enum Species {
    P("p", "протон", Formula.RAD_P, 0xFF9650, 0xFFF3E0, 2, 0.45),
    N("n", "нейтрон", Formula.RAD_N, 0xC8CDD7, 0xF2F4F8, 2, 0.5),
    E("e", "электрон", Formula.RAD_E, 0x50E6FF, 0xEAFFFF, 3, 0.9),
    // полюса диполя (добавка модели, не из монографии); радиус задаётся при создании из N = V·q
    DP("d+", "полюс диполя +", Formula.radOf(1e-3), 0xFF5A78, 0xFFE6EC, 6, 0.9),
    DM("d−", "полюс диполя −", Formula.radOf(1e-3), 0x7A8CFF, 0xE8ECFF, 6, 0.9),
    // своя частица (добавка модели): массу задаёт пользователь, радиус — из N = V·q при создании
    CUSTOM("m", "своя частица", Formula.RAD_E, 0xB6A6FF, 0xF1ECFF, 3, 0.7);

    public final String label, name;
    public final double rad;
    public final int color, core;  // RGB свечения и ядра диска
    public final double glow, alpha;

    Species(String label, String name, double rad, int color, int core, double glow, double alpha) {
        this.label = label;
        this.name = name;
        this.rad = rad;
        this.color = color;
        this.core = core;
        this.glow = glow;
        this.alpha = alpha;
    }
}
