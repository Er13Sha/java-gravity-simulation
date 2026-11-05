package uif;

/**
 * Константы и законы модели W = Q·N (Катющик).
 * W = Q·N, Q = k_a·C1/(4πR²), N = V·q = h·S·q, F = f(α)·n_f·W, n_f = k_v·h·S·q.
 * Для тяготения k_a = k_v = f(α) = 1, C1 = 4πG ⇒ F = G·m1·m2/R².
 */
public final class Formula {
    private Formula() {}

    public static final double K_A = 1, K_V = 1, F_ALPHA = 1;

    // В модели только частицы, доказанные напрямую и с измеренной массой: p, n, e.
    public static final double M_P = 1836, M_N = 1839, M_E = 1;
    public static final double MEV = 0.511; // mₑ в МэВ

    // Частица — сфера радиуса rad: S = πr², h = V/S = 4r/3, V = hS, N = Vq.
    // Плотность q одна для всех (калибрована по N(e) = 1), радиус — из N = V·q.
    public static final double RAD_E = 0.01;
    public static final double Q_DENS = M_E / sphereV(RAD_E);
    public static final double RAD_P = radOf(M_P);
    public static final double RAD_N = radOf(M_N);

    // Точек в следе частицы (не чаще одной за TRAIL_DT модельного времени — это и наибольший шаг,
    // так что след ~TRAIL_LEN·TRAIL_DT ≈ 19 с при любом шаге). Яркость падает линейно с возрастом точки.
    public static final int TRAIL_LEN = 2 * 3 * 375;
    public static final double TRAIL_DT = 1.0 / 120;
    // В UI след — у всех тел (trailSelect = true). У каждого следа ~54 КБ.
    // null trailSelect (тесты) — первые TRAIL_BODIES и все электроны.
    public static final int TRAIL_BODIES = 200;

    public static double sphereV(double r) {
        return 4.0 / 3 * Math.PI * r * r * r;
    }

    public static double radOf(double N) {
        return Math.cbrt(N / (Q_DENS * 4.0 / 3 * Math.PI));
    }

    /** Потенциал пары (на kQ·N_i·N_j), согласованный с QN: −dφ/dr = QN/(kQ·N_j). */
    public static double phi(double r, double R) {
        return r >= R ? 1 / r : (3 * R * R - r * r) / (2 * R * R * R);
    }
}
