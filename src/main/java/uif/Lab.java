package uif;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Лаборатория: опыты на модели. {@link #setup(World)} кладёт опыт в живую сцену (вкладка
 * «Лаборатория»), {@link #run()} считает копию и возвращает вердикт. Те же опыты зовёт FormulaTest.
 */
public final class Lab {
    private Lab() {}

    /** Итог опыта: прошёл ли, короткий вердикт и подробности. */
    public record Report(boolean ok, String verdict, String details) {}

    /** Один опыт: настраивает мир и считает его копию. */
    public interface Experiment {
        String title();

        String hint();

        /** Разложить опыт в живом мире (для вкладки «Лаборатория»). */
        void setup(World w);

        /** Прогнать копию опыта и вернуть вердикт. */
        Report run();
    }

    public static List<Experiment> all() {
        return List.of(new MoleculeInChamber(), new ShellsR1(), new SurfaceLanding(), new ElectronOrbit());
    }

    static World world(double G) {
        World w = new World(new Random(7));
        prepare(w, G);
        return w;
    }

    private static void prepare(World w, double G) {
        w.prepareLaboratory(7);
        w.setG(G);
    }

    /** No frame budget: stop at the requested model time, checking every adaptive step. */
    static void stepToward(World w, double target) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        w.laboratoryStep(target - w.time);
    }

    static String fmt(String f, double v) {
        return String.format(Locale.ROOT, f, v);
    }

    private static void pair(World w) {
        w.parts = new ArrayList<>(List.of(
                w.makeBody(Species.P, -1, new double[]{-0.5, 0, 0}, new double[3]),
                w.makeBody(Species.P, -1, new double[]{0.5, 0, 0}, new double[3])));
    }

    /** 1. Молекула в замкнутой области: держит ли форму. */
    public static final class MoleculeInChamber implements Experiment {
        public World.MoleculeType type = World.MoleculeType.H2;

        @Override
        public String title() {
            return "Молекула в камере";
        }

        @Override
        public String hint() {
            return "молекула в замкнутой области, G = 1, 30 с: расстояния между ядрами не растут больше 1.5×";
        }

        @Override
        public void setup(World w) {
            prepare(w, 1);
            w.moleculeType = type;
            w.parts = new ArrayList<>(w.makeMolecule(new double[]{0, 0, 0}));
            w.chamber.R0 = Math.max(8, 2.5 * maxBond(w.parts));
            w.chamber.setOn(true);
        }

        @Override
        public Report run() {
            World w = world(1);
            setup(w);
            Map<Integer, List<Body>> byAtom = groups(w.parts);
            List<Integer> ids = new ArrayList<>(byAtom.keySet());
            double[][] m0 = dists(byAtom, ids);
            double worst = 1;
            int escapes = 0;
            while (w.time < 30 - 1e-10) {
                stepToward(w, 30);
                for (Body b : w.parts) {
                    if (World.dist(b.p, w.chamber.c) > w.chamber.radiusAt(b.p)) escapes++;
                }
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = i + 1; j < ids.size(); j++) {
                        double d = World.dist(World.lump(byAtom.get(ids.get(i))).c(), World.lump(byAtom.get(ids.get(j))).c());
                        worst = Math.max(worst, d / Math.max(1e-9, m0[i][j]));
                    }
                }
            }
            boolean ok = worst < 1.5 && escapes == 0;
            return new Report(ok, ok ? "стабильна" : "распалась",
                    type.name() + ": max d/d₀ = " + fmt("%.2f", worst) + ", выходов из камеры " + escapes
                            + ", время модели " + fmt("%.3f", w.time) + " с");
        }
    }

    /** 2. R1: contact at the physical surfaces, with no hovering gap. */
    public static final class ShellsR1 implements Experiment {
        @Override
        public String title() {
            return "Оболочки R1";
        }

        @Override
        public String hint() {
            return "два протона сближаются: контакт при расстоянии 2R, после удара остаются рядом без зазора";
        }

        @Override
        public void setup(World w) {
            prepare(w, World.G_MEDIUM);
            w.softRepulsion = true;
            pair(w);
        }

        @Override
        public Report run() {
            World w = world(World.G_MEDIUM);
            setup(w);
            double min = 9, r2 = 2 * w.parts.get(0).rad;
            while (w.time < 20 - 1e-10) {
                stepToward(w, 20);
                min = Math.min(min, World.dist(w.parts.get(0).p, w.parts.get(1).p));
            }
            double finalDistance = World.dist(w.parts.get(0).p, w.parts.get(1).p);
            boolean ok = min >= r2 - 1e-9 && Math.abs(finalDistance - r2) < 1e-7;
            return new Report(ok, ok ? "контакт на поверхности" : "контакт не удержан",
                    "min r = " + fmt("%.3f", min) + " при 2R = " + fmt("%.3f", r2)
                            + ", G = " + fmt("%.1e", World.G_MEDIUM) + ", время модели " + fmt("%.3f", w.time) + " с");
        }
    }

    /** 3. Small body falls onto the actual surface of a larger body. */
    public static final class SurfaceLanding implements Experiment {
        @Override
        public String title() {
            return "Падение на поверхность";
        }

        @Override
        public String hint() {
            return "малое тело падает на большое и остаётся на поверхности; проверка при G = 0.5, 1, 10";
        }

        @Override
        public void setup(World w) {
            prepare(w, 1);
            w.softRepulsion = true;
            w.parts = new ArrayList<>(List.of(
                    new Body(Species.CUSTOM, 1000, 10, new double[3], new double[3]),
                    new Body(Species.CUSTOM, 1, 0.1, new double[]{12, 0, 0}, new double[3])));
        }

        @Override
        public Report run() {
            boolean ok = true;
            StringBuilder details = new StringBuilder("Радиусы 10 и 0.1; контакт при r = 10.1. ");
            for (double g : new double[]{0.5, 1, 10}) {
                World w = world(g); setup(w); w.setG(g);
                double target = 3 / Math.sqrt(g), min = 12;
                while (w.time < target - 1e-10) {
                    stepToward(w, target);
                    min = Math.min(min, World.dist(w.parts.get(0).p, w.parts.get(1).p));
                }
                double distance = World.dist(w.parts.get(0).p, w.parts.get(1).p);
                double relativeSpeed = World.dist(w.parts.get(0).v, w.parts.get(1).v);
                ok &= min >= 10.1 - 1e-8 && Math.abs(distance - 10.1) < 1e-7 && relativeSpeed < 1e-7;
                details.append("G = ").append(fmt("%.1f", g)).append(": r = ").append(fmt("%.6f", distance))
                        .append(", время ").append(fmt("%.3f", w.time)).append(" с; ");
            }
            return new Report(ok, ok ? "малое тело осталось на поверхности" : "посадка не удержана", details.toString());
        }
    }

    /** 4. Электрон вокруг ядра: радиус орбиты держится. */
    public static final class ElectronOrbit implements Experiment {
        @Override
        public String title() {
            return "Электрон вокруг ядра";
        }

        @Override
        public String hint() {
            return "водород-1, G = 1, 5 с: радиус орбиты электрона держится (не падает и не улетает)";
        }

        @Override
        public void setup(World w) {
            prepare(w, 1);
            w.parts = new ArrayList<>(w.makeAtom(World.AtomType.H1, 0, new double[]{0, 0, 0}));
        }

        @Override
        public Report run() {
            World w = world(1);
            setup(w);
            Body e = null, nuc = null;
            for (Body b : w.parts) {
                if (b.kind == Species.E) e = b;
                else if (b.kind == Species.P && nuc == null) nuc = b;
            }
            if (e == null || nuc == null) return new Report(false, "нет атома", "водород не собрался");
            double r0 = World.dist(e.p, nuc.p), min = r0, max = r0;
            while (w.time < 5 - 1e-10) {
                stepToward(w, 5);
                double r = World.dist(e.p, nuc.p);
                min = Math.min(min, r);
                max = Math.max(max, r);
            }
            boolean ok = min > 0.5 * r0 && max < 2.0 * r0;
            return new Report(ok, ok ? "орбита держится" : "орбита не держится",
                    "r₀ = " + fmt("%.3f", r0) + ", min = " + fmt("%.3f", min) + ", max = " + fmt("%.3f", max)
                            + ", время модели " + fmt("%.3f", w.time) + " с");
        }
    }

    // ---------- общие помощники ----------

    static Map<Integer, List<Body>> groups(List<Body> parts) {
        Map<Integer, List<Body>> byAtom = new LinkedHashMap<>();
        for (Body b : parts) byAtom.computeIfAbsent(b.atom, k -> new ArrayList<>()).add(b);
        return byAtom;
    }

    static double[][] dists(Map<Integer, List<Body>> byAtom, List<Integer> ids) {
        double[][] m = new double[ids.size()][ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                m[i][j] = World.dist(World.lump(byAtom.get(ids.get(i))).c(), World.lump(byAtom.get(ids.get(j))).c());
            }
        }
        return m;
    }

    static double maxBond(List<Body> parts) {
        Map<Integer, List<Body>> g = groups(parts);
        List<Integer> ids = new ArrayList<>(g.keySet());
        double m = 0;
        for (double[] row : dists(g, ids)) for (double v : row) m = Math.max(m, v);
        return m;
    }
}
