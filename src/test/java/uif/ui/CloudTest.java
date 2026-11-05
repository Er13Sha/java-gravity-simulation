package uif.ui;

import uif.Body;
import uif.Species;
import uif.World;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Частица полем (кнопка «поле» в теме «Вид»): облако светится своим полем Q·N — у протона в √1836 раз
 * больше, чем у электрона, — к краю гаснет, а в плотной сцене яркость насыщается, а не выжигает кадр.
 * Рисуется тем же мягким слоем, что и при шейдерах, поэтому проверяется Java2D без OpenGL.
 * Фон сцены — не чёрный ({@link Theme#BG}), поэтому всё сравнивается с ним, а не с чернотой.
 */
public final class CloudTest {
    private static int checks;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static int maxChannel(int p) {
        return Math.max(Math.max((p >> 16) & 255, (p >> 8) & 255), p & 255);
    }

    /** Наибольшая разница каналов: так проверяется, что цвета частиц разные. */
    private static int colorDist(int a, int b) {
        int d = 0;
        for (int sh = 16; sh >= 0; sh -= 8) d = Math.max(d, Math.abs(((a >> sh) & 255) - ((b >> sh) & 255)));
        return d;
    }

    /** Суммарное отличие пикселя от фона сцены: 0 — чистый фон. */
    private static int delta(int p, int bg) {
        int d = 0;
        for (int sh = 16; sh >= 0; sh -= 8) d += Math.abs(((p >> sh) & 255) - ((bg >> sh) & 255));
        return d;
    }

    private static BufferedImage paint(ScenePanel panel, int w, int h) {
        panel.setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
        return img;
    }

    /** Фон — правый нижний угол кадра: там нет ни сводки, ни осей. */
    private static int background(BufferedImage img) {
        return img.getRGB(img.getWidth() - 1, img.getHeight() - 1);
    }

    /** Сколько точек кадра отличается от фона: так сравнивается площадь облака. */
    private static int litCount(BufferedImage img) {
        int bg = background(img), n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if (delta(img.getRGB(x, y), bg) > 12) n++;
        }
        return n;
    }

    private static ScenePanel panel(World world, Camera cam) {
        Settings s = new Settings();
        s.bodyField = true;
        s.shaders = false; // облака — общий слой: при шейдерах рисует тот же drawClouds
        s.showField = false;
        s.showGround = false;
        s.showTrail = false;
        s.showForces = false;
        return new ScenePanel(world, cam, s);
    }

    public static void main(String[] args) {
        int W = 900, H = 700;

        // 1. Атом: облако протона видно и гаснет к краю
        World world = new World(new java.util.Random(1));
        world.setG(1e-5);
        world.setScene(World.Scene.ATOMS);
        Camera cam = new Camera();
        ScenePanel panel = panel(world, cam);
        BufferedImage img = paint(panel, W, H);
        int bg = background(img);
        Body proton = null;
        for (Body b : world.parts) if (b.kind == Species.P) { proton = b; break; }
        check(proton != null, "в сцене есть протон");
        double[] ps = cam.project(proton.p);
        check(ps != null, "протон в кадре");
        int cx = (int) ps[0], cy = (int) ps[1];
        int at = delta(img.getRGB(cx, cy), bg);
        int far = delta(img.getRGB(Math.max(0, Math.min(W - 1, cx + (int) (10 * ps[3]))), cy), bg);
        check(at > 60, "центр облака протона отличается от фона: " + at);
        check(at > far + 20, "облако гаснет к краю: " + at + " → " + far);
        check(maxChannel(img.getRGB(cx, cy)) < 250, "облако не белое: " + Integer.toHexString(img.getRGB(cx, cy)));
        // центр (яркая точка) должен быть ярче ореола на том же теле: точка ~30 px, ореол — сотни
        int halo = 0;
        for (int d = 30; d <= 90; d += 10) {
            halo = Math.max(halo, delta(img.getRGB(Math.min(W - 1, cx + d), cy), bg));
        }
        check(at > halo + 20, "центр-точка ярче ореола: " + at + " против " + halo);

        // 2. Цвет — по виду частицы: протон, нейтрон и электрон отличаются
        Body neutron = null, electron = null;
        for (Body b : world.parts) {
            if (b.kind == Species.N) neutron = b;
            if (b.kind == Species.E) electron = b;
        }
        check(neutron != null && electron != null, "в сцене есть нейтрон и электрон");
        int pc = panel.bodyColor(proton), nc = panel.bodyColor(neutron), ec = panel.bodyColor(electron);
        check(colorDist(pc, nc) > 30 && colorDist(pc, ec) > 30 && colorDist(nc, ec) > 30,
                "p, n и e разного цвета: " + Integer.toHexString(pc) + ", " + Integer.toHexString(nc) + ", " + Integer.toHexString(ec));

        // 3. Размер облака — из Q·N: у протона оно на порядки больше, чем у электрона
        world.parts.clear();
        world.addProton();
        int litProton = litCount(paint(panel, W, H));
        world.parts.clear();
        world.addElectron();
        int litElectron = litCount(paint(panel, W, H));
        check(litElectron > 0, "облако электрона видно: " + litElectron + " точек");
        check(litProton > 10 * litElectron, "облако протона в √1836 раз больше: " + litProton + " против " + litElectron);

        // 4. Плотная сцена: точки и ореолы видны, но кадр не выжигается в белый (подписей при >100 тел нет)
        world.parts.clear();
        for (int i = 0; i < 120; i++) world.addProton();
        BufferedImage dense = paint(panel, W, H);
        int denseBg = background(dense), worst = 0, lit = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = dense.getRGB(x, y);
                worst = Math.max(worst, maxChannel(p));
                if (delta(p, denseBg) > 20) lit++;
            }
        }
        check(worst < 250, "120 протонов: ни один канал не в насыщении — " + worst);
        check(lit > W * H / 20, "120 протонов: точки и ореолы видны — " + lit + " точек");

        // 5. Ограничения в 500 тел больше нет: облака частиц и слой поля рисуются и при 501 теле.
        world.parts.clear();
        for (int i = 0; i < 501; i++) world.addProton();
        BufferedImage many = paint(panel, W, H);
        int manyBg = background(many), plain = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) if (delta(many.getRGB(x, y), manyBg) <= 12) plain++;
        }
        check(plain < W * H / 2, "501 тело: облака частиц рисуются, а не шары (фона меньше половины): " + plain);

        Settings fs = new Settings();
        fs.bodyField = false;
        fs.showField = true;
        fs.shaders = false;
        fs.showGround = false;
        fs.showTrail = false;
        fs.showForces = false;
        BufferedImage fieldImg = paint(new ScenePanel(world, cam, fs), W, H);
        int fieldBg = background(fieldImg), litField = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) if (delta(fieldImg.getRGB(x, y), fieldBg) > 12) litField++;
        }
        check(litField > W * H / 20, "501 тело: слой поля рисуется: " + litField);

        System.out.println("CloudTest: " + checks + " проверок пройдено.");
    }
}
