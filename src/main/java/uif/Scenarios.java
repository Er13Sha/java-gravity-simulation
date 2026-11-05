package uif;

import java.util.ArrayList;
import java.util.List;

/**
 * Пресеты наполнения сцены (вкладка «Сценарий»): Земля–Луна, Солнечная система с режимом
 * спутников/поясов {@link MoonsBeltsMode} (A/B/D), рандомная СС, большая звезда,
 * планета со спутниками, группа солнечных систем, песочница, «мелкие фракции».
 * Астрономический масштаб: 1 единица длины = радиус Земли при N⊕ = {@link #EARTH_N};
 * орбиты — круговые из W = Q·N. Радиусы астротел — реальные доли R⊕ (независимо от
 * N = V·q). Только честный N-body; visual-кольца вне скоупа.
 */
public final class Scenarios {
    private Scenarios() {}

    /** Земля: N ≈ 1.00×10¹³ (зафиксировано в UI-предложении). */
    public static final double EARTH_N = 1e13;
    /** Доля массы Луны от Земли по умолчанию ≈ 1/81.3. */
    public static final double MOON_SHARE_DEFAULT = 1.0 / 81.3;
    /** Среднее расстояние Земля–Луна в радиусах Земли. */
    public static final double MOON_A_RE = 60.34;
    /**
     * 1 а.е. в радиусах Земли (~23455): общий астрономический масштаб длин для Земля–Луна
     * (через {@link #MOON_A_RE}) и Солнечной системы. Не сжимать — иначе ломаются орбиты/а.е.
     */
    public static final double AU_RE = 23454.8;

    /** Число «мелких фракций» по умолчанию. */
    public static final int FRAGMENT_COUNT_DEFAULT = 2000;
    /** Массы «мелких фракций» (m_e), добавка поверх сцены (÷3 от прежних 1e3…1e8). */
    public static final double ASTEROID_N_MIN = 1e3 / 3;
    public static final double ASTEROID_N_MAX = 1e8 / 3;
    /**
     * Массы тел поясов СС (m_e): ≪ планет; ÷3 от прежних 1e1…1e4 → ~3.33…~3.33e3.
     */
    public static final double BELT_N_MIN = 1e1 / 3;
    public static final double BELT_N_MAX = 1e4 / 3;
    /** Главный пояс (Марс–Юпитер), а.е. */
    public static final double MAIN_BELT_AU0 = 2.2, MAIN_BELT_AU1 = 3.3;
    /** Базовый (исторический) счётчик пояса; режимы A/B/D используют {@link #MAIN_BELT_DENSE}. */
    public static final int MAIN_BELT_COUNT = 180;
    /** Плотнее главный пояс для режимов A/B/D. */
    public static final int MAIN_BELT_DENSE = 350;
    /** Пояс Койпера (за Нептуном), а.е. */
    public static final double KUIPER_AU0 = 30, KUIPER_AU1 = 48;
    public static final int KUIPER_COUNT = 100;
    /** Рой у гигантов: B — шире; D — умеренный. */
    public static final int SWARM_N_MIN_B = 50, SWARM_N_MAX_B = 80;
    public static final int SWARM_N_MIN_D = 40, SWARM_N_MAX_D = 60;
    /** Суммарный бюджет массы роя относительно планеты (крошечные N). */
    public static final double SWARM_MASS_FRAC = 1e-6;
    /** Спутники спутников: бюджет массы относительно родительской луны. */
    public static final double SUBSAT_MASS_FRAC = 1e-5;
    /** Доля сферы Хилла луны для устойчивых круговых орбит. */
    public static final double SUBSAT_HILL_FRAC = 0.33;
    /** Число субспутников на крупную луну: A — мало; B/D — мелкий рой. */
    public static final int SUBSAT_N_MIN_A = 1, SUBSAT_N_MAX_A = 3;
    public static final int SUBSAT_N_MIN_B = 4, SUBSAT_N_MAX_B = 8;
    public static final int SUBSAT_N_MIN_D = 3, SUBSAT_N_MAX_D = 6;

    // --- геометрические радиусы в R⊕ (средние; масса N задаётся отдельно) ---
    public static final double SUN_RE = 109.2;
    public static final double MERCURY_RE = 0.383;
    public static final double VENUS_RE = 0.950;
    public static final double EARTH_RE = 1.0;
    public static final double MOON_RE = 0.273;
    public static final double MARS_RE = 0.532;
    public static final double JUPITER_RE = 11.21;
    public static final double IO_RE = 0.286;
    public static final double EUROPA_RE = 0.245;
    public static final double GANYMEDE_RE = 0.413;
    public static final double CALLISTO_RE = 0.378;
    public static final double SATURN_RE = 9.45;
    public static final double TITAN_RE = 0.404;
    public static final double RHEA_RE = 0.240;
    public static final double IAPETUS_RE = 0.230;
    public static final double DIONE_RE = 0.176;
    public static final double TETHYS_RE = 0.167;
    public static final double ENCELADUS_RE = 0.079;
    public static final double URANUS_RE = 4.01;
    public static final double TITANIA_RE = 0.124;
    public static final double OBERON_RE = 0.120;
    public static final double UMBRIEL_RE = 0.092;
    public static final double ARIEL_RE = 0.091;
    public static final double MIRANDA_RE = 0.037;
    public static final double NEPTUNE_RE = 3.88;
    public static final double TRITON_RE = 0.212;
    public static final double NEREID_RE = 0.027;
    public static final double PHOBOS_RE = 0.0018;
    public static final double DEIMOS_RE = 0.0010;
    /** Пояс: геометрический радиус ≪ спутников (~0.2 R⊕), log от массы → ~5e-5…5e-3 R⊕. */
    public static final double BELT_RE_MIN = 5e-5, BELT_RE_MAX = 5e-3;
    /** Мелкие фракции: ещё мельче типичных спутников, ~1e-5…2e-3 R⊕. */
    public static final double FRAG_RE_MIN = 1e-5, FRAG_RE_MAX = 2e-3;
    /** «большая звезда»: масса и радиус ≫ Солнца (одиночное тело STAR). */
    public static final double BIG_STAR_MSUN = 100;
    public static final double BIG_STAR_RE = SUN_RE * 80;
    /** «группа солнечных систем»: сколько случайных СС разнести в пространстве. */
    public static final int SOLAR_GROUP_MIN = 10;
    public static final int SOLAR_GROUP_MAX = 20;

    /**
     * Наклон орбиты относительно плоскости набора (pack plane). Планеты — реалистично
     * малые (≲ Меркурия ~7°); спутники — чуть шире; пояса — толще по i.
     */
    public static final double PLANET_I_MAX = Math.toRadians(7);
    public static final double MOON_I_MAX = Math.toRadians(12);
    public static final double BELT_I_MAX = Math.toRadians(25);

    /**
     * Режим спутников/поясов для SOLAR / RANDOM_SOLAR / PLANET_MOONS / SOLAR_GROUP
     * (выбор до генерации). Только честный N-body; visual-кольца вне скоупа.
     */
    public enum MoonsBeltsMode {
        /** Именованные средние + плотный главный пояс. */
        A("A имен."),
        /** Крупные именные + статистический рой у гигантов + плотный пояс. */
        B("B рой"),
        /** Гибрид: именные A + умеренный рой B (по умолчанию). */
        D("D гибрид");

        public final String title;

        MoonsBeltsMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Режим по умолчанию — гибрид D. */
    public static final MoonsBeltsMode MOONS_BELTS_DEFAULT = MoonsBeltsMode.D;

    /**
     * Порядок = порядок в radio-list: «мелкие фракции» — последними.
     */
    public enum Preset {
        EARTH_MOON("Земля–Луна"),
        SOLAR("Солнечная система"),
        RANDOM_SOLAR("Рандомная солнечная система"),
        BIG_STAR("Большая звезда"),
        PLANET_MOONS("Планета со спутниками"),
        SOLAR_GROUP("Группа солнечных систем"),
        SANDBOX("Песочница"),
        FRAGMENTS("Мелкие фракции");

        public final String title;

        Preset(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Число тел главного пояса (A/B/D denser). */
    public static int mainBeltCount(World w) {
        return MAIN_BELT_DENSE;
    }

    /** Целевой размер роя на гигант (случайный в диапазоне режима). */
    public static int swarmCount(World w, MoonsBeltsMode mode) {
        int lo = mode == MoonsBeltsMode.B ? SWARM_N_MIN_B : SWARM_N_MIN_D;
        int hi = mode == MoonsBeltsMode.B ? SWARM_N_MAX_B : SWARM_N_MAX_D;
        return lo + (int) (w.nextU() * (hi - lo + 1));
    }

    /** Число спутников спутников вокруг крупной луны. */
    public static int subsatCount(World w, MoonsBeltsMode mode) {
        int lo, hi;
        if (mode == MoonsBeltsMode.A) {
            lo = SUBSAT_N_MIN_A;
            hi = SUBSAT_N_MAX_A;
        } else if (mode == MoonsBeltsMode.B) {
            lo = SUBSAT_N_MIN_B;
            hi = SUBSAT_N_MAX_B;
        } else {
            lo = SUBSAT_N_MIN_D;
            hi = SUBSAT_N_MAX_D;
        }
        return lo + (int) (w.nextU() * (hi - lo + 1));
    }

    /** Крупная луна — кандидат на честные субспутники (не Phobos/рой). */
    public static boolean largeMoon(double N, double radiusRe) {
        return radiusRe >= 0.11 || N >= EARTH_N * 5e-4;
    }

    /** Рекомендуемое расстояние камеры под пресет (после apply). */
    public static double camR(World w) {
        return switch (w.preset) {
            case EARTH_MOON -> 1.6 * moonDistance(w);
            case SOLAR -> 1.15 * AU_RE * earthRadius() * KUIPER_AU1; // чуть дальше пояса Койпера
            case RANDOM_SOLAR, PLANET_MOONS, SOLAR_GROUP -> {
                double R = packRadius(w.parts);
                yield R > 1 ? 1.15 * R : 1.15 * AU_RE * earthRadius() * 48;
            }
            case BIG_STAR -> 6.0 * BIG_STAR_RE * earthRadius();
            case FRAGMENTS -> Math.max(8, 2.2 * Math.cbrt(Math.max(1, w.fragmentCount)));
            case SANDBOX -> 2.5;
        };
    }

    public static double earthRadius() {
        return Formula.radOf(EARTH_N);
    }

    public static double moonDistance(World w) {
        return MOON_A_RE * earthRadius();
    }

    public static double moonN(double share) {
        return EARTH_N * Math.max(1e-12, share);
    }

    /** Случайная масса для «мелких фракций»: log-равномерно [{@link #ASTEROID_N_MIN}…{@link #ASTEROID_N_MAX}]. */
    public static double asteroidMass(double u) {
        return logUniform(u, ASTEROID_N_MIN, ASTEROID_N_MAX);
    }

    /** Масса тела пояса СС: log-равномерно [{@link #BELT_N_MIN}…{@link #BELT_N_MAX}]. */
    public static double beltMass(double u) {
        return logUniform(u, BELT_N_MIN, BELT_N_MAX);
    }

    private static double logUniform(double u, double lo, double hi) {
        double a = Math.log10(lo), b = Math.log10(hi);
        return Math.pow(10, a + (b - a) * Math.min(1, Math.max(0, u)));
    }

    /** Геометрический радиус пояса/фракции из доли массы в своём диапазоне → [reMin…reMax]·R⊕. */
    private static double rockRadius(double N, double nMin, double nMax, double reMin, double reMax) {
        double t = (Math.log10(Math.max(nMin, Math.min(nMax, N))) - Math.log10(nMin))
                / Math.max(1e-15, Math.log10(nMax) - Math.log10(nMin));
        return earthRadius() * (reMin + (reMax - reMin) * Math.min(1, Math.max(0, t)));
    }

    /** Астротело: масса N и радиус в долях R⊕ независимо; {@code cls} — только отрисовка. */
    private static Body astro(double N, double radiusRe, AstroClass cls, double[] pos, double[] vel) {
        Body b = new Body(Species.CUSTOM, N, radiusRe * earthRadius(), pos, vel);
        b.astro = cls;
        return b;
    }

    /** Базовый пресет сцены (не «мелкие фракции» — они только добавляются поверх). */
    public static boolean isBase(Preset p) {
        return p != Preset.FRAGMENTS;
    }

    /** Пресет, который при выборе очищает сцену (остальные базы — append). */
    public static boolean clearsScene(Preset p) {
        return p == Preset.SANDBOX;
    }

    static List<Body> build(World w) {
        return switch (w.preset) {
            case EARTH_MOON -> earthMoon(w);
            case SOLAR -> solarSystem(w);
            case RANDOM_SOLAR -> randomSolarSystem(w);
            case BIG_STAR -> bigStar();
            case PLANET_MOONS -> planetWithMoons(w);
            case SOLAR_GROUP -> solarGroup(w);
            case SANDBOX, FRAGMENTS -> new ArrayList<>(); // FRAGMENTS не база — только addFragments
        };
    }

    /**
     * Свежий набор тел пресета (Земля–Луна / СС / рандом / большая звезда / планета+луны /
     * группа СС) для {@link World#appendPreset} — орбиты внутри набора, без привязки к
     * уже имеющимся телам.
     */
    public static List<Body> makeBase(World w, Preset p) {
        return switch (p) {
            case EARTH_MOON -> earthMoon(w);
            case SOLAR -> solarSystem(w);
            case RANDOM_SOLAR -> randomSolarSystem(w);
            case BIG_STAR -> bigStar();
            case PLANET_MOONS -> planetWithMoons(w);
            case SOLAR_GROUP -> solarGroup(w);
            default -> new ArrayList<>();
        };
    }

    /**
     * При apply/append этого пресета UI включает «отображение КУО» и устройство
     * {@link Forces.Device#GPU_FLOAT_BH} (много тел).
     */
    public static boolean enablesKuoAndGpuBh(Preset p) {
        return p == Preset.SOLAR_GROUP;
    }

    /** Одиночная звезда ≫ Солнца по N и радиусу ({@link #BIG_STAR_MSUN}, {@link #BIG_STAR_RE}). */
    private static List<Body> bigStar() {
        Body star = astro(EARTH_N * 332_946 * BIG_STAR_MSUN, BIG_STAR_RE,
                AstroClass.STAR, new double[3], new double[3]);
        return new ArrayList<>(List.of(star));
    }

    /**
     * Одна случайная планета + честные N-body спутники (режим {@link World#moonsBeltsMode}).
     * Без звезды и поясов: только PLANET ≫ MOON; случайная pack-плоскость + наклоны лун.
     * Гигант (~55%) или каменистая; всегда ≥1 луна; рой/субспутники — как в RANDOM_SOLAR.
     */
    private static List<Body> planetWithMoons(World w) {
        MoonsBeltsMode mode = w.moonsBeltsMode != null ? w.moonsBeltsMode : MOONS_BELTS_DEFAULT;
        boolean namedExtra = mode == MoonsBeltsMode.A || mode == MoonsBeltsMode.D;
        boolean swarm = mode == MoonsBeltsMode.B || mode == MoonsBeltsMode.D;
        double[][] plane = randomOrbitPlane(w);

        boolean giant = w.nextU() < 0.55;
        double nEarth, radRe;
        if (giant) {
            nEarth = logUniform(w.nextU(), 12, 380);
            radRe = lerp(4.0, 13.5, Math.min(1, (Math.log10(nEarth) - Math.log10(12))
                    / (Math.log10(380) - Math.log10(12))));
            radRe *= 0.9 + 0.25 * w.nextU();
        } else {
            nEarth = logUniform(w.nextU(), 0.04, 2.8);
            radRe = lerp(0.28, 1.55, Math.min(1, (Math.log10(nEarth) - Math.log10(0.04))
                    / (Math.log10(2.8) - Math.log10(0.04))));
            radRe *= 0.9 + 0.25 * w.nextU();
        }
        Body planet = astro(EARTH_N * nEarth, radRe, AstroClass.PLANET, new double[3], new double[3]);
        List<Body> all = new ArrayList<>();
        all.add(planet);

        int majorMax;
        if (giant) {
            majorMax = namedExtra ? 2 + (int) (w.nextU() * 4) : 1 + (int) (w.nextU() * 3);
        } else {
            majorMax = namedExtra
                    ? 1 + (int) (w.nextU() * 3)
                    : 1 + (int) (w.nextU() * 2);
        }
        majorMax = Math.max(1, majorMax);
        for (int m = 0; m < majorMax; m++) {
            double share = logUniform(w.nextU(), 2e-5, giant ? 0.03 : 0.02);
            double moonN = Math.min(planet.N * share, EARTH_N * (giant ? 0.04 : 0.02));
            double moonRe = clamp(radRe * (0.12 + 0.28 * w.nextU()) * Math.pow(share / 0.01, 0.25),
                    0.08, Math.min(0.55, radRe * 0.45));
            double aMoon = planet.rad * (5 + 32 * w.nextU());
            if (largeMoon(moonN, moonRe))
                addMajorMoon(w, all, planet, moonN, moonRe, aMoon, mode, plane);
            else
                addMoon(w, all, planet, moonN, moonRe, aMoon, plane);
        }
        if (giant && swarm) addMoonSwarm(w, all, planet, swarmCount(w, mode), plane);
        return all;
    }

    /** Новые астероидные тела для добавления поверх текущей сцены. */
    public static List<Body> makeFragments(World w) {
        return fragments(w);
    }

    /** Оценка «радиуса» набора тел от центра масс (для сдвига append). */
    public static double packRadius(List<Body> bodies) {
        if (bodies == null || bodies.isEmpty()) return 0;
        World.Lump L = World.lump(bodies);
        double R = 0;
        for (Body b : bodies) R = Math.max(R, World.dist(b.p, L.c()) + b.rad);
        return R;
    }

    private static List<Body> earthMoon(World w) {
        double nE = EARTH_N, nM = moonN(w.moonShare);
        Body earth = astro(nE, EARTH_RE, AstroClass.PLANET, new double[3], new double[3]);
        Body moon = astro(nM, MOON_RE, AstroClass.MOON, new double[3], new double[3]);
        List<Body> e = new ArrayList<>(List.of(earth)), m = new ArrayList<>(List.of(moon));
        w.circularOrbit(m, e, moonDistance(w), new double[]{1, 0, 0}, new double[]{0, 1, 0});
        w.setPackNormal(0, 0, 1); // экватор Земли = плоскость орбиты Луны (XY)
        List<Body> all = new ArrayList<>(2);
        all.addAll(e);
        all.addAll(m);
        return all;
    }

    /**
     * Солнце + 8 планет + спутники (режим {@link World#moonsBeltsMode}) + главный пояс и Койпер.
     * Массы — доли от Земли × {@link #EARTH_N}; радиусы — реальные доли R⊕;
     * полуоси — в а.е. × {@link #AU_RE}·R⊕ (полный астро-масштаб).
     * Базовая плоскость — случайная на набор (multi-append / RMB); у каждого тела
     * свой скромный наклон к ней (планеты ≲7°, пояса толще).
     * Без visual-колец: только честные тела в {@code parts}.
     */
    private static List<Body> solarSystem(World w) {
        MoonsBeltsMode mode = w.moonsBeltsMode != null ? w.moonsBeltsMode : MOONS_BELTS_DEFAULT;
        boolean namedExtra = mode == MoonsBeltsMode.A || mode == MoonsBeltsMode.D;
        boolean swarm = mode == MoonsBeltsMode.B || mode == MoonsBeltsMode.D;
        double re = earthRadius(), au = AU_RE * re;
        Body sun = astro(EARTH_N * 332_946, SUN_RE, AstroClass.STAR, new double[3], new double[3]);
        List<Body> all = new ArrayList<>();
        all.add(sun);
        double[][] plane = randomOrbitPlane(w); // ex, ey, normal

        int k = 0;
        addPlanet(w, all, sun, EARTH_N * 0.055, MERCURY_RE, 0.387 * au, phase(k++), plane);
        addPlanet(w, all, sun, EARTH_N * 0.815, VENUS_RE, 0.723 * au, phase(k++), plane);
        Body earth = addPlanet(w, all, sun, EARTH_N, EARTH_RE, 1.0 * au, phase(k++), plane);
        addMajorMoon(w, all, earth, moonN(w.moonShare), MOON_RE, MOON_A_RE * re, mode, plane);
        Body mars = addPlanet(w, all, sun, EARTH_N * 0.107, MARS_RE, 1.524 * au, phase(k++), plane);
        if (namedExtra) {
            addMoon(w, all, mars, EARTH_N * 1.07e-8, PHOBOS_RE, 2.76 * mars.rad, plane); // мелкие — без суб
            addMoon(w, all, mars, EARTH_N * 1.8e-9, DEIMOS_RE, 6.92 * mars.rad, plane);
        }
        addBelt(w, all, sun, au, mainBeltCount(w), MAIN_BELT_AU0, MAIN_BELT_AU1, plane);
        Body jupiter = addPlanet(w, all, sun, EARTH_N * 317.8, JUPITER_RE, 5.203 * au, phase(k++), plane);
        addMajorMoon(w, all, jupiter, EARTH_N * 0.015, IO_RE, 5.9 * jupiter.rad, mode, plane);
        addMajorMoon(w, all, jupiter, EARTH_N * 0.008, EUROPA_RE, 9.4 * jupiter.rad, mode, plane);
        addMajorMoon(w, all, jupiter, EARTH_N * 0.025, GANYMEDE_RE, 15.0 * jupiter.rad, mode, plane);
        addMajorMoon(w, all, jupiter, EARTH_N * 0.018, CALLISTO_RE, 26.4 * jupiter.rad, mode, plane);
        if (swarm) addMoonSwarm(w, all, jupiter, swarmCount(w, mode), plane);
        Body saturn = addPlanet(w, all, sun, EARTH_N * 95.2, SATURN_RE, 9.537 * au, phase(k++), plane);
        addMajorMoon(w, all, saturn, EARTH_N * 0.0225, TITAN_RE, 20.3 * saturn.rad, mode, plane);
        if (namedExtra) {
            addNamedMoonMaybeSubs(w, all, saturn, EARTH_N * 3.9e-4, ENCELADUS_RE, 4.0 * saturn.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, saturn, EARTH_N * 1.0e-3, TETHYS_RE, 4.9 * saturn.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, saturn, EARTH_N * 1.8e-3, DIONE_RE, 6.3 * saturn.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, saturn, EARTH_N * 3.9e-3, RHEA_RE, 8.7 * saturn.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, saturn, EARTH_N * 3.0e-3, IAPETUS_RE, 59.0 * saturn.rad, mode, plane);
        }
        if (swarm) addMoonSwarm(w, all, saturn, swarmCount(w, mode), plane);
        Body uranus = addPlanet(w, all, sun, EARTH_N * 14.5, URANUS_RE, 19.191 * au, phase(k++), plane);
        if (namedExtra) {
            addNamedMoonMaybeSubs(w, all, uranus, EARTH_N * 1.1e-4, MIRANDA_RE, 5.1 * uranus.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, uranus, EARTH_N * 2.3e-4, ARIEL_RE, 7.5 * uranus.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, uranus, EARTH_N * 2.0e-4, UMBRIEL_RE, 10.5 * uranus.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, uranus, EARTH_N * 5.9e-4, TITANIA_RE, 17.2 * uranus.rad, mode, plane);
            addNamedMoonMaybeSubs(w, all, uranus, EARTH_N * 5.0e-4, OBERON_RE, 23.0 * uranus.rad, mode, plane);
        }
        if (swarm) addMoonSwarm(w, all, uranus, swarmCount(w, mode), plane);
        Body neptune = addPlanet(w, all, sun, EARTH_N * 17.1, NEPTUNE_RE, 30.069 * au, phase(k), plane);
        addMajorMoon(w, all, neptune, EARTH_N * 0.0036, TRITON_RE, 14.4 * neptune.rad, mode, plane);
        if (namedExtra) {
            addMoon(w, all, neptune, EARTH_N * 5.0e-6, NEREID_RE, 222.0 * neptune.rad, plane);
        }
        if (swarm) addMoonSwarm(w, all, neptune, swarmCount(w, mode), plane);
        addBelt(w, all, sun, au, KUIPER_COUNT, KUIPER_AU0, KUIPER_AU1, plane);
        return all;
    }

    /**
     * Случайная звёздная система в том же астро-масштабе ({@link #AU_RE}): звезда + 4…12 планет
     * (массы/радиусы/полуоси разнятся), спутники по {@link World#moonsBeltsMode}, 0…2 пояса.
     * Иерархия STAR ≫ PLANET ≫ MOON ≫ ASTEROID; базовая плоскость случайна на набор,
     * плюс per-body наклон (как у SOLAR).
     */
    private static List<Body> randomSolarSystem(World w) {
        MoonsBeltsMode mode = w.moonsBeltsMode != null ? w.moonsBeltsMode : MOONS_BELTS_DEFAULT;
        boolean namedExtra = mode == MoonsBeltsMode.A || mode == MoonsBeltsMode.D;
        boolean swarm = mode == MoonsBeltsMode.B || mode == MoonsBeltsMode.D;
        double re = earthRadius(), au = AU_RE * re;
        double starMs = logUniform(w.nextU(), 0.25, 4.0); // доли M☉
        double starN = EARTH_N * 332_946 * starMs;
        double starRe = SUN_RE * Math.pow(starMs, 0.7) * (0.85 + 0.3 * w.nextU());
        starRe = clamp(starRe, 0.5 * SUN_RE, 3.0 * SUN_RE);
        Body sun = astro(starN, starRe, AstroClass.STAR, new double[3], new double[3]);
        List<Body> all = new ArrayList<>();
        all.add(sun);
        double[][] plane = randomOrbitPlane(w);

        int nPlanets = 4 + (int) (w.nextU() * 9); // 4…12
        double aMin = 0.25 + 0.35 * w.nextU();    // 0.25…0.6 а.е.
        double aMax = 22 + 28 * w.nextU();        // 22…50 а.е.
        double[] aAu = new double[nPlanets];
        for (int i = 0; i < nPlanets; i++) {
            double t = (i + 0.2 + 0.6 * w.nextU()) / Math.max(1, nPlanets);
            aAu[i] = aMin * Math.pow(aMax / aMin, Math.min(1, Math.max(0, t)));
        }
        java.util.Arrays.sort(aAu);
        for (int i = 1; i < nPlanets; i++) {
            if (aAu[i] < aAu[i - 1] * 1.22) aAu[i] = aAu[i - 1] * 1.22;
        }

        int frost = -1; // индекс первой «внешней» (гигантской) для пояса
        for (int i = 0; i < nPlanets; i++) {
            boolean giant = aAu[i] > 3.5 && w.nextU() < 0.62
                    || aAu[i] > 8 && w.nextU() < 0.82
                    || aAu[i] > 15;
            if (giant && frost < 0) frost = i;
            double nEarth, radRe;
            if (giant) {
                nEarth = logUniform(w.nextU(), 12, 380);
                radRe = lerp(4.0, 13.5, Math.min(1, (Math.log10(nEarth) - Math.log10(12))
                        / (Math.log10(380) - Math.log10(12))));
                radRe *= 0.9 + 0.25 * w.nextU();
            } else {
                nEarth = logUniform(w.nextU(), 0.04, 2.8);
                radRe = lerp(0.28, 1.55, Math.min(1, (Math.log10(nEarth) - Math.log10(0.04))
                        / (Math.log10(2.8) - Math.log10(0.04))));
                radRe *= 0.9 + 0.25 * w.nextU();
            }
            double ang = 2 * Math.PI * w.nextU();
            Body planet = addPlanet(w, all, sun, EARTH_N * nEarth, radRe, aAu[i] * au, ang, plane);
            // крупные/«именные» спутники
            int majorMax;
            if (giant) {
                majorMax = namedExtra ? 2 + (int) (w.nextU() * 4) : 1 + (int) (w.nextU() * 3);
            } else {
                majorMax = namedExtra
                        ? (w.nextU() < 0.55 ? 1 + (int) (w.nextU() * 2) : 0)
                        : (w.nextU() < 0.45 ? 1 + (int) (w.nextU() * 2) : 0);
            }
            for (int m = 0; m < majorMax; m++) {
                double share = logUniform(w.nextU(), 2e-5, giant ? 0.03 : 0.02);
                double moonN = Math.min(planet.N * share, EARTH_N * (giant ? 0.04 : 0.02));
                double moonRe = clamp(radRe * (0.12 + 0.28 * w.nextU()) * Math.pow(share / 0.01, 0.25),
                        0.08, Math.min(0.55, radRe * 0.45));
                double aMoon = planet.rad * (5 + 32 * w.nextU());
                if (largeMoon(moonN, moonRe))
                    addMajorMoon(w, all, planet, moonN, moonRe, aMoon, mode, plane);
                else
                    addMoon(w, all, planet, moonN, moonRe, aMoon, plane);
            }
            if (giant && swarm) addMoonSwarm(w, all, planet, swarmCount(w, mode), plane);
        }

        // внутренний пояс (если есть переход к гигантам) — плотнее в A/B/D
        if (frost > 0 && w.nextU() < 0.85) {
            double a0 = aAu[frost - 1] * 1.15;
            double a1 = aAu[frost] * 0.88;
            if (a1 > a0 * 1.05) {
                int n = 100 + (int) (w.nextU() * 220); // denser vs прежних 40…200
                addBelt(w, all, sun, au, n, a0, a1, plane);
            }
        }
        // внешний пояс за последней планетой
        if (w.nextU() < 0.9) {
            double a0 = aAu[nPlanets - 1] * (1.15 + 0.25 * w.nextU());
            double a1 = a0 * (1.35 + 0.55 * w.nextU());
            int n = 80 + (int) (w.nextU() * 200); // denser vs прежних 50…220
            addBelt(w, all, sun, au, n, a0, a1, plane);
        }
        return all;
    }

    /**
     * {@link #SOLAR_GROUP_MIN}…{@link #SOLAR_GROUP_MAX} случайных СС (каждая —
     * {@link #randomSolarSystem}), разнесённых в 3D так, чтобы упаковки почти не
     * пересекались. Режим спутников/поясов — тот же {@link World#moonsBeltsMode}.
     */
    private static List<Body> solarGroup(World w) {
        int n = SOLAR_GROUP_MIN + (int) (w.nextU() * (SOLAR_GROUP_MAX - SOLAR_GROUP_MIN + 1));
        List<List<Body>> systems = new ArrayList<>(n);
        double[] radii = new double[n];
        double maxR = 0;
        for (int i = 0; i < n; i++) {
            List<Body> sys = randomSolarSystem(w);
            systems.add(sys);
            radii[i] = packRadius(sys);
            maxR = Math.max(maxR, radii[i]);
        }
        double cell = Math.max(maxR * 2.4, AU_RE * earthRadius() * 60);
        double[][] centers = new double[n][3];
        for (int i = 0; i < n; i++) {
            boolean placed = false;
            for (int attempt = 0; attempt < 400 && !placed; attempt++) {
                double scale = cell * (0.3 + Math.cbrt(Math.max(1, n)) * Math.cbrt(w.nextU()));
                if (attempt > 80) scale *= 1.0 + 0.02 * (attempt - 80);
                double[] d = World.norm(w.randDir());
                double[] c = {d[0] * scale, d[1] * scale, d[2] * scale};
                boolean ok = true;
                for (int j = 0; j < i; j++) {
                    double minSep = radii[i] + radii[j] + 0.15 * maxR;
                    if (World.dist(c, centers[j]) < minSep) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    centers[i] = c;
                    placed = true;
                }
            }
            if (!placed) {
                double x = 0;
                for (int j = 0; j < i; j++) {
                    x = Math.max(x, centers[j][0] + radii[j]);
                }
                centers[i] = new double[]{x + radii[i] + 0.25 * maxR, 0, 0};
            }
            World.shiftPackTo(systems.get(i), centers[i]);
        }
        List<Body> all = new ArrayList<>();
        for (List<Body> sys : systems) all.addAll(sys);
        return all;
    }

    /**
     * Статистический рой мелких спутников вокруг гиганта: крошечные N (бюджет
     * {@link #SWARM_MASS_FRAC}·M_planet), полуоси в пределах ~3.5…60 R_planet.
     * Без субспутников (слишком мелки).
     */
    private static void addMoonSwarm(World w, List<Body> all, Body planet, int count, double[][] plane) {
        if (count <= 0) return;
        double budget = planet.N * SWARM_MASS_FRAC;
        double used = 0;
        for (int i = 0; i < count; i++) {
            double remain = Math.max(budget - used, budget * 1e-9);
            double share = logUniform(w.nextU(), 1e-12, 1e-9);
            double n = Math.min(planet.N * share, remain * (i + 1.0) / (count - i));
            n = Math.max(n, BELT_N_MIN * 3);
            if (used + n > budget) n = Math.max(BELT_N_MIN * 3, budget - used);
            used += n;
            double moonRe = clamp(lerp(8e-5, 0.06, Math.min(1,
                    (Math.log10(Math.max(n, 1)) - 4) / 8)), 5e-5, 0.08);
            double aMoon = planet.rad * (3.5 + 56.5 * w.nextU());
            addMoon(w, all, planet, n, moonRe, aMoon, plane);
        }
    }

    /** Крупная именная луна + опционально субспутники, если {@link #largeMoon}. */
    private static void addNamedMoonMaybeSubs(World w, List<Body> all, Body planet, double N,
                                              double radiusRe, double a, MoonsBeltsMode mode,
                                              double[][] plane) {
        if (largeMoon(N, radiusRe)) addMajorMoon(w, all, planet, N, radiusRe, a, mode, plane);
        else addMoon(w, all, planet, N, radiusRe, a, plane);
    }

    /** Крупный спутник + честные спутники спутников в доле сферы Хилла. */
    private static Body addMajorMoon(World w, List<Body> all, Body planet, double N, double radiusRe,
                                     double a, MoonsBeltsMode mode, double[][] plane) {
        Body moon = addMoon(w, all, planet, N, radiusRe, a, plane);
        addSubsatellites(w, all, moon, planet, a, mode, plane);
        return moon;
    }

    /**
     * Честные N-body субспутники: круговые орбиты вокруг луны внутри
     * {@link #SUBSAT_HILL_FRAC}·r_Hill; масса суммарно ≤ {@link #SUBSAT_MASS_FRAC}·M_moon.
     */
    private static void addSubsatellites(World w, List<Body> all, Body moon, Body planet,
                                         double aMoon, MoonsBeltsMode mode, double[][] plane) {
        if (moon == null || planet == null || planet.N <= 0 || moon.N <= 0) return;
        double rHill = aMoon * Math.cbrt(moon.N / (3.0 * planet.N));
        double aMax = SUBSAT_HILL_FRAC * rHill;
        double aMin = Math.max(2.2 * moon.rad, 0.05 * rHill);
        if (!(aMax > aMin * 1.05)) return; // Хилл слишком тесный
        int count = subsatCount(w, mode);
        double budget = moon.N * SUBSAT_MASS_FRAC;
        double used = 0;
        for (int i = 0; i < count; i++) {
            double remain = Math.max(budget - used, budget * 1e-9);
            double share = logUniform(w.nextU(), 1e-10, 1e-7);
            double n = Math.min(moon.N * share, remain * (i + 1.0) / Math.max(1, count - i));
            n = Math.max(n, BELT_N_MIN);
            if (used + n > budget) n = Math.max(BELT_N_MIN, budget - used);
            used += n;
            double subRe = clamp(lerp(3e-5, 0.02, Math.min(1,
                    (Math.log10(Math.max(n, 1)) - 3) / 7)), 2e-5, 0.04);
            double aSub = aMin + (aMax - aMin) * w.nextU();
            addMoon(w, all, moon, n, subRe, aSub, plane); // централ = луна
        }
    }

    /**
     * Случайная ортонормаль плоскости орбит: {@code [ex, ey, n]} (n = ex×ey).
     * Каждый вызов SOLAR / RANDOM_SOLAR — своя базовая (pack) плоскость.
     */
    private static double[][] randomOrbitPlane(World w) {
        double[] n = World.norm(w.randDir());
        double[] ex = World.norm(World.cross(n, w.randDir()));
        if (ex[0] == 0 && ex[1] == 0 && ex[2] == 0) {
            ex = World.norm(World.cross(n, new double[]{1, 0, 0}));
            if (ex[0] == 0 && ex[1] == 0 && ex[2] == 0)
                ex = World.norm(World.cross(n, new double[]{0, 1, 0}));
        }
        double[] ey = World.norm(World.cross(n, ex));
        w.setPackNormal(n[0], n[1], n[2]); // запасной экватор для Option B (нет spin axis)
        return new double[][]{ex, ey, n};
    }

    /**
     * Наклон pack-плоскости на случайный i ∈ [−iMax, iMax] вокруг случайной линии узлов.
     * Возвращает новую ортонормаль {@code [ex, ey, n]}; при |i|≈0 — копия pack.
     */
    private static double[][] inclinedPlane(World w, double[][] pack, double iMax) {
        double i = (2 * w.nextU() - 1) * iMax;
        double[] ex = pack[0], ey = pack[1], n = pack[2];
        if (Math.abs(i) < 1e-15) {
            return new double[][]{ex.clone(), ey.clone(), n.clone()};
        }
        double Omega = 2 * Math.PI * w.nextU();
        double cO = Math.cos(Omega), sO = Math.sin(Omega);
        double[] node = {
                cO * ex[0] + sO * ey[0],
                cO * ex[1] + sO * ey[1],
                cO * ex[2] + sO * ey[2]
        };
        double[] nxn = World.cross(node, n);
        double ci = Math.cos(i), si = Math.sin(i);
        double[] np = World.norm(new double[]{
                ci * n[0] + si * nxn[0],
                ci * n[1] + si * nxn[1],
                ci * n[2] + si * nxn[2]
        });
        double[] exp = World.norm(node);
        double[] eyp = World.norm(World.cross(np, exp));
        return new double[][]{exp, eyp, np};
    }

    /** Радиус-вектор и касательная в плоскости {@code plane} при фазе {@code ang}. */
    private static double[][] orbitAxes(double[][] plane, double ang) {
        double[] ex = plane[0], ey = plane[1];
        double c = Math.cos(ang), s = Math.sin(ang);
        double[] u = {c * ex[0] + s * ey[0], c * ex[1] + s * ey[1], c * ex[2] + s * ey[2]};
        double[] t = {-s * ex[0] + c * ey[0], -s * ex[1] + c * ey[1], -s * ex[2] + c * ey[2]};
        return new double[][]{u, t};
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.min(hi, Math.max(lo, x));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Кольцо астероидов на круговых орбитах вокруг Солнца (Солнце не получает отдачу). */
    private static void addBelt(World w, List<Body> all, Body sun, double auUnit, int count,
                                double a0Au, double a1Au, double[][] plane) {
        for (int i = 0; i < count; i++) {
            double a = (a0Au + (a1Au - a0Au) * w.nextU()) * auUnit;
            double ang = 2 * Math.PI * w.nextU();
            addBeltRock(w, all, sun, beltMass(w.nextU()), a, ang, plane);
        }
    }

    private static void addBeltRock(World w, List<Body> all, Body sun, double N, double a,
                                    double ang, double[][] plane) {
        double rad = rockRadius(N, BELT_N_MIN, BELT_N_MAX, BELT_RE_MIN, BELT_RE_MAX);
        Body rock = new Body(Species.CUSTOM, N, rad, new double[3], new double[3]);
        rock.astro = AstroClass.ASTEROID;
        List<Body> moving = new ArrayList<>(List.of(rock));
        List<Body> central = new ArrayList<>(List.of(sun));
        double[][] ut = orbitAxes(inclinedPlane(w, plane, BELT_I_MAX), ang);
        double[] sp = sun.p.clone(), sv = sun.v.clone();
        w.circularOrbit(moving, central, a, ut[0], ut[1]);
        System.arraycopy(sp, 0, sun.p, 0, 3);
        System.arraycopy(sv, 0, sun.v, 0, 3);
        all.add(rock);
    }

    private static double phase(int i) {
        return i * 0.7;
    }

    private static Body addPlanet(World w, List<Body> all, Body sun, double N, double radiusRe,
                                  double a, double ang, double[][] plane) {
        Body p = astro(N, radiusRe, AstroClass.PLANET, new double[3], new double[3]);
        List<Body> moving = new ArrayList<>(List.of(p));
        List<Body> central = new ArrayList<>(List.of(sun));
        double[][] ut = orbitAxes(inclinedPlane(w, plane, PLANET_I_MAX), ang);
        w.circularOrbit(moving, central, a, ut[0], ut[1]);
        all.add(p);
        return p;
    }

    private static Body addMoon(World w, List<Body> all, Body planet, double N, double radiusRe,
                                double a, double[][] plane) {
        Body m = astro(N, radiusRe, AstroClass.MOON, new double[3], new double[3]);
        List<Body> moving = new ArrayList<>(List.of(m));
        List<Body> central = new ArrayList<>(List.of(planet));
        double[][] orb = inclinedPlane(w, plane, MOON_I_MAX);
        double[] radial = {planet.p[0], planet.p[1], planet.p[2]};
        if (radial[0] == 0 && radial[1] == 0 && radial[2] == 0) radial = orb[0].clone();
        double[] u = World.norm(radial);
        double[] t = World.norm(World.cross(orb[2], u)); // касательная в наклонённой плоскости
        if (t[0] == 0 && t[1] == 0 && t[2] == 0) t = orb[1].clone();
        w.circularOrbit(moving, central, a, u, t);
        all.add(m);
        return m;
    }

    /**
     * Облако своих тел с астероидными массами (непрерывный диапазон, без пула p/n/e/атом/диполь).
     * Число — {@link World#fragmentCount}. Центр — COM текущих тел; радиус облака растёт с сценой.
     */
    private static List<Body> fragments(World w) {
        int n = Math.max(1, Math.min(50_000, w.fragmentCount));
        List<Body> built = new ArrayList<>(n);
        double[] c = w.parts.isEmpty() ? new double[3] : w.centerOfMass();
        double span = 4;
        for (Body b : w.parts) span = Math.max(span, World.dist(b.p, c) + b.rad);
        double cloudR = Math.max(2.8 * Math.cbrt(n), span * 0.12);
        double[] zero = {0, 0, 0};
        for (int i = 0; i < n; i++) {
            double[] d = w.randDir();
            double r = cloudR * Math.cbrt(w.nextU()); // равномерно по объёму шара
            double[] p = {c[0] + d[0] * r, c[1] + d[1] * r, c[2] + d[2] * r};
            double N = asteroidMass(w.nextU());
            double rad = rockRadius(N, ASTEROID_N_MIN, ASTEROID_N_MAX, FRAG_RE_MIN, FRAG_RE_MAX);
            Body rock = new Body(Species.CUSTOM, N, rad, p, zero);
            rock.astro = AstroClass.ASTEROID;
            built.add(rock);
        }
        return built;
    }
}
