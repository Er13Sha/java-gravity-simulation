package uif.ui;

/**
 * Шейдеры рисуют там же, где Java2D: тело — в точке Camera.project (строки сверху вниз, не
 * перевёрнуты), ядро непрозрачное, свечение полупрозрачное и гаснет к краю; след проецирует
 * видеокарта — его точки ложатся на Camera.project. Нет OpenGL — тест пропускается.
 */
public final class GlSceneTest {
    private static int checks;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static int a(int p) { return p >>> 24; }
    private static int r(int p) { return (p >> 16) & 255; }
    private static int g(int p) { return (p >> 8) & 255; }
    private static int b(int p) { return p & 255; }

    public static void main(String[] args) throws Throwable {
        GlScene gl = GlScene.get();
        if (gl == null) {
            System.out.println("GlSceneTest: OpenGL нет — пропуск (" + GlScene.failure() + ")");
            return;
        }
        int W = 320, H = 200;
        Camera cam = new Camera();
        cam.width = W;
        cam.height = H;
        cam.yaw = 0.7;
        cam.pitch = 0.3;
        cam.camR = 5;
        cam.focal = 150;
        cam.tx = 0.2;
        cam.ty = -0.1;
        cam.tz = 0.3;
        // тело в левой верхней четверти
        double[] P = {-1.0, 1.0, 0.0};
        double[] sc = cam.project(P);
        check(sc != null && sc[0] > 20 && sc[1] > 20 && sc[0] < W / 2.0 && sc[1] < H / 2.0, "тело в левой верхней четверти: " + java.util.Arrays.toString(sc));
        gl.beginBodies(1);
        gl.body(sc[0], sc[1], 6, 14, 0xFF0000, 0.6, 0x00FF00, false);
        // след: прямой отрезок справа внизу
        double[] A = {0.8, -0.6, 0.4}, B = {1.6, -0.9, -0.5};
        gl.beginTrails(2, 1);
        gl.trail(2);
        gl.point(A[0] - cam.tx, A[1] - cam.ty, A[2] - cam.tz, 1, 0x0000FF);
        gl.point(B[0] - cam.tx, B[1] - cam.ty, B[2] - cam.tz, 1, 0x0000FF);
        int[] px = new int[W * H];
        gl.render(W, H, px, Math.cos(cam.yaw), Math.sin(cam.yaw), Math.cos(cam.pitch), Math.sin(cam.pitch), cam.focal, cam.camR,
                1e6, 1, 3f);

        int cx = (int) sc[0], cy = (int) sc[1];
        int c = px[cy * W + cx];
        check(a(c) == 255 && g(c) > 240 && r(c) < 10, "в центре тела — непрозрачное ядро: " + Integer.toHexString(c));
        int h = px[cy * W + cx + 10]; // за ядром (6), в свечении (14)
        check(a(h) > 10 && a(h) < 200 && r(h) > 0 && g(h) == 0, "в свечении — красное полупрозрачное: " + Integer.toHexString(h));
        check(px[cy * W + cx + 16] == 0 && px[(cy + 16) * W + cx] == 0, "за краем свечения — пусто");
        check(px[(H - 1 - cy) * W + cx] == 0, "строки не перевёрнуты: в зеркальной точке пусто");

        // точки следа: проекция середины отрезка (перспектива — по экранной прямой между концами)
        double[] sa = cam.project(A), sb = cam.project(B);
        int hits = 0, tested = 0;
        for (double t = 0.1; t <= 0.9; t += 0.1) {
            double x = sa[0] + (sb[0] - sa[0]) * t, y = sa[1] + (sb[1] - sa[1]) * t;
            int best = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int q = px[((int) y + dy) * W + (int) x + dx];
                    best = Math.max(best, a(q) > 0 && b(q) > r(q) ? a(q) : 0);
                }
            }
            tested++;
            if (best > 20) hits++;
        }
        check(hits == tested, "след ложится на Camera.project: " + hits + " / " + tested);
        int empty = px[(int) ((sa[1] + sb[1]) / 2 - 25) * W + (int) ((sa[0] + sb[0]) / 2)];
        check(empty == 0, "в стороне от следа — пусто");

        // точка за камерой: отрезок обрезается ближней плоскостью, кадр не ломается
        gl.beginBodies(0);
        gl.beginTrails(2, 1);
        gl.trail(2);
        gl.point(0.5 * Math.cos(cam.yaw), 0, -0.5 * Math.sin(cam.yaw), 1, 0xFFFFFF); // вправо от цели: отрезок не вдоль оси взгляда
        double cp = Math.cos(cam.pitch), k = -50; // ось взгляда в мире: (cos p·sin y, sin p, cos p·cos y); −50 — за камерой
        gl.point(k * cp * Math.sin(cam.yaw), k * Math.sin(cam.pitch), k * cp * Math.cos(cam.yaw), 1, 0xFFFFFF);
        check(cam.project(k * cp * Math.sin(cam.yaw) + cam.tx, k * Math.sin(cam.pitch) + cam.ty, k * cp * Math.cos(cam.yaw) + cam.tz) == null,
                "вторая точка — за камерой");
        gl.render(W, H, px, Math.cos(cam.yaw), Math.sin(cam.yaw), Math.cos(cam.pitch), Math.sin(cam.pitch), cam.focal, cam.camR,
                1e6, 1, 1f);
        int lit = 0;
        for (int p : px) if (p != 0) lit++;
        check(lit > 0 && lit < W * H / 4, "отрезок через ближнюю плоскость обрезан: " + lit + " пикселей");
        java.lang.reflect.Field pixelField=GlScene.class.getDeclaredField("pixels"); pixelField.setAccessible(true);
        java.lang.foreign.MemorySegment before=(java.lang.foreign.MemorySegment)pixelField.get(gl);
        gl.beginBodies(0); gl.beginTrails(0,0);
        gl.render(W-1,H,new int[(W-1)*H],1,0,1,0,150,5,1e6,1,1f);
        check(before==pixelField.get(gl),"уменьшение кадра переиспользует native-буфер");
        gl.render(W*2,H*2,new int[W*H*4],1,0,1,0,150,5,1e6,1,1f);
        check(!before.scope().isAlive(),"рост кадра освобождает предыдущую арену пикселей");
        check(((java.lang.foreign.MemorySegment)pixelField.get(gl)).scope().isAlive(),"новый буфер пикселей доступен");
        System.out.println("GlSceneTest: " + checks + " проверок пройдено (" + GlScene.status() + ").");
    }
}
