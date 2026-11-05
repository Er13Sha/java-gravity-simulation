package uif;

/**
 * Визуальный класс астротела (не физика). Задаётся в {@link Scenarios}; частицы и ручной CUSTOM —
 * {@link #NONE} (лаванетного ореола нет). {@link #PLANET} и {@link #MOON} — одна палитра.
 */
public enum AstroClass {
    NONE(0xB6A6FF, 0xF1ECFF, 0.7, false),
    STAR(0xFFE29A, 0xFFF6D8, 3.5, true),
    PLANET(0xE09A5A, 0xFFE4C8, 2.2, true),
    MOON(0xE09A5A, 0xFFE4C8, 2.0, true),   // тот же цвет, что у планет
    ASTEROID(0x7A7670, 0xB0ACA8, 1.0, false);

    /** RGB свечения и ядра; множитель ореола отрисовки; есть ли экранный halo. */
    public final int color, core;
    public final double glow;
    public final boolean halo;

    AstroClass(int color, int core, double glow, boolean halo) {
        this.color = color;
        this.core = core;
        this.glow = glow;
        this.halo = halo;
    }

    /**
     * Экранный радиус ореола (пиксели), не {@link Body#rad}: log₁₀(N) от ~луны (~1e11) до Солнца (~3e18)
     * → примерно 2…9 px. 0 — класс без halo (астероид / NONE).
     */
    public static double haloPx(Body b) {
        if (b == null || !b.astro.halo) return 0;
        double logN = Math.log10(Math.max(1, b.N));
        double t = (logN - 11.0) / (18.5 - 11.0);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return 2.0 + 7.0 * t;
    }
}
