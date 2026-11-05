package uif.ui;

/** Переключатели отображения, общие для сцены и панелей. */
final class Settings {
    /**
     * Мягкая визуальная деформация до 8% с учётом ракурса; физика остаётся сферой.
     * «выкл» / «ускорение» (net a) / «прилив» (к ближайшему массивному) — Расчёт → Рисование.
     */
    enum DeformMode {
        OFF("Выкл"),
        ACCEL("Ускорение"),
        TIDAL("Прилив");

        final String title;

        DeformMode(String title) {
            this.title = title;
        }
    }

    boolean paused;
    boolean showField;
    boolean showTrail;         // следы — по умолчанию выкл (T / «след»)
    boolean detailsOpen;
    boolean testOpen;
    boolean showGround;        // 3D-опоры: пол, тени-проекции, оси — по умолчанию выкл
    /** Скорость времени: во сколько раз модельное время идёт быстрее реального (шаг расчёта тот же). */
    double speed = 1;
    boolean showEquator;       // линия экватора на телах; только отображение
    boolean showForces;        // стрелки: частное отталкивание, приталкивание от КУО, итог
    /**
     * Оверлей КУО (тема «Вид», «отображение КУО»): дальняя сфера комплекса, пятна/конусы
     * дефицита у тел и тонкий HUD q — только картинка, силы не меняет. По умолчанию выкл.
     */
    boolean showKuo;
    /**
     * Оверлей «полюса как стороны» (тема «Вид»): у каждого тела две встречные составляющие —
     * красная отталкивание, голубая приталкивание от КУО (у диполей — те же стороны связи).
     * Только картинка; Forces / pairQN не меняет. По умолчанию выкл.
     */
    boolean showPolesSides;
    boolean shaders = true;    // тела и следы — шейдерами OpenGL (GlScene); нет OpenGL — Java2D
    boolean bodyField;         // частицы — облаками своего поля (цвет по «нагреву»), а не шарами
    boolean showLabels;        // подписи частиц — по умолчанию выкл (до LABELS_MAX тел)
    /** Рисовать обнаруженные устойчивые орбиты (кнопка «орбиты»; доступна только после детекции). */
    boolean showOrbits;
    /** Есть ли сейчас хотя бы одна устойчивая орбита — включает кнопку «орбиты». */
    boolean orbitsReady;
    /** Экранный squash/stretch дисков; по умолчанию выкл. */
    DeformMode deform = DeformMode.OFF;

    /** Переключатели отображения — как при запуске. */
    void defaults() {
        paused = false;
        showField = false;
        showTrail = false;
        detailsOpen = false;
        testOpen = false;
        showGround = false;
        showEquator = false;
        showForces = false;
        showKuo = false;
        showPolesSides = false;
        shaders = true;
        bodyField = false;
        showLabels = false;
        showOrbits = false;
        orbitsReady = false;
        speed = 1;
        deform = DeformMode.OFF;
    }
}
