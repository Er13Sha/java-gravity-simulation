package uif.ui;

import java.util.Set;

/** Перспективная камера: вращение yaw/pitch вокруг цели, цель плавно следует за центром масс. */
final class Camera {
    double focal = 620, camR = 6.2, yaw = 0.5, pitch = 0.28;
    double tx, ty, tz;   // цель (центр масс + смещение)
    double ox, oy, oz;   // смещение клавишами W/QE
    double speed = 4;
    /** Множитель Shift-ускорения полёта (W/Q/E). */
    static final double BOOST = 3;
    /** Целевое расстояние при плавном приближении к телу; NaN — нет анимации. */
    private double zoomTo = Double.NaN;
    /**
     * Пределы масштаба и ближняя плоскость. MIN_R не крошечный: иначе колесо/W
     * почти не отпускают от тела; MAX_R большой под астро-пресеты (R⊕ × а.е.).
     */
    static final double MIN_R = 0.1, MAX_R = 1e12, NEAR = 0.005;
    /** Ниже этого camR отдаление колесом и полёт W ускоряются, чтобы легко выйти. */
    private static final double CLOSE_R = 3;
    int width, height;

    /** Экранные x, y, глубина и масштаб s = focal/z; null — точка за камерой. */
    double[] project(double x, double y, double z) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double px = x - tx, py = y - ty, pz = z - tz;
        double x1 = px * cy - pz * sy;
        double z1 = px * sy + pz * cy;
        double y2 = py * cp - z1 * sp;
        double z2 = py * sp + z1 * cp;
        double zc = z2 + camR;
        if (zc < NEAR) return null;
        double s = focal / zc;
        return new double[]{width / 2.0 + x1 * s, height / 2.0 - y2 * s, zc, s};
    }

    /** Только поворот направления (без сдвига и перспективы): экранные x, y (вверх) и глубина. */
    double[] turn(double x, double y, double z) {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double x1 = x * cy - z * sy, z1 = x * sy + z * cy;
        return new double[]{x1, y * cp - z1 * sp, y * sp + z1 * cp};
    }

    double[] project(double[] p) {
        return project(p[0], p[1], p[2]);
    }

    void rotate(double dx, double dy) {
        yaw += dx * 0.006;
        pitch = Math.max(-1.4, Math.min(1.4, pitch + dy * 0.006));
    }

    /** Направление взгляда в мире (единичный): из глаза к цели. */
    double[] forward() {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        return new double[]{cp * sy, sp, cp * cy};
    }

    /** Положение камеры (глаз) в мире. */
    double[] eye() {
        double[] f = forward();
        return new double[]{tx - f[0] * camR, ty - f[1] * camR, tz - f[2] * camR};
    }

    /**
     * Колесо: шаг ~15 %. Вблизи ({@code camR < CLOSE_R}) отдаление сильнее (больше
     * показатель + небольшой аддитивный толчок), чтобы легко выйти из «залипания».
     * Любое колесо сбрасывает анимацию {@link #focusOn}.
     */
    void zoom(double notches) {
        if (notches == 0) return;
        zoomTo = Double.NaN;
        double exp = notches;
        double kick = 0;
        if (notches > 0 && camR < CLOSE_R) {
            double u = (CLOSE_R - camR) / CLOSE_R; // 0…1 у пола
            exp = notches * (1 + 2 * u);           // до ×3 щелчков на отдаление
            kick = 0.35 * u * notches;            // плюс абсолютный шаг у самого пола
        }
        camR = Math.max(MIN_R, Math.min(MAX_R, camR * Math.pow(1.15, exp) + kick));
    }

    /**
     * W — тяга вдоль полного направления взгляда (yaw и pitch), Q/E — мир вверх/вниз;
     * Shift — ускорение ({@link #BOOST}). Цель: при {@code lockFollow} — жёстко на теле
     * (без лага вблизи); иначе плавно к COM. {@code dt} режется, чтобы кадр-лаг не дёргал.
     */
    void update(Set<Character> keys, double[] follow, double dt, boolean boost, boolean lockFollow) {
        double cdt = Math.min(Math.max(0, dt), 0.05); // hitch → не рывок цели/зума
        boolean fwd = keys.contains('w');
        int up = (keys.contains('e') ? 1 : 0) - (keys.contains('q') ? 1 : 0);
        if (fwd || up != 0) {
            zoomTo = Double.NaN; // ручной полёт отменяет focusOn
            // вдали — быстрее; вблизи не ползти: пол скорости выше прежних 0.05
            double near = camR < CLOSE_R ? 0.55 : 0.35;
            double step = speed * (boost ? BOOST : 1) * cdt * Math.max(near, camR / 6.2);
            if (fwd) {
                // взгляд камеры в мир: из project/eye → (cp·sy, sp, cp·cy)
                double cy = Math.cos(yaw), sy = Math.sin(yaw);
                double cp = Math.cos(pitch), sp = Math.sin(pitch);
                ox += cp * sy * step;
                oy += sp * step;
                oz += cp * cy * step;
            }
            oy += up * step;
        }
        double wantX = follow[0] + ox, wantY = follow[1] + oy, wantZ = follow[2] + oz;
        double dx = wantX - tx, dy = wantY - ty, dz = wantZ - tz;
        double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (lockFollow) {
            // клик по телу: пока далеко — быстрый доезд; рядом — жёсткий lock (без лага при zoom-in)
            if (err > Math.max(0.5, 0.5 * camR)) {
                double kf = 1 - Math.exp(-cdt * 10);
                tx += dx * kf;
                ty += dy * kf;
                tz += dz * kf;
            } else {
                tx = wantX;
                ty = wantY;
                tz = wantZ;
            }
        } else {
            // COM: вблизи жёстче (экранный лаг не растёт при zoom-in); dt clamp — hitch не дёргает
            double rate = Math.max(3.0, 12.0 / Math.max(camR, 0.35));
            double kf = 1 - Math.exp(-cdt * rate);
            tx += dx * kf;
            ty += dy * kf;
            tz += dz * kf;
        }
        if (!Double.isNaN(zoomTo)) {
            double zk = 1 - Math.exp(-cdt * 4);
            camR += (zoomTo - camR) * zk;
            if (Math.abs(camR - zoomTo) < 1e-3 * Math.max(1, zoomTo)) zoomTo = Double.NaN;
        }
    }

    /** Совместимость: без lock — плавный follow (COM). */
    void update(Set<Character> keys, double[] follow, double dt, boolean boost) {
        update(keys, follow, dt, boost, false);
    }

    /**
     * Плавное приближение к выбранному телу: сброс смещения полёта (цель дальше задаёт App
     * как позицию тела) и анимация {@link #camR} к расстоянию по радиусу тела.
     */
    void focusOn(double rad) {
        ox = oy = oz = 0;
        zoomTo = Math.max(MIN_R * 2, Math.min(MAX_R, Math.max(0.35, rad * 28)));
    }

    /** Вид — как при запуске: расстояние, поворот и наклон по умолчанию, без смещения. */
    void defaults() {
        camR = 6.2;
        yaw = 0.5;
        pitch = 0.28;
        zoomTo = Double.NaN;
        resetOffset();
    }

    void resetOffset() {
        ox = oy = oz = 0;
        zoomTo = Double.NaN;
    }
}
