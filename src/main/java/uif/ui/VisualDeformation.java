package uif.ui;

/** Smoothed world-space axis; projection is evaluated independently for each camera. */
final class VisualDeformation {
    static final double MAX_ASPECT = 1.08;
    static final double RESPONSE_SECONDS = 0.18;
    static final double[] IDENTITY = {1, 0, 1, 1};
    private double x = 1, y, z, strength;

    void update(double ax, double ay, double az, double target, double dt) {
        double length = Math.hypot(ax, Math.hypot(ay, az));
        if (!Double.isFinite(length) || length <= 1e-20 || !Double.isFinite(target)) target = 0;
        target = Math.max(0, Math.min(1, target));
        if (!(dt > 0) || !Double.isFinite(dt)) return;
        double blend = -Math.expm1(-Math.min(dt, 0.1) / RESPONSE_SECONDS);
        if (target > 0) {
            ax /= length; ay /= length; az /= length;
            if (strength < 1e-8) { x = ax; y = ay; z = az; }
            else {
                // An axis has no sign: opposite vectors describe the same silhouette.
                if (x * ax + y * ay + z * az < 0) { ax = -ax; ay = -ay; az = -az; }
                x += blend * (ax - x); y += blend * (ay - y); z += blend * (az - z);
                double norm = Math.hypot(x, Math.hypot(y, z));
                x /= norm; y /= norm; z /= norm;
            }
        }
        strength += blend * (target - strength);
    }

    double[] axis() { return strength > 1e-6 ? new double[]{x, y, z} : null; }

    double[] project(Camera camera) {
        if (!(strength > 1e-8)) return IDENTITY;
        double[] direction = camera.turn(x, y, z);
        double sx = direction[0], sy = -direction[1];
        double projectedSquared = Math.max(0, Math.min(1, sx * sx + sy * sy));
        // Face-on is circular; the full small flattening is visible only from the side.
        double aspect = 1 + (MAX_ASPECT - 1) * strength * projectedSquared;
        if (aspect - 1 < 1e-8) return IDENTITY;
        double length = Math.sqrt(projectedSquared), scale = Math.sqrt(aspect);
        return new double[]{sx / length, sy / length, 1 / scale, scale};
    }

    static double tidalStrength(double angularSize) {
        if (!(angularSize > 0)) return 0;
        // At the previous saturation reference (0.016 rad), only 20% of the visual limit.
        return 1 - 1 / (1 + angularSize / (4 * 0.016));
    }
}
