package uif.ui;

import uif.Body;
import uif.Formula;
import uif.Kuo;
import uif.Scenarios;
import uif.Species;
import uif.StabilityTest;
import uif.World;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Окно: полоса тем сверху (панели выпадают поверх сцены), сцена под ней; цикл ~60 кадров/с. */
public final class App {
    // Подсказки — в справочнике (HelpTopics/HelpDialog): правый клик на сцене или F1.

    private final World world = new World();
    private final Camera cam = new Camera();
    private final Settings s = new Settings();
    private final ScenePanel scene = new ScenePanel(world, cam, s);
    private final ControlPanel controls = new ControlPanel(world, cam, s);
    private HelpDialog help;
    private final StabilityTest test = new StabilityTest();
    private final Set<Character> keys = new HashSet<>();
    private boolean boost; // Shift — ускорение полёта
    private Body camFocus; // тело, к центру которого плавно едет камера; null — центр масс
    private long last = System.nanoTime(), fpsT = last;
    private int fpsN;
    private double fps, realSpeed = 1, fpsModelT, lastSpeed = 1;
    /** Энергия — тот же перебор всех пар; в больших сценах её считаем не каждый кадр, а раз в 0.25 с. */
    static final int ENERGY_EACH_FRAME = 500;
    private World.Energy en;
    private long enT, enChanges = -1;

    public static void start() {
        UIManager.put("ToolTip.background", Theme.UI_CARD);
        UIManager.put("ToolTip.foreground", Theme.UI_TEXT);
        UIManager.put("ToolTip.font", Theme.UI_SMALL);
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.UI_BORDER),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        ToolTipManager.sharedInstance().setInitialDelay(400);
        new App().show();
    }

    private void show() {
        JFrame frame = build();
        frame.setVisible(true);
        // без ограничения кадров: следующий кадр — сразу после отрисовки предыдущего
        // (задержка 0, лишние срабатывания склеиваются); FPS = сколько успевает компьютер
        Timer t = new Timer(0, e -> frame());
        t.setCoalesce(true);
        t.start();
    }

    /** Собрать окно, не показывая его (используется и в SnapshotTool). */
    JFrame build() {
        cam.camR = uif.Scenarios.camR(world); // стартовый вид — по пресету (песочница → 2.5)
        JFrame frame = new JFrame("W = Q·N в 3D — атомы, нуклоны и электроны");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(Theme.BG);
        frame.setLayout(new BorderLayout());
        frame.add(scene, BorderLayout.CENTER);
        frame.add(controls, BorderLayout.NORTH); // полоса тем; их панели выпадают поверх сцены
        scene.setOnClick(() -> {
            if (camFocus != null) {
                double[] com = world.centerOfMass();
                cam.ox += camFocus.p[0] - com[0];
                cam.oy += camFocus.p[1] - com[1];
                cam.oz += camFocus.p[2] - com[2];
                camFocus = null;
            }
            // empty LMB clears main follow only — PiP keeps sticky lastFocused
            controls.close();
        });
        scene.setOnBodyClick(b -> {
            camFocus = b;
            cam.focusOn(b.rad);
            scene.showFocusPip(b); // sticky lastFocused + PiP inset
            controls.close();
        });
        frame.setSize(1360, 860);
        frame.setLocationRelativeTo(null);
        help = new HelpDialog(frame, world);
        controls.attach(frame.getLayeredPane(), () -> help.open(-1), () -> {
            camFocus = null;
            scene.clearFocusPip();
            test.reset();
        });
        scene.setContextMenu(this::sceneMenu);
        installKeys();
        return frame;
    }

    World world() {
        return world;
    }

    Settings settings() {
        return s;
    }

    ControlPanel controls() {
        return controls;
    }

    ScenePanel scene() {
        return scene;
    }

    Camera camera() {
        return cam;
    }

    /** Space — пауза, R — сброс, G — поле, T — след, F1 — справочник, Esc — закрыть панель темы; W/QE + Shift — полёт. */
    private void installKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", e -> clearKeys());
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusedWindow", e -> clearKeys());
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            int code = e.getKeyCode();
            char key = switch (code) {
                case KeyEvent.VK_W -> 'w';
                case KeyEvent.VK_Q -> 'q';
                case KeyEvent.VK_E -> 'e';
                default -> 0;
            };
            // Releases must clear held state even after focus moves to another window/input.
            if (e.getID() == KeyEvent.KEY_RELEASED) {
                if (key != 0) keys.remove(key);
                if (code == KeyEvent.VK_SHIFT) boost = false;
                pressed.remove(code);
                return false;
            }
            // F1 remains available; editing/activating a control must not control the scene.
            java.awt.Component src = e.getComponent();
            java.awt.Window win = src instanceof java.awt.Window w0 ? w0 : javax.swing.SwingUtilities.getWindowAncestor(src);
            if (code != KeyEvent.VK_F1 && (win != null && win != javax.swing.SwingUtilities.getWindowAncestor(scene)
                    || src instanceof javax.swing.text.JTextComponent || src instanceof javax.swing.JTable
                    || src instanceof javax.swing.JComboBox<?> || src instanceof javax.swing.AbstractButton
                    || src instanceof javax.swing.JList<?> || src instanceof javax.swing.JSlider
                    || src instanceof javax.swing.JSpinner || e.isControlDown() || e.isAltDown() || e.isMetaDown())) return false;
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                if (key != 0) keys.add(key);
                if (code == KeyEvent.VK_SHIFT) boost = true;
                boolean repeat = !pressed.add(code);
                if (repeat) return false;
                switch (code) {
                    case KeyEvent.VK_SPACE -> s.paused = !s.paused;
                    case KeyEvent.VK_R -> { world.reset(); camFocus = null; scene.clearFocusPip(); test.reset(); }
                    case KeyEvent.VK_G -> s.showField = !s.showField;
                    case KeyEvent.VK_T -> s.showTrail = !s.showTrail;
                    case KeyEvent.VK_F1 -> { if (help != null) help.open(-1); }
                    case KeyEvent.VK_ESCAPE -> {
                        // hide PiP first; still close theme panel (wheel/W remain zoom-escape)
                        if (scene.isFocusPipVisible()) scene.hideFocusPip();
                        controls.close();
                    }
                    default -> {}
                }
            }
            return false;
        });
    }

    private void clearKeys() {
        keys.clear(); pressed.clear(); boost = false;
    }

    private final Set<Integer> pressed = new HashSet<>();

    void frame() {
        long now = System.nanoTime();
        double realDt = (now - last) / 1e9;
        last = now;
        if (world.mode != World.Mode.KUO) world.setMode(World.Mode.KUO); // UI: Kuo; вкл/выкл — kuo.on
        // честное ускорение: шаг расчёта тот же, просто за кадр считается больше шагов
        if (s.speed != lastSpeed) {
            world.dropLag();
            lastSpeed = s.speed;
        }
        world.trailSelect = b -> true; // «След» вкл — у всех тел (не только круговых)
        if (!s.paused) world.advance(realDt * s.speed);
        scene.tickGrab(); // тело исчезло из сцены — отпустить
        if (world.stabilize != null && !world.parts.contains(world.stabilize)) world.clearStabilize();
        if (camFocus != null && !world.parts.contains(camFocus)) camFocus = null;
        Body pip = scene.lastFocused();
        if (pip != null && !world.parts.contains(pip)) scene.clearFocusPip();
        double[] follow = camFocus != null ? camFocus.p : world.centerOfMass();
        cam.update(keys, follow, realDt, boost, camFocus != null); // тело — lock, без лага вблизи

        fpsN++;
        if (now - fpsT > 500_000_000L) {
            double span = (now - fpsT) / 1e9;
            fps = fpsN / span;
            realSpeed = s.paused ? 0 : (world.time - fpsModelT) / span; // фактическая скорость модельного времени
            fpsModelT = world.time;
            fpsN = 0;
            fpsT = now;
        }

        World.Counts c = world.counts();
        if (en == null || world.parts.size() <= ENERGY_EACH_FRAME || world.changes != enChanges || now - enT > 250_000_000L) {
            en = world.energy();
            enT = now;
            enChanges = world.changes;
        }
        double dE = world.energyDrift(en);
        List<StabilityTest.Row> rows = test.update(world); // копится всегда; обновляет nearCircular для следующего кадра
        s.orbitsReady = test.orbitsAvailable(world); // ≥2 тел: СС, Земля–Луна, атомы, песочница
        if (!s.orbitsReady) s.showOrbits = false;
        scene.setOrbits(s.showOrbits ? test.stableOrbits(world) : List.of());
        String[] sum = summary(c, en, dE);
        if (s.testOpen) sum = concat(sum, testLines(rows));
        if (s.showForces) sum = concat(sum, forceLines());
        if (s.showPolesSides) sum = concat(sum, polesSidesLines());
        scene.setOverlay(sum, s.detailsOpen ? details(en) : new String[0]);
        controls.sync(c);
        // рисуем сразу, а не «когда-нибудь»: иначе без ограничения кадров repaint склеивается
        // и FPS в сводке считал бы расчёты, а не нарисованные кадры
        if (scene.isShowing()) scene.paintImmediately(0, 0, scene.getWidth(), scene.getHeight());
        else scene.repaint();
    }

    private String[] summary(World.Counts c, World.Energy en, double dE) {
        List<String> l = new ArrayList<>();
        l.add("Комплексное тяготение (КУО) · 3D · " + (world.scene == World.Scene.SANDBOX ? "песочница" : "атомы") + (s.paused ? " · пауза" : ""));
        l.add("p " + c.p() + " · n " + c.n() + " · e " + c.e() + (c.custom() > 0 ? " · своих " + c.custom() : "") + (c.molecules() > 0 ? " · мол " + c.molecules() : "") + " · A " + (c.p() + c.n()) + " · Z " + c.p() + (c.dipoles() > 0 ? " · диполей " + c.dipoles() : "") + " · тел " + world.parts.size());
        for (Body d : world.parts) {
            if (d.kind != Species.DP || d.partner == null) continue;
            Body m = d.partner;
            double r = World.dist(d.p, m.p), L = d.rs / Math.cbrt(d.chi);
            double mu = d.m * m.m / (d.m + m.m), v = World.dist(d.v, m.v);
            double E = 0.5 * mu * v * v - Formula.F_ALPHA * Formula.K_V * world.kQ() * d.N * m.N * World.pairPhi(d, m, r);
            l.add("диполь: заряд " + Theme.exp(d.N - m.N, 1) + " (d+ " + Theme.exp(d.N, 1) + ", d− " + Theme.exp(m.N, 1) + ") · длина " + Theme.exp(r, 3) + " (равновесие " + Theme.exp(L, 2) + ") · энергия связи " + Theme.exp(world.bindingEnergy(d), 2)
                    + (E < 0 ? " · держится" : " · РАЗОРВАН"));
            break; // первый диполь — для остальных то же самое
        }
        l.add("E = " + Theme.exp(en.E(), 4) + " · ΔE/E = " + Theme.exp(dE, 1) + (world.chamber.on ? " (с потерями)" : ""));
        if (world.stabilize != null) {
            Body cen = world.stabilizeCentral;
            l.add("стабилизация орбиты · крафт"
                    + (cen != null ? " вокруг " + (cen.astro == uif.AstroClass.STAR ? "Солнца" : cen.kind.label) : "")
                    + " · ПКМ по телу — стоп");
        }
        if (world.mode == World.Mode.KUO && world.kuo.on) {
            double beta = world.kuo.beta, g = world.kuo.gain();
            l.add("КУО: частное отталкивание тел −" + Theme.beta(beta) + "·W · приталкивание от КУО (дефицит массы) +"
                    + Theme.beta(world.kuo.betaK()) + "·W · итог " + (world.kuo.linked ? "W" : (g < 0 ? "−" : "") + Theme.beta(Math.abs(g)) + "·W"));
            if (world.kuo.closed) {
                l.add("КУО замкнут: q = " + Theme.fixed(world.kuo.q(), 3) + " · приталкивание ×(1 − q) · итог "
                        + (world.kuo.netGain() < 0 ? "−" : "") + Theme.beta(Math.abs(world.kuo.netGain())) + "·W");
            }
        } else if (world.mode == World.Mode.KUO) {
            l.add("КУО выкл · только −" + Theme.beta(world.kuo.beta) + "·W (частное отталкивание) — тела расталкиваются");
        }
        if (world.chamber.on) {
            l.add("замкнутая область: R = " + Theme.fixed(world.chamber.R(), 3) + " · радиус покоя " + Theme.fixed(world.chamber.restRadius(), 3)
                    + " (R₀ = " + Theme.fixed(world.chamber.R0, 1) + ", давление тел " + Theme.exp(world.chamber.bodyPush, 1)
                    + ", внешнее " + (world.chamber.pExt > 0 ? Theme.exp(world.chamber.pExt, 1) : "0") + ") · потери " + Theme.exp(world.chamber.lost, 2));
            double ratio = 0;
            List<World.Nucleon> nucs = world.parts.size() <= ENERGY_EACH_FRAME ? world.nucleons() : List.of();
            for (Body e : nucs.isEmpty() ? List.<Body>of() : world.parts) {
                if (e.kind != Species.E || e.atom < 0) continue;
                List<Body> nucleus = new ArrayList<>();
                for (World.Nucleon g : nucs) if (g.atom() == e.atom) nucleus.addAll(g.bodies());
                if (!nucleus.isEmpty()) ratio = Math.max(ratio, world.chamber.pushRatio(e, World.lump(nucleus)));
            }
            if (ratio > 0) l.add("стенка давит на e сильнее ядра в " + (ratio >= 1 ? Theme.fixed(ratio, ratio < 10 ? 2 : 0) + " раз" : Theme.fixed(ratio, 4) + " раза (слабее)")
                    + (ratio > 1 ? " — атом сжимается" : ""));
            double Rm = world.chamber.R(), out = 0, in = 0;
            for (double rk : world.chamber.Rk) {
                out = Math.max(out, rk - Rm);
                in = Math.max(in, Rm - rk);
            }
            double[] vc = world.chamber.vc;
            l.add("форма: выпуклость +" + Theme.fixed(out, 3) + " · вмятина −" + Theme.fixed(in, 3)
                    + " (от R̄) · скорость центра " + Theme.exp(Math.sqrt(vc[0] * vc[0] + vc[1] * vc[1] + vc[2] * vc[2]), 1));
        }
        boolean behind = world.lag() > 0.1 * Math.max(1, s.speed);
        l.add("t = " + Theme.fixed(world.time, 1) + " · скорость ×" + speedText(s.speed)
                + (behind ? " · фактически ×" + Theme.fixed(realSpeed, realSpeed < 10 ? 1 : 0) + " (не успевает)" : "")
                + " · FPS = " + Theme.fixed(fps, 0));
        int n = world.parts.size();
        String path = world.forces.lastPath;
        l.add("расчёт пар: " + path + (path.startsWith("GPU") ? " (" + world.forces.gpuStatus() + ")" : "")
                + (path.contains("Barnes") ? " · Barnes–Hut, приближённо"
                : path.startsWith("GPU float") ? " · приближённо, ~10⁻⁷" : "")
                + (n > ENERGY_EACH_FRAME ? " · E — раз в 0.25 с" : "")
                + (s.showTrail ? " · след — все тела" : ""));
        return l.toArray(String[]::new);
    }

    /** Тест устойчивости: по строке на пару p–n и на e каждого атома. */
    private static String[] testLines(List<StabilityTest.Row> rows) {
        List<String> l = new ArrayList<>();
        l.add("тест устойчивости (связано ⇔ E пары < 0)");
        if (rows.isEmpty()) l.add("  атомов нет");
        if (rows.size() > 8) l.add("  показаны первые 8 атомов из " + rows.size());
        for (StabilityTest.Row r : rows.subList(0, Math.min(8, rows.size()))) {
            if (r.pn() != null) {
                l.add("  атом " + r.atom() + " · p–n: r = " + Theme.fixed(r.pn().r(), 3) + " · E = " + Theme.exp(r.pn().E(), 2)
                        + (r.pn().bound() ? " · связаны" : " · РАЗЛЕТЕЛИСЬ"));
            }
            if (r.e() != null) {
                l.add("  атом " + r.atom() + " · e: r = " + Theme.fixed(r.e().r(), 3)
                        + " (" + Theme.fixed(r.rMin(), 2) + "…" + Theme.fixed(r.rMax(), 2) + ", эксц. " + Theme.fixed(r.ecc(), 2) + ")"
                        + (Double.isNaN(r.ratio()) ? "" : " · r_e/r_pn = " + Theme.fixed(r.ratio(), 2))
                        + " · E = " + Theme.exp(r.e().E(), 2)
                        + (r.e().bound() ? " · связан" : " · СВОБОДЕН")
                        + (Double.isNaN(r.tFree()) ? "" : " · уходил при t = " + Theme.fixed(r.tFree(), 1)));
            }
        }
        return l.toArray(String[]::new);
    }

    /** Телесный угол соседей у первых трёх тел — в нём КУО «недосчитывает» массу соседей. */
    private String[] forceLines() {
        List<String> l = new ArrayList<>();
        l.add("силы: красная −β·W отталкивание тел · голубая +(1+β)·W приталкивание от КУО · белая — итог");
        if (world.parts.size() > ScenePanel.FORCES_MAX) l.add("  стрелки скрыты: тел больше " + ScenePanel.FORCES_MAX);
        for (int i = 0; i < Math.min(3, world.parts.size()); i++) {
            Body a = world.parts.get(i);
            double frac = 0;
            for (Body b : world.parts) if (b != a) frac += Kuo.solidAngleFraction(b.rad, World.dist(a.p, b.p));
            l.add("  " + a.kind.label + ": соседи закрывают телесный угол Ω/4π = " + Theme.exp(frac, 2));
        }
        return l.toArray(String[]::new);
    }

    /** Легенда оверлея «полюса как стороны» — только картинка, уравнения те же. */
    private String[] polesSidesLines() {
        List<String> l = new ArrayList<>();
        l.add("полюса как стороны: у каждого тела красная — отталкивание · голубая — приталкивание от КУО · только картинка");
        if (world.parts.isEmpty()) l.add("  на сцене нет тел");
        else if (world.parts.size() > ScenePanel.FORCES_MAX)
            l.add("  стрелки скрыты: тел больше " + ScenePanel.FORCES_MAX);
        else l.add("  тел " + world.parts.size() + " · без белого итога (он у кнопки «силы») · диполь: те же стороны связи");
        return l.toArray(String[]::new);
    }

    static String speedText(double v) {
        if (v == 0.125) return "⅛";
        if (v == 0.25) return "¼";
        if (v == 0.5) return "½";
        return v < 1 ? Theme.fixed(v, 2) : Theme.fixed(v, 0);
    }

    /** Меню сцены: стабилизация / удаление по телу, «создать здесь» в точке правого клика и разделы справки. */
    private javax.swing.JPopupMenu sceneMenu() {
        javax.swing.JPopupMenu m = help.menu();
        int at = 0;
        Body hit = scene.popupHit();
        if (hit != null) {
            boolean on = world.stabilize == hit;
            String label = on ? "Стабилизировать ✓" : "Стабилизировать";
            javax.swing.JMenuItem stab = createItem(label, () -> world.toggleStabilize(hit));
            stab.setEnabled(on || world.canStabilize(hit));
            if (!on && !world.canStabilize(hit)) {
                stab.setToolTipText("нет централа (нужно другое тело / Солнце)");
            }
            m.insert(stab, at++);
            m.insert(createItem("Удалить", () -> world.removeBody(hit)), at++);
            m.insert(new javax.swing.JPopupMenu.Separator(), at++);
        }
        double[] p = scene.pickPoint();
        String title = p == null ? "Создать здесь" : "Создать здесь: x " + Theme.fixed(p[0], 2)
                + " · y " + Theme.fixed(p[1], 2) + " · z " + Theme.fixed(p[2], 2);
        javax.swing.JMenu create = new javax.swing.JMenu(title);
        create.setFont(Theme.UI_FONT);
        create.setForeground(Theme.UI_TEXT);
        create.setBackground(Theme.UI_CARD);
        create.setOpaque(true);
        if (p != null) {
            final double[] q = p.clone();
            create.add(createItem("Протон", () -> world.addAt(Species.P, q)));
            create.add(createItem("Нейтрон", () -> world.addAt(Species.N, q)));
            create.add(createItem("Электрон", () -> world.addAt(Species.E, q)));
            create.add(createItem("Своя (m_e " + Theme.fixed(world.customMass, world.customMass < 10 ? 2 : 0) + ")",
                    () -> world.addAt(Species.CUSTOM, q)));
            create.addSeparator();
            create.add(createItem("Атом: " + world.atomType.title, () -> world.addAtomAt(q)));
            create.add(createItem("Молекула: " + world.moleculeType.title, () -> world.addMoleculeAt(q)));
            create.add(createItem("Диполь", () -> world.addDipoleAt(q)));
            create.addSeparator();
            create.add(createItem("Рандом", () -> world.addRandomAt(q)));
            create.addSeparator();
            javax.swing.JMenu scenario = new javax.swing.JMenu("Сценарий");
            scenario.setFont(Theme.UI_FONT);
            scenario.setForeground(Theme.UI_TEXT);
            scenario.setBackground(Theme.UI_CARD);
            scenario.setOpaque(true);
            scenario.setToolTipText("добавить пресет в точку прицела (поверх сцены)");
            for (Scenarios.Preset pr : Scenarios.Preset.values()) {
                if (Scenarios.clearsScene(pr)) continue; // песочница — только в теме «Сценарий»
                final Scenarios.Preset preset = pr;
                scenario.add(createItem(pr.title, () -> placeScenarioAt(preset, q)));
            }
            create.add(scenario);
        } else {
            create.setEnabled(false);
        }
        m.insert(create, at++);
        m.insert(new javax.swing.JPopupMenu.Separator(), at);
        return m;
    }

    /** Пункт «создать здесь»: поставить частицу, убрать метку, обновить счётчики. */
    private javax.swing.JMenuItem createItem(String title, Runnable r) {
        javax.swing.JMenuItem it = new javax.swing.JMenuItem(title);
        it.setFont(Theme.UI_FONT);
        it.setBackground(Theme.UI_CARD);
        it.setForeground(Theme.UI_TEXT);
        it.setOpaque(true);
        it.addActionListener(e -> {
            r.run();
            scene.clearPick();
            scene.repaint();
            controls.sync(world.counts());
        });
        return it;
    }

    /** ПКМ → создать здесь → сценарий: append пресета с COM в точке прицела. */
    private void placeScenarioAt(Scenarios.Preset preset, double[] at) {
        world.appendPresetAt(preset, at);
        if (Scenarios.enablesKuoAndGpuBh(preset)) s.showKuo = true; // устройство — в World
        double need = Math.max(Scenarios.camR(world), 1.25 * Scenarios.packRadius(world.parts));
        cam.camR = Math.max(cam.camR, Math.max(Camera.MIN_R, Math.min(Camera.MAX_R, need)));
        camFocus = null;
        // append keeps old bodies; PiP stays if lastFocused still alive (frame clears if not)
    }

    double realSpeed() {
        return realSpeed;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private String[] details(World.Energy en) {
        List<World.Nucleon> nucs = world.nucleons();
        List<World.Lump> protons = new ArrayList<>(), neutrons = new ArrayList<>();
        for (World.Nucleon g : nucs) (g.kind() == Species.P ? protons : neutrons).add(World.lump(g.bodies()));
        double R = 0;
        for (int i = 0; i < protons.size(); i++) {
            for (int j = i + 1; j < protons.size(); j++) {
                double d = World.dist(protons.get(j).c(), protons.get(i).c());
                if (R == 0 || d < R) R = d;
            }
        }
        List<String> l = new ArrayList<>(List.of(
                "воздействие = качество × количество",
                "Q = k_a·C1/(4πR²) — качество; N = V·q = h·S·q — количество (заряд)",
                "W = Σ Q_j·N_j; F = f(α)·n_f·W, n_f = k_v·N; a = F/m",
                "k_a = k_v = f(α) = 1 · C1 = 4πG = " + Theme.exp(world.C1, 2),
                "q = " + Theme.exp(Formula.Q_DENS, 2) + " у всех тел; N = V·q",
                "p: N = " + Theme.fixed(Formula.M_P, 0) + ", радиус " + Theme.fixed(Formula.RAD_P, 4),
                "n: N = " + Theme.fixed(Formula.M_N, 0) + ", радиус " + Theme.fixed(Formula.RAD_N, 4),
                "e: N = " + Theme.fixed(Formula.M_E, 0) + ", радиус " + Formula.RAD_E,
                "только доказанные частицы с измеренной массой: p, n, e",
                "комплексное тяготение: F = −β·W (отталкивание тел) + (1+β)·W (дефицит массы КУО) = W; телесный угол тела R на r: Ω/4π ≈ R²/4r²",
                "R(p–p, min) = " + (R > 0 ? Theme.fixed(R, 2) : "—") + " a₀"));
        if (!protons.isEmpty() && !neutrons.isEmpty()) {
            World.Lump p0 = protons.get(0), n0 = neutrons.get(0);
            l.add("R(p–n) = " + Theme.fixed(World.dist(n0.c(), p0.c()), 2) + " a₀ · v=" + Theme.exp(World.dist(n0.cv(), p0.cv()), 2));
        }
        l.add("|p| = " + Theme.exp(en.momentum(), 1) + " · сложность O(N²)");
        l.add("без кулона, кора, БГП и потоков Лесажа");
        return l.toArray(String[]::new);
    }
}
