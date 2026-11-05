package uif;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Наблюдение орбит (на движение не влияет).
 * <p>
 * Атомы: как раньше — первый электрон относительно ядра (строки теста устойчивости).
 * Все тела: эллипс вокруг более тяжёлого централа (Солнце / планета / протон…): копится
 * r_min/r_max, рисуются связанные орбиты с умеренным эксцентриситетом — планеты, луны,
 * астероиды и частицы, не только электроны атомов.
 */
public final class StabilityTest {
    public record Row(int atom, World.Pair pn, World.Pair e, double ratio, double rMin, double rMax,
                      double ecc, double tFree) {}

    /**
     * Геометрия орбиты для отрисовки: центр (фокус ≈ центрл), полуоси a≥b в плоскости (u)×(v).
     */
    public record Orbit(double[] c, double[] u, double[] v, double a, double b) {}

    /** Минимальный разброс r_max/r_min, чтобы считать, что пройдены пери- и апоцентр. */
    static final double STABLE_SPAN = 1.05;
    /** Эксцентриситет выше этого — скорее пролёт. */
    static final double STABLE_ECC = 0.85;
    /** Почти круговая (пресеты СС/Земля–Луна): рисуем сразу, не дожидаясь peri/apo. */
    static final double CIRC_ECC = 0.12;
    /** Не больше стольких эллипсов за кадр (крупные раньше астероидов). */
    static final int ORBIT_DRAW_MAX = 80;
    /**
     * Ниже этого — полный поиск централа O(N) на тело (луна→планета). Выше — hubs
     * (все STAR, иначе самый тяжёлый): O(N·H) вместо O(N²); иначе UI «висит» на multi-SS/поясах.
     */
    static final int FULL_CENTRAL_SCAN = 400;

    private static final class Track {
        double rMin = Double.POSITIVE_INFINITY, rMax, tFree = Double.NaN;
    }

    private static final class BodyTrack {
        Body central;
        double rMin = Double.POSITIVE_INFINITY, rMax, tFree = Double.NaN;
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final IdentityHashMap<Body, BodyTrack> bodyTracks = new IdentityHashMap<>();
    private double lastTime;
    private List<Row> last = List.of();

    public void reset() {
        tracks.clear();
        bodyTracks.clear();
        last = List.of();
    }

    /** Измерить текущее состояние; после сброса модели (время пошло назад) история начинается заново. */
    public List<Row> update(World w) {
        if (w.time < lastTime) reset();
        lastTime = w.time;
        Map<Integer, List<Body>> p = new HashMap<>(), n = new HashMap<>(), e = new HashMap<>();
        for (Body b : w.parts) {
            if (b.atom < 0) continue;
            (b.kind == Species.P ? p : b.kind == Species.N ? n : e).computeIfAbsent(b.atom, k -> new ArrayList<>()).add(b);
        }
        List<Row> rows = new ArrayList<>();
        for (int atom : w.atomIds()) {
            List<Body> nucleus = new ArrayList<>(p.getOrDefault(atom, List.of()));
            List<Body> ns = n.getOrDefault(atom, List.of()), es = e.getOrDefault(atom, List.of());
            nucleus.addAll(ns);
            World.Pair pn = ns.isEmpty() || p.getOrDefault(atom, List.of()).isEmpty()
                    ? null : w.pair(p.get(atom), List.of(ns.get(0)));
            World.Pair pe = es.isEmpty() || nucleus.isEmpty() ? null : w.pair(List.of(es.get(0)), nucleus); // первый (внутренний) электрон
            Track t = tracks.computeIfAbsent(atom, k -> new Track());
            if (pe != null) {
                t.rMin = Math.min(t.rMin, pe.r());
                t.rMax = Math.max(t.rMax, pe.r());
                if (!pe.bound() && Double.isNaN(t.tFree)) t.tFree = w.time;
            }
            double ratio = pn != null && pe != null && pn.r() > 0 ? pe.r() / pn.r() : Double.NaN;
            double ecc = t.rMax > 0 ? (t.rMax - t.rMin) / (t.rMax + t.rMin) : Double.NaN;
            rows.add(new Row(atom, pn, pe, ratio, t.rMin, t.rMax, ecc, t.tFree));
        }
        tracks.keySet().retainAll(new ArrayList<>(w.atomIds()));
        last = rows;
        updateBodies(w);
        return rows;
    }

    /** Централ орбиты: среди тел тяжелее b — с наибольшим m/r² (луна→планета, планета→Солнце). */
    static Body orbitCentral(World w, Body b) {
        Body best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Body o : w.parts) {
            if (o == b || o.m <= b.m) continue;
            if (o.partner != null && o.kind == Species.DM) continue;
            double dx = o.p[0] - b.p[0], dy = o.p[1] - b.p[1], dz = o.p[2] - b.p[2];
            double score = o.m / (dx * dx + dy * dy + dz * dz + 1e-18);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    /**
     * Hubs больших сцен: все {@link AstroClass#STAR} (multi-СС), иначе один самый тяжёлый.
     * O(N); дальше назначение тела — O(H) на тело.
     */
    static List<Body> orbitHubs(World w) {
        List<Body> hubs = new ArrayList<>();
        Body heaviest = null;
        for (Body o : w.parts) {
            if (o.partner != null && o.kind == Species.DM) continue;
            if (o.astro == AstroClass.STAR) hubs.add(o);
            if (heaviest == null || o.m > heaviest.m) heaviest = o;
        }
        if (hubs.isEmpty() && heaviest != null) hubs.add(heaviest);
        return hubs;
    }

    /** Лучший hub для b среди hubs тяжелее b — max m/r² (своё Солнце в multi-СС). */
    static Body bestHub(Body b, List<Body> hubs) {
        Body best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Body o : hubs) {
            if (o == b || o.m <= b.m) continue;
            double dx = o.p[0] - b.p[0], dy = o.p[1] - b.p[1], dz = o.p[2] - b.p[2];
            double score = o.m / (dx * dx + dy * dy + dz * dz + 1e-18);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    private void updateBodies(World w) {
        if (w.parts.size() > FULL_CENTRAL_SCAN) {
            updateBodiesHub(w);
            return;
        }
        IdentityHashMap<Body, Boolean> alive = new IdentityHashMap<>();
        for (Body b : w.parts) {
            if (b.partner != null && b.kind == Species.DM) continue; // один эллипс на диполь — по d+
            Body c = orbitCentral(w, b);
            trackPair(w, b, c, alive);
        }
        bodyTracks.keySet().removeIf(b -> !alive.containsKey(b));
    }

    /** Большие N: все STAR-hubs (или heaviest) — O(N·H) вместо O(N²) полного {@link #orbitCentral}. */
    private void updateBodiesHub(World w) {
        List<Body> hubs = orbitHubs(w);
        IdentityHashMap<Body, Boolean> alive = new IdentityHashMap<>();
        if (hubs.isEmpty()) {
            bodyTracks.clear();
            return;
        }
        for (Body b : w.parts) {
            if (b.partner != null && b.kind == Species.DM) continue;
            Body hub = bestHub(b, hubs);
            if (hub == null) continue;
            trackPair(w, b, hub, alive);
        }
        bodyTracks.keySet().removeIf(b -> !alive.containsKey(b));
    }

    private void trackPair(World w, Body b, Body c, IdentityHashMap<Body, Boolean> alive) {
        if (c == null) return;
        alive.put(b, Boolean.TRUE);
        BodyTrack t = bodyTracks.get(b);
        if (t == null || t.central != c) {
            t = new BodyTrack();
            t.central = c;
            bodyTracks.put(b, t);
        }
        World.Pair pe = w.pair(List.of(b), List.of(c));
        t.rMin = Math.min(t.rMin, pe.r());
        t.rMax = Math.max(t.rMax, pe.r());
        if (!pe.bound() && Double.isNaN(t.tFree)) t.tFree = w.time;
        if (pe.bound()) t.tFree = Double.NaN;
    }

    /**
     * Кнопка «орбиты»: есть кого показывать (хотя бы два тела). Переключатель доступен сразу
     * в СС / Земля–Луна; эллипсы появляются для связанных тел.
     */
    public boolean orbitsAvailable(World w) {
        return w.parts.size() >= 2;
    }

    /**
     * Почти круговая связанная орбита (ecc ≤ {@link #CIRC_ECC}) — для следов, в т.ч. крупных тел.
     * Атомные электроны — по строкам теста; остальные — по {@link #bodyTracks}.
     */
    public boolean nearCircular(Body b) {
        if (b == null) return false;
        BodyTrack t = bodyTracks.get(b);
        if (t != null) {
            if (!(t.rMax > 0) || !(t.rMin < Double.POSITIVE_INFINITY)) return false;
            if (!Double.isNaN(t.tFree)) return false;
            double ecc = (t.rMax - t.rMin) / (t.rMax + t.rMin);
            return ecc <= CIRC_ECC;
        }
        if (b.kind == Species.E && b.atom >= 0) {
            for (Row r : last) {
                if (r.atom() != b.atom || r.e() == null) continue;
                return r.e().bound() && Double.isNaN(r.tFree())
                        && !Double.isNaN(r.ecc()) && r.ecc() <= CIRC_ECC;
            }
        }
        return false;
    }

    /** Есть ли сейчас хотя бы одна устойчивая орбита электрона атома (для теста). */
    public boolean hasStableOrbit() {
        for (Row r : last) if (stable(r)) return true;
        return false;
    }

    static boolean stable(Row r) {
        return r.e() != null && r.e().bound() && Double.isNaN(r.tFree())
                && r.rMax() > 0 && r.rMin() < Double.POSITIVE_INFINITY
                && r.rMax() >= r.rMin() * STABLE_SPAN
                && !Double.isNaN(r.ecc()) && r.ecc() <= STABLE_ECC;
    }

    /** Рисуемая орбита тела: связан, e умеренный; почти круг — сразу, иначе после peri/apo. */
    static boolean bodyDrawable(BodyTrack t, World.Pair pe) {
        if (t == null || pe == null || !pe.bound() || !Double.isNaN(t.tFree)) return false;
        if (!(t.rMax > 0) || !(t.rMin < Double.POSITIVE_INFINITY)) return false;
        double ecc = (t.rMax - t.rMin) / (t.rMax + t.rMin);
        if (ecc > STABLE_ECC) return false;
        if (ecc <= CIRC_ECC) return true;
        return t.rMax >= t.rMin * STABLE_SPAN;
    }

    /**
     * Орбиты для отрисовки: электроны атомов + связанные тела вокруг более тяжёлого централа.
     * Крупные раньше; не больше {@link #ORBIT_DRAW_MAX}.
     */
    public List<Orbit> stableOrbits(World w) {
        List<Orbit> out = new ArrayList<>();
        // --- атомы (как раньше) ---
        Map<Integer, List<Body>> p = new HashMap<>(), n = new HashMap<>(), e = new HashMap<>();
        for (Body b : w.parts) {
            if (b.atom < 0) continue;
            (b.kind == Species.P ? p : b.kind == Species.N ? n : e).computeIfAbsent(b.atom, k -> new ArrayList<>()).add(b);
        }
        for (Row r : last) {
            if (!stable(r)) continue;
            List<Body> nucleus = new ArrayList<>(p.getOrDefault(r.atom(), List.of()));
            nucleus.addAll(n.getOrDefault(r.atom(), List.of()));
            List<Body> es = e.getOrDefault(r.atom(), List.of());
            if (nucleus.isEmpty() || es.isEmpty()) continue;
            Orbit o = ellipse(World.lump(nucleus).c(), World.lump(nucleus).cv(), es.get(0), r.rMin(), r.rMax(), r.ecc());
            if (o != null) out.add(o);
        }
        // --- прочие тела: сначала не-астероиды / тяжелее ---
        List<Body> candidates = new ArrayList<>();
        for (Body b : w.parts) {
            if (b.atom >= 0 && b.kind == Species.E) continue; // уже через atom-track
            if (b.partner != null && b.kind == Species.DM) continue;
            BodyTrack t = bodyTracks.get(b);
            if (t == null || t.central == null) continue;
            World.Pair pe = w.pair(List.of(b), List.of(t.central));
            if (!bodyDrawable(t, pe)) continue;
            candidates.add(b);
        }
        candidates.sort((a, b) -> {
            int ka = a.astro == AstroClass.ASTEROID ? 1 : 0;
            int kb = b.astro == AstroClass.ASTEROID ? 1 : 0;
            if (ka != kb) return Integer.compare(ka, kb);
            return Double.compare(b.m, a.m);
        });
        int room = Math.max(0, ORBIT_DRAW_MAX - out.size());
        for (int i = 0; i < candidates.size() && i < room; i++) {
            Body b = candidates.get(i);
            BodyTrack t = bodyTracks.get(b);
            Body c = t.central;
            double ecc = (t.rMax - t.rMin) / (t.rMax + t.rMin);
            Orbit o = ellipse(c.p, c.v, b, t.rMin, t.rMax, ecc);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** Эллипс в плоскости r×v; a=(r_min+r_max)/2, центр — централ (фокус ≈ центр при малом e). */
    private static Orbit ellipse(double[] c, double[] cv, Body moving, double rMin, double rMax, double ecc) {
        double rx = moving.p[0] - c[0], ry = moving.p[1] - c[1], rz = moving.p[2] - c[2];
        double vx = moving.v[0] - cv[0], vy = moving.v[1] - cv[1], vz = moving.v[2] - cv[2];
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (!(rl > 1e-12)) return null;
        double ux = rx / rl, uy = ry / rl, uz = rz / rl;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12) {
            double ax = Math.abs(ux) < 0.9 ? 1 : 0, ay = Math.abs(ux) < 0.9 ? 0 : 1;
            nx = uy * 0 - uz * ay;
            ny = uz * ax - ux * 0;
            nz = ux * ay - uy * ax;
            nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(nl > 1e-12)) return null;
        }
        nx /= nl;
        ny /= nl;
        nz /= nl;
        double vx2 = ny * uz - nz * uy, vy2 = nz * ux - nx * uz, vz2 = nx * uy - ny * ux;
        double a = 0.5 * (rMin + rMax);
        if (!(a > 0)) a = rl;
        double e = Double.isNaN(ecc) ? 0 : Math.min(Math.abs(ecc), 0.999);
        double b = a * Math.sqrt(Math.max(0, 1 - e * e));
        return new Orbit(c.clone(), new double[]{ux, uy, uz}, new double[]{vx2, vy2, vz2}, a, Math.max(b, a * 0.05));
    }
}
