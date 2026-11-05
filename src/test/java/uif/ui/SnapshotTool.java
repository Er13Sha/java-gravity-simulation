package uif.ui;

import uif.World;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Снимок окна без ручного запуска:
 * java -cp build/classes uif.ui.SnapshotTool out.png [atoms|sandbox|chamber] [секунды].
 * Прогоняет кадры с шагом 1/60 с при G = 1e-5 и сохраняет окно в PNG.
 */
public final class SnapshotTool {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "build/snapshot.png";
        String mode = args.length > 1 ? args[1] : "atoms";
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 5;
        SwingUtilities.invokeAndWait(() -> {
            try {
                App app = new App();
                JFrame frame = app.build();
                frame.setSize(1360, 860);
                frame.setVisible(true);
                World w = app.world();
                w.setG(1e-5);
                app.settings().shaders = !"false".equals(System.getProperty("shaders")); // -Dshaders=false — Java2D
                app.settings().bodyField = Boolean.getBoolean("clouds"); // -Dclouds=true — частицы полем
                String kuo = System.getProperty("kuo"); // -Dkuo=β_т или -Dkuo=β_т:β_к — комплексное тяготение
                if (kuo != null) {
                    String[] b = kuo.split(":");
                    w.setMode(World.Mode.KUO);
                    w.kuo.beta = Double.parseDouble(b[0]);
                    if (b.length > 1) w.kuo.setBetaK(Double.parseDouble(b[1]));
                }
                boolean sandbox = mode.equals("sandbox") || mode.equals("chamber");
                if (mode.startsWith("big:")) { // big:N — песочница из N тел (протон и электроны на орбитах)
                    int n = Integer.parseInt(mode.substring(4));
                    w.setScene(World.Scene.SANDBOX);
                    app.camera().camR = 6;
                    w.addProton();
                    for (int i = 1; i < n; i++) {
                        if (i % 10 == 0) w.addProton();
                        else w.addElectron();
                    }
                    app.settings().showField = !"false".equals(System.getProperty("field"));
                    String dev = System.getProperty("device"); // -Ddevice=GPU_FLOAT и т. п.
                    if (dev != null) w.forces.device = uif.Forces.Device.valueOf(dev);
                    double t0model = w.time;
                    long t0 = System.nanoTime();
                    int frames = 0;
                    while (frames < 10 || System.nanoTime() - t0 < 3_000_000_000L) {
                        app.frame();
                        frames++;
                    }
                    double sec = (System.nanoTime() - t0) / 1e9;
                    System.out.printf("big:%d — %.1f мс на кадр (%d кадров), модель шла ×%.2f от реального времени, расчёт: %s%n", n,
                            sec * 1e3 / frames, frames, (w.time - t0model) / sec, w.forces.lastPath);
                } else if (sandbox) {
                    w.setScene(World.Scene.SANDBOX); // сбрасывает сцену
                    app.camera().camR = 2.5;
                    if (mode.equals("chamber")) w.chamber.setOn(true);
                    w.addProton();
                    w.addNeutron();
                    w.addElectron();
                    w.addElectron();
                    if (mode.equals("chamber")) w.parts.get(0).v[0] = 0.6;
                } else {
                    w.setScene(World.Scene.ATOMS); // стартовая сцена теперь песочница; здесь нужен атом
                    app.settings().detailsOpen = mode.equals("details");
                }
                for (int i = 0; i < seconds * 60; i++) {
                    w.advance(1.0 / 60);
                    app.camera().update(java.util.Set.of(), w.centerOfMass(), 1.0 / 60, false);
                    if (app.settings().bodyField) app.scene().updateHeat(); // «нагрев» копится по кадрам, как в окне
                }
                app.frame();
                frame.validate();
                BufferedImage img = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                String topic = System.getProperty("topic"); // -Dtopic=1…4 — открыть панель темы
                if (topic != null) {
                    app.controls().open(Integer.parseInt(topic) - 1);
                    app.frame();
                    frame.validate();
                }
                frame.getLayeredPane().paint(img.getGraphics()); // и панели тем поверх сцены
                ImageIO.write(img, "png", new File(out));
                System.out.println("рисование: " + (app.settings().shaders && !GlScene.failed() ? "шейдеры, " + GlScene.status() : "Java2D" + (GlScene.failed() ? " (" + GlScene.status() + ")" : "")));
                frame.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
