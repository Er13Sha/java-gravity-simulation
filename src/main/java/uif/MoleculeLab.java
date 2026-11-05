package uif;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reproducible experiments; geometry is an initial condition, not a chemical bond. */
public final class MoleculeLab {
    private MoleculeLab() {}

    public sealed interface Setup permits Config, Assembly {
        double seconds();
        long seed();
        String description();
        Measurement measurement();
    }

    /** Indices in construction order; the middle index is the vertex. */
    public record Measurement(int first, int vertex, int last) {}

    public record Atom(World.AtomType type, double x, double y, double z, double vx, double vy, double vz) {
        public Atom {
            Objects.requireNonNull(type, "Выберите вид атома");
            for (double v : new double[]{x, y, z, vx, vy, vz})
                if (!Double.isFinite(v) || Math.abs(v) > 10000)
                    throw new IllegalArgumentException("Координаты и скорости: конечные числа от −10000 до 10000");
        }
        public double[] position() { return new double[]{x, y, z}; }
        public double[] velocity() { return new double[]{vx, vy, vz}; }
    }

    public record Assembly(List<Atom> atoms, double seconds, long seed, Measurement measurement) implements Setup {
        public Assembly {
            atoms = List.copyOf(atoms);
            if (atoms.isEmpty() || atoms.size() > 32) throw new IllegalArgumentException("В сборке должно быть 1…32 атома");
            validateTime(seconds);
            int bodies = 0;
            for (int i = 0; i < atoms.size(); i++) {
                Atom a = atoms.get(i);
                bodies += 2 * a.type().protons + a.type().neutrons;
                for (int j = 0; j < i; j++) if (World.dist(a.position(), atoms.get(j).position()) < 1e-6)
                    throw new IllegalArgumentException("Атомы " + (j + 1) + " и " + (i + 1) + " имеют одинаковые координаты");
            }
            if (bodies > 600) throw new IllegalArgumentException("Для одного опыта допускается до 600 частиц p/n/e");
            if (measurement != null) {
                int a = measurement.first(), b = measurement.vertex(), c = measurement.last();
                if (a < 0 || b < 0 || c < 0 || a >= atoms.size() || b >= atoms.size() || c >= atoms.size()
                        || a == b || b == c || a == c) throw new IllegalArgumentException("Для угла выберите три разных атома");
            }
        }
        public String description() {
            return "Свободная сборка (" + atoms.size() + " атомов): "
                    + atoms.stream().map(a -> a.type().name()).collect(java.util.stream.Collectors.joining(", "));
        }
    }

    private static void validateTime(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.1 || seconds > 60)
            throw new IllegalArgumentException("Время модели: 0.1…60 с");
    }

    public record Config(World.MoleculeType type, double distance, double angle, double seconds, long seed) implements Setup {
        public Config {
            if (type != World.MoleculeType.H2 && type != World.MoleculeType.H2O)
                throw new IllegalArgumentException("Выберите H₂ или H₂O");
            if (!Double.isFinite(distance) || distance < 1 || distance > 50
                    || !Double.isFinite(angle) || angle < 10 || angle > 180
                    || !Double.isFinite(seconds) || seconds < 0.1 || seconds > 60)
                throw new IllegalArgumentException("Расстояние 1…50, угол 10…180°, время 0.1…60 с");
        }
        public String description() {
            return String.format(Locale.ROOT, "%s; d = %.3f a₀; угол = %s", type, distance,
                    type == World.MoleculeType.H2O ? String.format(Locale.ROOT, "%.1f°", angle) : "не применимо");
        }
        public Measurement measurement() { return type == World.MoleculeType.H2O ? new Measurement(1, 0, 2) : null; }
        public Assembly toAssembly() {
            double[][] positions = offsets();
            List<Atom> atoms = new ArrayList<>();
            for (int i = 0; i < positions.length; i++)
                atoms.add(new Atom(type.atoms[i], positions[i][0], positions[i][1], positions[i][2], 0, 0, 0));
            return new Assembly(atoms, seconds, seed, measurement());
        }
        public double[][] offsets() {
            if (type == World.MoleculeType.H2)
                return new double[][]{{-distance / 2, 0, 0}, {distance / 2, 0, 0}};
            double a = Math.toRadians(angle / 2);
            return new double[][]{{0, 0, 0}, {distance * Math.sin(a), distance * Math.cos(a), 0},
                    {-distance * Math.sin(a), distance * Math.cos(a), 0}};
        }
    }

    public record Sample(double time, double minRatio, double maxRatio, double angle) {}
    public record Result(Setup config, List<Sample> samples, String verdict, String details) {}

    public static Result run(Setup config, BooleanSupplier cancelled, Consumer<Sample> progress) {
        World w = new World(new Random(config.seed()));
        w.prepareMoleculeLab(config);
        List<List<Body>> atoms = new ArrayList<>(Lab.groups(w.parts).values());
        double[][] initial = centers(atoms, true);
        Measurement measurement = config.measurement();
        double initialAngle = measurement != null ? measuredAngle(initial, measurement) : Double.NaN;
        double[][] initialAtoms = centers(atoms, false);
        double[] radii = new double[atoms.size()];
        for (int i = 0; i < atoms.size(); i++)
            for (Body b : atoms.get(i)) radii[i] = Math.max(radii[i], World.dist(b.p, initialAtoms[i]));
        List<Sample> samples = new ArrayList<>();
        double low = 1, high = 1, angleDrift = 0;
        boolean escaped = false, atomDamaged = false;
        int targetIndex = 0;
        double nextSample = 0;
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            double[][] c = centers(atoms, true);
            double min = atoms.size() == 1 ? 1 : Double.POSITIVE_INFINITY, max = atoms.size() == 1 ? 1 : 0;
            for (int i = 0; i < c.length; i++) for (int j = i + 1; j < c.length; j++) {
                double ratio = World.dist(c[i], c[j]) / World.dist(initial[i], initial[j]);
                min = Math.min(min, ratio); max = Math.max(max, ratio);
            }
            double angle = measurement != null ? measuredAngle(c, measurement) : Double.NaN;
            if (!Double.isFinite(min) || !Double.isFinite(max) || (measurement != null && !Double.isFinite(angle)))
                throw new IllegalStateException("Нечисловые координаты или вырожденная геометрия");
            low = Math.min(low, min); high = Math.max(high, max);
            if (measurement != null) angleDrift = Math.max(angleDrift, Math.abs(angle - initialAngle));
            double[][] ac = centers(atoms, false);
            for (int i = 0; i < atoms.size(); i++) for (Body b : atoms.get(i)) {
                for (int k = 0; k < 3; k++) if (!Double.isFinite(b.p[k]) || !Double.isFinite(b.v[k]))
                    throw new IllegalStateException("Нечисловое состояние частицы");
                if (World.dist(b.p, w.chamber.c) > w.chamber.radiusAt(b.p)) escaped = true;
                if (World.dist(b.p, ac[i]) > 2 * radii[i]) atomDamaged = true;
            }
            if (w.time + 1e-10 >= nextSample) {
                Sample sample = new Sample(w.time, min, max, angle);
                samples.add(sample); progress.accept(sample);
                targetIndex++;
                nextSample = Math.min(config.seconds(), targetIndex / 30.0);
            }
            if (w.time + 1e-10 >= config.seconds()) break;
            w.laboratoryStep(Math.min(nextSample, config.seconds()) - w.time);
        }
        String verdict = high > 1.5 ? "Разделилась по критерию расстояний"
                : atomDamaged ? "Нарушена целостность атомов"
                : escaped ? "Выход из камеры"
                : low < 0.8 || high > 1.2 || angleDrift > 15 ? "Деформировалась"
                : atoms.size() == 1 ? "Атом сохранил целостность" : "Сохранила форму";
        String details = String.format(Locale.ROOT,
                "%s; seed = %d%nCPU, G = 1, КУО по умолчанию, камера R₀ = %.3f%nВремя модели %.3f с; d/d₀: %.3f…%.3f; отклонение угла: %s%nВыход из камеры: %s; расширение атома >2×: %s%nКритерии формы: d/d₀ 0.8…1.2, угол ±15°. Разделение: >1.5. Это диагностика модели, не проверка химической связи.",
                config.description(), config.seed(), w.chamber.R0, w.time, low, high,
                measurement != null ? String.format(Locale.ROOT, "%.2f° (атомы %d–%d–%d)", angleDrift,
                        measurement.first() + 1, measurement.vertex() + 1, measurement.last() + 1) : "не выбрано",
                escaped ? "да" : "нет", atomDamaged ? "да" : "нет");
        if (config instanceof Assembly assembly) {
            StringBuilder initialState = new StringBuilder("\nНачальные центры ядер (a₀) и скорости атомов (a₀/с):");
            for (int i = 0; i < assembly.atoms().size(); i++) {
                Atom a = assembly.atoms().get(i);
                initialState.append(String.format(Locale.ROOT, "%n%d %s: r=(%.6g, %.6g, %.6g), v=(%.6g, %.6g, %.6g)",
                        i + 1, a.type().name(), a.x(), a.y(), a.z(), a.vx(), a.vy(), a.vz()));
            }
            details += initialState;
        }
        return new Result(config, List.copyOf(samples), verdict, details);
    }

    private static double measuredAngle(double[][] centers, Measurement m) {
        return angle(new double[][]{centers[m.vertex()], centers[m.first()], centers[m.last()]});
    }

    public static double angle(Atom a, Atom vertex, Atom b) {
        return angle(new double[][]{vertex.position(), a.position(), b.position()});
    }

    private static double[][] centers(List<List<Body>> atoms, boolean nucleiOnly) {
        double[][] result = new double[atoms.size()][3];
        for (int i = 0; i < atoms.size(); i++) {
            double mass = 0;
            for (Body b : atoms.get(i)) if (!nucleiOnly || b.kind != Species.E) {
                mass += b.m;
                for (int k = 0; k < 3; k++) result[i][k] += b.m * b.p[k];
            }
            for (int k = 0; k < 3; k++) result[i][k] /= mass;
        }
        return result;
    }

    private static double angle(double[][] c) {
        double dot = 0, a = 0, b = 0;
        for (int k = 0; k < 3; k++) {
            double x = c[1][k] - c[0][k], y = c[2][k] - c[0][k];
            dot += x * y; a += x * x; b += y * y;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / Math.sqrt(a * b)))));
    }
}
