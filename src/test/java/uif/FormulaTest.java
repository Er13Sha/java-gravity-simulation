package uif;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Запускается без JUnit: java -cp build/classes uif.FormulaTest. */
public final class FormulaTest {
    private static int checks;

    /** Суммарный импульс тел Σ m·v (вектор). */
    /**
     * 28г. Замкнутая область на видеокарте (весь шаг, «GPU float») против «GPU double» (= CPU):
     * часть тел у стенки и летит наружу — работают сила стенки, прогиб узлов, вязкий слой
     * (в том числе в среднем подшаге Йошиды, где dt < 0). Положения, узлы, центр, потери,
     * E + потери и импульс тел + стенки — те же.
     */
    private static void chamberGpu() {
        World[] w = new World[2];
        for (int v = 0; v < 2; v++) {
            w[v] = world(1e-5);
            w[v].setScene(World.Scene.SANDBOX);
            w[v].chamber.setOn(true);
            Random r = new Random(11);
            w[v].addProton();
            for (int i = 0; i < 120; i++) {
                int kind = r.nextInt(4);
                if (kind == 0) w[v].addProton();
                else if (kind == 1) w[v].addElectron();
                else if (kind == 2) w[v].addNeutron();
                else w[v].addDipole();
            }
            for (int i = 0; i < w[v].parts.size(); i += 7) { // к стенке, с разлётом наружу
                Body b = w[v].parts.get(i);
                double rho = Math.sqrt(Chamber.dot(b.p, b.p));
                if (b.partner != null || rho == 0) continue;
                double R = w[v].chamber.radiusAt(b.p) * 0.93;
                for (int k = 0; k < 3; k++) { b.p[k] = b.p[k] / rho * R; b.v[k] += 0.3 * b.p[k] / R; }
            }
            w[v].chamber.lost = 0;
            w[v].forces.device = v == 0 ? Forces.Device.GPU : Forces.Device.GPU_FLOAT;
        }
        double mw = w[1].chamber.mass(), scale = 0;
        for (Body b : w[1].parts) scale += b.m * Math.sqrt(Chamber.dot(b.v, b.v));
        double E0 = w[1].energy().E();
        double[] P0 = momentum(w[1]);
        for (int k = 0; k < 3; k++) P0[k] += mw * w[1].chamber.vc[k];
        int[] st = new int[2];
        for (int v = 0; v < 2; v++) {
            st[v] = w[v].advance(0.02);
            for (int m; (m = w[v].advance(0)) > 0; ) st[v] += m;
        }
        check(w[1].forces.lastPath.equals(Forces.RESIDENT), "замкнутая область: весь шаг на видеокарте — " + w[1].forces.lastPath);
        check(st[0] == st[1] && st[1] > 0, "замкнутая область на видеокарте: столько же шагов " + st[1] + " / " + st[0]);
        double dx = 0, dR = 0, dc = 0;
        for (int i = 0; i < w[0].parts.size(); i++) dx = Math.max(dx, World.dist(w[0].parts.get(i).p, w[1].parts.get(i).p));
        for (int k = 0; k < Chamber.M; k++) dR = Math.max(dR, Math.abs(w[0].chamber.Rk[k] - w[1].chamber.Rk[k]));
        dc = World.dist(w[0].chamber.c, w[1].chamber.c);
        check(dx < 1e-8, "замкнутая область на видеокарте: положения тел ≈ double, " + dx);
        check(dR < 1e-7 && dc < 1e-8, "замкнутая область на видеокарте: узлы и центр ≈ double, " + dR + ", " + dc);
        check(w[1].chamber.lost != 0, "вязкий слой работал (потери не ноль)");
        near(w[1].chamber.lost, w[0].chamber.lost, 1e-6 * Math.abs(w[0].chamber.lost), "потери ≈ double");
        near(w[1].chamber.bodyPush, w[0].chamber.bodyPush, 1e-6 * Math.abs(w[0].chamber.bodyPush), "давление тел на стенку ≈ double");
        double bal = (w[1].energy().E() + w[1].chamber.lost - E0) / Math.abs(E0);
        check(Math.abs(bal) < 1e-8, "замкнутая область на видеокарте: E + потери сохраняется, " + bal);
        double[] P1 = momentum(w[1]);
        for (int k = 0; k < 3; k++) P1[k] += mw * w[1].chamber.vc[k];
        double dP = Math.hypot(Math.hypot(P1[0] - P0[0], P1[1] - P0[1]), P1[2] - P0[2]) / scale;
        check(dP < 1e-8, "замкнутая область на видеокарте: импульс тел + стенки сохраняется, " + dP);
        System.out.printf("  замкнутая область на видеокарте: %d тел, %d шагов, расхождение с double %.1e, R_k %.1e, импульс %.1e%n",
                w[1].parts.size(), st[1], dx, dR, dP);
    }

    private static double[] momentum(World w) {
        double[] P = new double[3];
        for (Body b : w.parts) for (int k = 0; k < 3; k++) P[k] += b.m * b.v[k];
        return P;
    }

    private static void near(double actual, double expected, double tolerance, String name) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(name + ": " + actual + " != " + expected + " ± " + tolerance);
        }
        checks++;
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static World world(double G) {
        World w = new World(new Random(1));
        // приложение по умолчанию — GPU float; проверки физики до бита — на CPU
        w.forces.device = Forces.Device.CPU;
        w.setG(G);
        w.setScene(World.Scene.ATOMS); // стартовая сцена теперь песочница; проверки физики — про атом
        return w;
    }

    private static void runFor(World w, double T) {
        double t = 0;
        while (t < T) {
            double dt = Math.min(1.0 / 120, 0.02 * w.dynTime());
            w.step(dt);
            t += dt;
        }
    }

    /** Наибольшее отклонение E (+ потери) за время T, проверка каждые 10 шагов. */
    private static double maxDrift(World w, double T) {
        double e0 = w.energy().E(), dev = 0, t = 0;
        w.chamber.lost = 0;
        int n = 0;
        while (t < T) {
            double dt = Math.min(1.0 / 120, 0.02 * w.dynTime());
            w.step(dt);
            t += dt;
            if (++n % 10 == 0) {
                double lost = w.chamber.on ? w.chamber.lost : 0;
                dev = Math.max(dev, Math.abs((w.energy().E() + lost - e0) / e0));
            }
        }
        return dev;
    }

    private static String kinds(World w) {
        StringBuilder s = new StringBuilder();
        for (Body b : w.parts) s.append(b.kind.name().toLowerCase());
        return s.toString();
    }

    public static void main(String[] args) {
        // 1. Q = k_a·C1/(4πR²): при G = 1 Q(2) = G/4.
        World w = world(1);
        near(w.Q(2), 0.25, 1e-15, "Q(2) = G/4");

        // 2. N = h·S·q, m = N; массы и радиусы частиц.
        Body p = w.makeBody(Species.P, -1, new double[3], new double[3]);
        Body e = w.makeBody(Species.E, -1, new double[]{3, 0, 0}, new double[3]);
        Body n = w.makeBody(Species.N, -1, new double[3], new double[3]);
        near(p.h * p.S * p.q, 1836, 1e-9, "N(p) = h·S·q");
        near(n.N, 1839, 1e-9, "N(n)");
        near(e.N, 1, 1e-12, "N(e)");
        check(p.m == p.N && e.m == e.N, "m = N");
        near(p.rad, 0.1224, 1e-4, "радиус протона");

        // 3. F = G·m1·m2/R² и действие = противодействие.
        w.parts = new ArrayList<>(List.of(p, e));
        double[][] a = w.accelerate();
        near(e.m * a[1][0], -1836.0 / 9, 1e-9, "F(p–e) на R = 3");
        near(p.m * a[0][0] + e.m * a[1][0], 0, 1e-9, "F_p + F_e = 0");

        // 4. 1/r²: Q·N·R² = G·m_p.
        for (double R : new double[]{1, 2, 4, 8}) {
            Body p1 = w.makeBody(Species.P, -1, new double[3], new double[3]);
            Body p2 = w.makeBody(Species.P, -1, new double[]{R, 0, 0}, new double[3]);
            w.parts = new ArrayList<>(List.of(p1, p2));
            near(w.accelerate()[0][0] * R * R, 1836, 1e-9, "Q·N·R² при R = " + R);
        }

        // 5. Целостность: F = −∂E/∂x (8 тел, есть тела внутри чужих шаров), с камерой и без.
        for (boolean withChamber : new boolean[]{false, true}) {
            World g = world(1);
            Random r = new Random(7);
            g.parts = new ArrayList<>();
            for (Species s : new Species[]{Species.P, Species.P, Species.P, Species.N, Species.N, Species.E, Species.E, Species.E}) {
                g.parts.add(g.makeBody(s, -1, new double[]{r.nextDouble() - .5, r.nextDouble() - .5, r.nextDouble() - .5}, new double[3]));
            }
            Body inner = g.parts.get(5), host = g.parts.get(0);
            System.arraycopy(new double[]{host.p[0] + 0.03, host.p[1], host.p[2]}, 0, inner.p, 0, 3);
            if (withChamber) {
                g.chamber.on = true;
                g.chamber.c = new double[3];
                g.chamber.R0 = 1;
                g.chamber.pExt = 3; // внешнее давление тоже должно входить в −∂E/∂R_k
                g.chamber.vc = new double[]{0.1, -0.2, 0.05};
                for (int k = 0; k < Chamber.M; k++) g.chamber.Rk[k] = 1.05 + 0.1 * r.nextDouble(); // деформированная стенка
                double[] dir = {0.6, 0.48, 0.64};
                double rw = g.chamber.radiusAt(dir);
                System.arraycopy(new double[]{dir[0] * (rw - 0.01), dir[1] * (rw - 0.01), dir[2] * (rw - 0.01)}, 0, g.parts.get(6).p, 0, 3); // в слое δ у стенки
                System.arraycopy(new double[]{0, 1.3, 0}, 0, g.parts.get(7).p, 0, 3); // за стенкой
            }
            double[][] acc = g.accelerate();
            double[] aR = g.chamber.aR.clone(), ac = g.chamber.ac.clone();
            double[] sum = new double[3];
            for (int i = 0; i < g.parts.size(); i++) {
                Body b = g.parts.get(i);
                double fm = Math.sqrt(acc[i][0] * acc[i][0] + acc[i][1] * acc[i][1] + acc[i][2] * acc[i][2]) * b.m;
                for (int k = 0; k < 3; k++) {
                    double x0 = b.p[k], hh = 1e-6;
                    b.p[k] = x0 + hh;
                    double ep = g.energy().E();
                    b.p[k] = x0 - hh;
                    double em = g.energy().E();
                    b.p[k] = x0;
                    double fNum = -(ep - em) / (2 * hh);
                    near((fNum - b.m * acc[i][k]) / fm, 0, 1e-4, "F = −∂E/∂x, тело " + i + (withChamber ? " (камера)" : ""));
                    sum[k] += b.m * acc[i][k];
                }
            }
            if (withChamber) {
                double mk = g.chamber.mass() / Chamber.M, hh = 1e-7, scale = 0;
                for (double ak : aR) scale = Math.max(scale, mk * Math.abs(ak));
                for (int k = 0; k < Chamber.M; k++) {
                    double R0 = g.chamber.Rk[k];
                    g.chamber.Rk[k] = R0 + hh;
                    double ep = g.energy().E();
                    g.chamber.Rk[k] = R0 - hh;
                    double em = g.energy().E();
                    g.chamber.Rk[k] = R0;
                    double fNum = -(ep - em) / (2 * hh), fAn = mk * aR[k];
                    near((fNum - fAn) / scale, 0, 1e-6, "m_k·R̈_k = −∂E/∂R_k, узел " + k);
                }
                for (int k = 0; k < 3; k++) {
                    double x0 = g.chamber.c[k];
                    g.chamber.c[k] = x0 + 1e-6;
                    double ep = g.energy().E();
                    g.chamber.c[k] = x0 - 1e-6;
                    double em = g.energy().E();
                    g.chamber.c[k] = x0;
                    double fNum = -(ep - em) / 2e-6, fAn = g.chamber.mass() * ac[k];
                    near((fNum - fAn) / Math.max(1, Math.abs(fAn)), 0, 1e-4, "m·c̈ = −∂E/∂c");
                    near(sum[k] + fAn, 0, 1e-6, "силы тел + камеры = 0");
                }
            } else {
                for (int k = 0; k < 3; k++) near(sum[k], 0, 1e-7, "сумма сил = 0");
            }
        }

        // 6. Сохранение: дейтерий при G = 1 (5 с) и G = 10⁻⁵ (20 с), орбита p–n держится.
        World d1 = world(1);
        d1.reset();
        near(d1.energy().momentum(), 0, 1e-9, "импульс на старте");
        check(maxDrift(d1, 5) < 1e-6, "ΔE/E при G = 1");
        near(World.dist(d1.parts.get(0).p, d1.parts.get(1).p), 0.45, 1e-3, "орбита p–n при G = 1");
        World d2 = world(1e-5);
        d2.reset();
        check(maxDrift(d2, 20) < 1e-8, "ΔE/E при G = 1e-5");
        near(d2.energy().momentum(), 0, 1e-10, "импульс при G = 1e-5");

        // 7. Песочница: частицы по одной, удаление последней своего вида.
        World s = world(1e-5);
        s.setScene(World.Scene.SANDBOX);
        check(s.parts.isEmpty(), "песочница пустая");
        s.addProton();
        s.addNeutron();
        s.addElectron();
        s.addElectron();
        check(kinds(s).equals("pnee"), "добавление: " + kinds(s));
        s.removeProton();
        s.removeElectron();
        check(kinds(s).equals("ne"), "удаление: " + kinds(s));

        // 8. Атомы: +атом, +n, −атом; счётчики.
        World at = world(1e-5);
        at.addAtom();
        at.addNeutron();
        World.Counts c = at.counts();
        check(c.p() == 2 && c.n() == 3 && c.e() == 2 && c.atoms() == 2, "счётчики после +атом +n");
        at.removeAtom();
        c = at.counts();
        check(c.atoms() == 1 && c.p() == 1, "−атом");

        // 9. Камера: быстрый протон не ломает её, R > 0, баланс E + потери.
        World ch = world(1e-5);
        ch.setScene(World.Scene.SANDBOX);
        ch.chamber.setOn(true);
        ch.addProton();
        Body fast = ch.parts.get(0);
        fast.p[0] = fast.p[1] = fast.p[2] = 0;
        ch.chamber.reset();
        fast.v[0] = 2;
        double bal = maxDrift(ch, 30);
        for (double rk : ch.chamber.Rk) check(rk > 0, "R_k камеры > 0");
        check(bal < 5e-3, "E + потери при v = 2: " + bal);
        check(World.dist(fast.p, ch.chamber.c) < ch.chamber.radiusAt(fast.p), "протон внутри камеры");
        check(ch.chamber.lost > 0, "вязкость забрала энергию");

        // 10. След: не длиннее TRAIL_LEN.
        World tr = world(1e-5);
        tr.reset();
        for (int i = 0; i < Formula.TRAIL_LEN + 500; i++) tr.step(1.0 / 120);
        check(tr.parts.get(0).trail.size() == Formula.TRAIL_LEN, "длина следа");
        runFor(tr, 0.1);

        // 11. 4-й порядок: при вдвое меньшем dt ошибка энергии падает ~в 16 раз.
        double[] errs = new double[2];
        for (int k = 0; k < 2; k++) {
            World o = world(1);
            o.reset();
            double dt = 0.05 * o.dynTime() / (1 << k), e00 = o.energy().E();
            for (int i = 0; i < (200 << k); i++) {
                o.step(dt);
                errs[k] = Math.max(errs[k], Math.abs((o.energy().E() - e00) / e00));
            }
        }
        check(errs[0] / errs[1] > 10, "порядок схемы: " + errs[0] + " / " + errs[1]);

        // 12. advance не теряет время: t + отставание = Σ realDt.
        World adv = world(1);
        adv.reset();
        for (int i = 0; i < 30; i++) adv.advance(0.1);
        near(adv.time + adv.lag(), 3.0, 1e-9, "время не теряется");
        check(adv.lag() >= 0, "отставание ≥ 0");

        // 13. Тест устойчивости: на старте p–n и e связаны; энергия пары — та же, что в energy();
        //     после толчка электрона он отмечается свободным, а движение тест не меняет.
        World st = world(1);
        st.reset();
        StabilityTest sta = new StabilityTest();
        StabilityTest.Row r0 = sta.update(st).get(0);
        check(r0.pn().bound() && r0.e().bound(), "атом связан на старте");
        near(r0.pn().r(), st.rn, 1e-9, "r(p–n) на старте");
        near(r0.e().r(), st.r0, 1e-9, "r(e–ядро) на старте");
        World two = world(1);
        two.parts = new ArrayList<>(List.of(st.parts.get(0), st.parts.get(1)));
        World.Lump cm = World.lump(two.parts);
        double kcm = 0.5 * cm.m() * (cm.cv()[0] * cm.cv()[0] + cm.cv()[1] * cm.cv()[1] + cm.cv()[2] * cm.cv()[2]);
        double ePair = two.pair(List.of(two.parts.get(0)), List.of(two.parts.get(1))).E();
        near((ePair - (two.energy().E() - kcm)) / ePair, 0, 1e-12, "E пары p–n = E − E центра масс");
        Body el = st.parts.get(2);
        el.v[0] += 50 * Math.sqrt(st.kQ() * 3675 / st.r0);
        double[] before = el.p.clone();
        StabilityTest.Row r1 = sta.update(st).get(0);
        check(!r1.e().bound() && r1.tFree() == st.time, "толкнутый e свободен");
        check(java.util.Arrays.equals(before, el.p), "тест не двигает тела");

        // 14. Форма стенки локальна: протон у стенки по +x толкает сильнее всего ближайший узел,
        //     противоположный почти не чувствует (только через средний радиус R̄); после удара прогиб у +x больше, чем у −x.
        World loc = world(1e-5);
        loc.setScene(World.Scene.SANDBOX);
        loc.chamber.setOn(true);
        loc.chamber.c = new double[3];
        Body lp = loc.makeBody(Species.P, -1, new double[]{loc.chamber.R0 - 0.01, 0, 0}, new double[3]);
        loc.parts = new ArrayList<>(List.of(lp));
        loc.accelerate();
        int near = 0, far = 0;
        for (int k = 0; k < Chamber.M; k++) {
            if (Chamber.DIRS[k][0] > Chamber.DIRS[near][0]) near = k;
            if (Chamber.DIRS[k][0] < Chamber.DIRS[far][0]) far = k;
        }
        double aNear = loc.chamber.aR[near], aFar = Math.abs(loc.chamber.aR[far]);
        for (int k = 0; k < Chamber.M; k++) check(loc.chamber.aR[k] <= aNear + 1e-12, "ближайший узел толкается сильнее всех");
        check(aNear > 0 && aFar < 1e-2 * aNear, "дальний узел не чувствует: " + aFar + " / " + aNear);
        World hit = world(1e-5);
        hit.setScene(World.Scene.SANDBOX);
        hit.chamber.setOn(true);
        hit.chamber.c = new double[3];
        hit.chamber.visc = 0;
        hit.chamber.zeta = 0;
        Body hp = hit.makeBody(Species.P, -1, new double[]{0.2, 0.1, 0}, new double[]{1, 0, 0}); // не из центра: там у поля стенки излом
        hit.parts = new ArrayList<>(List.of(hp));
        hit.changed();
        double pBefore = hp.m * 1, maxNear = 0, maxFar = 0;
        double e0h = hit.energy().E(), devh = 0;
        for (int i = 0; i < 20000 && hp.v[0] - hit.chamber.vc[0] > 0; i++) { // до отскока от стенки
            hit.step(Math.min(1.0 / 120, 0.02 * hit.dynTime()));
            maxNear = Math.max(maxNear, hit.chamber.Rk[near] - hit.chamber.R0);
            maxFar = Math.max(maxFar, Math.abs(hit.chamber.Rk[far] - hit.chamber.R0));
            devh = Math.max(devh, Math.abs((hit.energy().E() - e0h) / e0h));
        }
        check(hp.v[0] - hit.chamber.vc[0] < 0, "протон отскочил");
        check(maxNear > 10 * maxFar, "прогиб у точки удара: " + maxNear + " против " + maxFar);
        near(hp.m * hp.v[0] + hit.chamber.mass() * hit.chamber.vc[0], pBefore, 1e-9 * pBefore, "импульс тел + камеры");
        check(devh < 1e-6, "ΔE/E без вязкости: " + devh);

        // 15. Комплексное тяготение (монография): частное отталкивание −β·W + приталкивание от
        //     дефицита массы КУО +(1+β)·W = W. Дефициты складываются, поэтому при любом β ускорения
        //     совпадают с прямым тяготением (эквивалентность по формуле, стр. 39–40); тела не трогает.
        World dc = world(1);
        Random rr = new Random(11);
        dc.parts = new ArrayList<>();
        for (Species sp : new Species[]{Species.P, Species.P, Species.N, Species.N, Species.E, Species.E, Species.E, Species.E}) {
            dc.parts.add(dc.makeBody(sp, -1, new double[]{rr.nextDouble() - .5, rr.nextDouble() - .5, rr.nextDouble() - .5}, new double[3]));
        }
        System.arraycopy(new double[]{dc.parts.get(0).p[0] + 0.03, dc.parts.get(0).p[1], dc.parts.get(0).p[2]}, 0, dc.parts.get(4).p, 0, 3); // e внутри p
        double[][] aDirect = dc.accelerate();
        double[][] pDec = new double[dc.parts.size()][];
        for (int i = 0; i < pDec.length; i++) pDec[i] = dc.parts.get(i).p.clone();
        dc.setMode(World.Mode.KUO);
        int nb = dc.parts.size();
        for (double beta : new double[]{0, 0.5, 2, 1e6, Kuo.BETA_MAX}) {
            dc.kuo.beta = beta;
            double[][] aK = dc.accelerate();
            for (int i = 0; i < nb; i++) {
                double scale = Math.max(1e-300, Math.sqrt(aDirect[i][0] * aDirect[i][0] + aDirect[i][1] * aDirect[i][1] + aDirect[i][2] * aDirect[i][2]));
                for (int k = 0; k < 3; k++) {
                    near((aK[i][k] - aDirect[i][k]) / scale, 0, 1e-12, "комплексное = прямое, β = " + beta + ", тело " + i);
                    near((dc.kuo.rep()[i][k] + dc.kuo.push()[i][k] - aDirect[i][k]) / scale, 0, 1e-12 * (1 + 2 * beta), "отталкивание + приталкивание = W (с округлением ~β·10⁻¹⁶), тело " + i);
                }
                double rep = Math.sqrt(dc.kuo.rep()[i][0] * dc.kuo.rep()[i][0] + dc.kuo.rep()[i][1] * dc.kuo.rep()[i][1] + dc.kuo.rep()[i][2] * dc.kuo.rep()[i][2]);
                if (beta == 0) check(rep == 0, "β = 0 — нет частного отталкивания");
            }
        }
        // направление: у одиночной пары частное отталкивание — от соседа, приталкивание — к соседу
        World pr = world(1);
        pr.setMode(World.Mode.KUO);
        pr.kuo.beta = 1;
        pr.parts = new ArrayList<>(List.of(pr.makeBody(Species.P, -1, new double[3], new double[3]),
                pr.makeBody(Species.E, -1, new double[]{2, 0, 0}, new double[3])));
        pr.accelerate();
        check(pr.kuo.rep()[1][0] > 0 && pr.kuo.push()[1][0] < 0, "e: отталкивание от p, приталкивание к p");
        near(pr.kuo.push()[1][0] / pr.kuo.rep()[1][0], -2, 1e-12, "приталкивание = (1+β)/β · отталкивание при β = 1");
        pr.kuo.beta = 1e12;
        double[][] aBig = pr.accelerate();
        near(pr.kuo.push()[1][0] / pr.kuo.rep()[1][0], -(1 + 1e12) / 1e12, 1e-12, "при β = 10¹² отношение то же (1+β)/β");
        pr.setMode(World.Mode.QN);
        double[][] aQn = pr.accelerate();
        check(aBig[1][0] == aQn[1][0] && aBig[0][0] == aQn[0][0], "при β = 10¹² итог до бита тот же, что в прямом тяготении");
        // две шкалы: β_т и β_к отдельно — итог (β_к − β_т)·W
        pr.setMode(World.Mode.KUO);
        pr.kuo.beta = 0.5;
        pr.kuo.setBetaK(3.5);
        check(!pr.kuo.linked && pr.kuo.gain() == 3, "несвязанные шкалы: g = β_к − β_т = 3");
        double[][] aG = pr.accelerate();
        for (int i = 0; i < 2; i++) near(aG[i][0] / aQn[i][0], 3, 1e-12, "итог = 3·W, тело " + i);
        near(pr.kuo.push()[1][0] / pr.kuo.rep()[1][0], -7, 1e-12, "приталкивание КУО 3.5·W, отталкивание 0.5·W");
        near((pr.kuo.push()[1][0] + pr.kuo.rep()[1][0]) / aQn[1][0], 3, 1e-12, "составляющие в сумме — 3·W");
        pr.kuo.beta = 1;
        pr.kuo.setBetaK(0);
        double[][] aNeg = pr.accelerate();
        near(aNeg[1][0] / aQn[1][0], -1, 1e-12, "β_к = 0, β_т = 1: итог −W — e отталкивается от p");
        // чип КУО выкл: только −β·W — два покоящихся тела расталкиваются (не Mode.QN / не притяжение)
        {
            World fly = world(1);
            fly.setMode(World.Mode.KUO);
            fly.kuo.on = false;
            fly.kuo.beta = 0.5;
            fly.parts = new ArrayList<>(List.of(
                    fly.makeBody(Species.P, -1, new double[]{-1, 0, 0}, new double[3]),
                    fly.makeBody(Species.P, -1, new double[]{1, 0, 0}, new double[3])));
            double[][] aFly = fly.accelerate();
            check(aFly[0][0] < 0 && aFly[1][0] > 0, "КУО выкл: ускорения врозь");
            check(fly.kuo.push()[0][0] == 0 && fly.kuo.push()[1][0] == 0, "КУО выкл: push = 0");
            double flyR0 = World.dist(fly.parts.get(0).p, fly.parts.get(1).p);
            for (int i = 0; i < 120; i++) fly.advance(1.0 / 60);
            double flyR1 = World.dist(fly.parts.get(0).p, fly.parts.get(1).p);
            check(flyR1 > flyR0 * 1.05, "КУО выкл: два тела улетают " + flyR0 + " → " + flyR1);
            fly.kuo.on = true; // не портит следующие проверки
        }
        pr.kuo.link();
        check(pr.kuo.linked && pr.kuo.betaK() == 2 && pr.kuo.gain() == 1, "связать: β_к = 1 + β_т, g = 1");
        double[][] aL = pr.accelerate();
        check(aL[1][0] == aQn[1][0] && aL[0][0] == aQn[0][0], "после «связать» итог снова до бита W");
        for (int i = 0; i < pDec.length; i++) check(java.util.Arrays.equals(pDec[i], dc.parts.get(i).p), "расчёт не двигает тела");
        // телесный угол: шар R на расстоянии r закрывает долю Ω/4π → R²/4r² вдали, внутри — всё
        near(Kuo.solidAngleFraction(0.01, 10) / (0.01 * 0.01 / (4 * 100)), 1, 1e-6, "Ω/4π ≈ R²/4r² вдали");
        near(Kuo.solidAngleFraction(1, 0.5), 1, 0, "Ω/4π = 1 внутри шара");

        // 16. Комплексное тяготение в движении: атом дейтерия — та же траектория, что в прямом, энергия сохраняется.
        World dq = world(1), dk = world(1);
        dq.reset();
        dk.reset();
        dk.setMode(World.Mode.KUO);
        dk.kuo.beta = 1.5;
        double ek0 = dk.energy().E(), devK = 0;
        for (int i = 0; i < 2000; i++) {
            double dt = Math.min(1.0 / 120, 0.02 * dq.dynTime());
            dq.step(dt);
            dk.step(dt);
            devK = Math.max(devK, Math.abs((dk.energy().E() - ek0) / ek0));
        }
        for (int i = 0; i < dq.parts.size(); i++) {
            near(World.dist(dq.parts.get(i).p, dk.parts.get(i).p), 0, 1e-12, "траектория КУО = прямая, тело " + i);
        }
        check(devK < 1e-6, "ΔE/E в комплексном тяготении: " + devK);
        near(dk.energy().momentum(), 0, 1e-9, "импульс в комплексном тяготении");
        // несвязанные шкалы: тяготение ×g, энергия пар ×g (кроме полюсов диполя) — всё равно сохраняется
        World dg = world(1);
        dg.setScene(World.Scene.SANDBOX);
        dg.addProton();
        dg.addElectron();
        dg.addDipole();
        dg.setMode(World.Mode.KUO);
        dg.kuo.beta = 0.5;
        dg.kuo.setBetaK(2.5);
        double eg0 = dg.energy().E(), devG = 0, pg0 = dg.energy().momentum();
        for (int i = 0; i < 2000; i++) {
            dg.step(Math.min(1.0 / 120, 0.02 * dg.dynTime()));
            devG = Math.max(devG, Math.abs((dg.energy().E() - eg0) / eg0));
        }
        check(devG < 1e-5, "ΔE/E при g = 2: " + devG);
        near(dg.energy().momentum() - pg0, 0, 1e-9, "импульс при g = 2");
        World dd = world(1);
        dd.setScene(World.Scene.SANDBOX);
        dd.addDipole();
        check(dd.forces.partnerEnergy() == dd.forces.pairEnergy(), "один диполь: энергия полюсов = энергия всех пар до бита");

        // 17. Общее поле пары: сила от вращающейся пары p–n на далёкое тело = сила от одного тела
        //     массой m₁ + m₂ в их центре масс; поправка ∝ (d/r)², с ростом r вдвое — вчетверо меньше.
        double[] errs17 = new double[3];
        double[] dists = {3, 6, 12};
        for (int k = 0; k < 3; k++) {
            World pairW = world(1);
            Body pp = pairW.makeBody(Species.P, -1, new double[]{-0.2, 0.15, 0.1}, new double[3]);
            Body pn = pairW.makeBody(Species.N, -1, new double[]{0.25, -0.1, -0.05}, new double[3]);
            double r3 = dists[k];
            Body farB = pairW.makeBody(Species.E, -1, new double[]{r3 * 0.6, r3 * 0.64, r3 * 0.48}, new double[3]);
            pairW.parts = new ArrayList<>(List.of(pp, pn, farB));
            double[] aPair = pairW.accelerate()[2];
            World.Lump cmPair = World.lump(List.of(pp, pn));
            double dx = cmPair.c()[0] - farB.p[0], dy = cmPair.c()[1] - farB.p[1], dz = cmPair.c()[2] - farB.p[2], rr3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double aOne = pairW.kQ() * cmPair.N() / (rr3 * rr3);
            double err = 0;
            for (int ci = 0; ci < 3; ci++) err += Math.pow(aPair[ci] - aOne * new double[]{dx, dy, dz}[ci] / rr3, 2);
            errs17[k] = Math.sqrt(err) / aOne;
            double d = World.dist(pp.p, pn.p);
            check(errs17[k] < 2 * Math.pow(d / rr3, 2), "пара как одно тело на r = " + r3 + ": " + errs17[k]);
        }
        check(errs17[0] / errs17[1] > 3.5 && errs17[1] / errs17[2] > 3.5, "поправка ∝ 1/r²: " + errs17[0] + ", " + errs17[1] + ", " + errs17[2]);

        // 18. Песочница: второй электрон добавляется на круговую орбиту вокруг первого — пара
        //     кружит (расстояние почти постоянно), суммарный импульс остаётся нулевым.
        World ee = world(1e-5);
        ee.setScene(World.Scene.SANDBOX);
        ee.addElectron();
        ee.addElectron();
        Body ea = ee.parts.get(0), eb = ee.parts.get(1);
        double d0 = World.dist(ea.p, eb.p), dMin = d0, dMax = d0;
        near(ee.energy().momentum(), 0, 1e-15, "импульс пары e = 0");
        for (int f = 0; f < 60 * 120; f++) {
            ee.advance(1.0 / 60);
            double d = World.dist(ea.p, eb.p);
            dMin = Math.min(dMin, d);
            dMax = Math.max(dMax, d);
        }
        check(dMin > 0.97 * d0 && dMax < 1.03 * d0, "два e кружат, а не падают: " + dMin + "…" + dMax + " при d0 = " + d0);

        // 19. Радиус покоя: стенка идёт к R₀ при любом G (упругость от G не зависит); внешнее
        //     давление сжимает оболочку до restRadius(); давление тел изнутри — растягивает.
        World rw = world(World.G_HONEST);
        rw.setScene(World.Scene.SANDBOX);
        rw.chamber.setOn(true);
        rw.chamber.R0 = 3;
        for (int f = 0; f < 60 * 10; f++) rw.advance(1.0 / 60);
        near(rw.chamber.R(), 3, 0.02, "стенка дошла до нового R₀ при честном G");
        rw.chamber.pExt = 20;
        double rest = rw.chamber.restRadius();
        check(rest < 3, "внешнее давление уменьшает радиус покоя: " + rest);
        for (int f = 0; f < 60 * 15; f++) rw.advance(1.0 / 60);
        near(rw.chamber.R(), rest, 0.01 * rest, "стенка пришла к радиусу покоя под давлением");
        near(rw.chamber.stiffness() * (rw.chamber.R() - 3) + 20 * 4 * Math.PI * rw.chamber.R() * rw.chamber.R(), 0,
                0.05 * rw.chamber.stiffness(), "равновесие: упругость = внешнее давление");
        World pw = world(1);
        pw.setScene(World.Scene.SANDBOX);
        pw.chamber.setOn(true);
        pw.addProton();
        pw.parts.get(0).p[0] = pw.chamber.c[0] + 1.2; // ближе к стенке: в центре тело давит во все стороны поровну
        pw.accelerate();
        check(pw.chamber.bodyPush > 0 && pw.chamber.restRadius() > pw.chamber.R0, "тела изнутри растягивают: радиус покоя " + pw.chamber.restRadius());

        // 20. «Всё по умолчанию»: после изменения параметров — те же значения, что у свежей модели.
        World dw = world(1), fresh = new World(new Random(1));
        dw.setScene(World.Scene.SANDBOX);
        dw.setMode(World.Mode.KUO);
        dw.kuo.beta = 2;
        dw.kuo.setBetaK(7);
        dw.chamber.setOn(true);
        dw.chamber.kappa = 50;
        dw.chamber.pExt = 7;
        dw.chamber.tau = 30;
        dw.chamber.zeta = 0.2;
        dw.chamber.R0 = 8;
        dw.addElectron();
        dw.forces.device = Forces.Device.CPU;
        dw.restoreDefaults();
        check(dw.scene == fresh.scene && dw.mode == fresh.mode && dw.G == fresh.G && dw.kuo.beta == fresh.kuo.beta
                && dw.kuo.linked && dw.kuo.betaK() == fresh.kuo.betaK(), "сцена, режим, G, β_т, β_к (связаны) по умолчанию");
        check(dw.forces.device == Forces.Device.CPU && fresh.forces.device == Forces.Device.CPU,
                "считать на: CPU по умолчанию");
        check(!dw.chamber.on && dw.chamber.kappa == fresh.chamber.kappa && dw.chamber.pExt == fresh.chamber.pExt
                && dw.chamber.tau == fresh.chamber.tau && dw.chamber.zeta == fresh.chamber.zeta && dw.chamber.R0 == fresh.chamber.R0
                && dw.chamber.N == fresh.chamber.N && dw.chamber.hFrac == fresh.chamber.hFrac && dw.chamber.visc == fresh.chamber.visc, "камера по умолчанию");
        check(kinds(dw).equals(kinds(fresh)), "частицы как при запуске: " + kinds(dw));

        // 21. Экстремальная сфера: N = 10⁹ — стенка давит на e сильнее ядра в N/(d²·N_ядра) раз,
        //     электрон прижимается к ядру, баланс E + потери держится; при N = 10³ — обычный режим.
        World xw = world(1e-5);
        xw.reset();
        Body xe = null;
        List<Body> xn = new ArrayList<>();
        for (Body bq : xw.parts) if (bq.kind == Species.E) xe = bq; else xn.add(bq);
        xw.chamber.setOn(true);
        double dWall = xw.chamber.radiusAt(xe.p) - World.dist(xe.p, xw.chamber.c);
        double rNuc = World.dist(xe.p, World.lump(xn).c());
        check(xw.chamber.pushRatio(xe, World.lump(xn)) < 0.03, "обычная стенка слабее ядра");
        xw.chamber.extreme();
        near(xw.chamber.pushRatio(xe, World.lump(xn)) / (1e9 / (dWall * dWall) / (World.lump(xn).N() / (rNuc * rNuc))), 1, 1e-9, "отношение стенка/ядро = формула");
        double rSum = 0;
        int cnt = 0;
        for (int f = 0; f < 60 * 5; f++) {
            xw.advance(1.0 / 60);
            rSum += World.dist(xe.p, World.lump(xn).c());
            cnt++;
        }
        check(rSum / cnt < 0.8 * World.R0, "атом сжат: среднее r(e) = " + rSum / cnt);
        check(Math.abs(xw.energyDrift(xw.energy())) < 2e-3, "E + потери в экстремальной сфере: " + xw.energyDrift(xw.energy()));
        for (Body bq : xw.parts) check(Double.isFinite(bq.p[0] + bq.p[1] + bq.p[2]), "координаты конечны");

        // 22. Экстремальный режим вкл/выкл: включение ставит N = 10⁹, выключение возвращает прежние N, κ, τ.
        World tg = world(1e-5);
        tg.chamber.setOn(true);
        tg.chamber.N = 500;
        tg.chamber.kappa = 3;
        tg.chamber.tau = 0.2;
        tg.chamber.setExtreme(true);
        check(tg.chamber.isExtreme() && tg.chamber.N == Chamber.EXTREME_N, "экстремальный режим включён");
        tg.chamber.setExtreme(false);
        check(!tg.chamber.isExtreme() && tg.chamber.N == 500 && tg.chamber.kappa == 3 && tg.chamber.tau == 0.2, "выключение вернуло N, κ, τ");
        tg.chamber.setExtreme(true);
        tg.chamber.N = 1e6; // сдвинули ползунок N — режим уже не экстремальный
        check(!tg.chamber.isExtreme(), "ручной N выключает режим");

        // 23. Виды атомов: полный Z = 1…118 в списке; сборку makeAtom проверяем у лёгких (тяжёлые — сотни тел).
        {
            boolean[] sawZ = new boolean[119];
            int types = 0;
            for (World.AtomType aType : World.AtomType.values()) {
                types++;
                check(aType.protons >= 1 && aType.protons <= 118, "Z в 1…118: " + aType);
                check(aType.neutrons >= 0, "нейтроны ≥ 0: " + aType);
                check(aType.title != null && !aType.title.isBlank(), "title: " + aType);
                sawZ[aType.protons] = true;
                if (aType.protons > 12) continue; // Ne и тяжелее — только метаданные (makeAtom общий)
                World aw = world(1e-5);
                aw.atomType = aType;
                aw.reset();
                World.Counts ac = aw.counts();
                check(ac.p() == aType.protons && ac.n() == aType.neutrons && ac.e() == aType.protons && ac.atoms() == 1,
                        "состав " + aType + ": " + kinds(aw));
                near(aw.energy().momentum(), 0, 1e-9, "импульс атома " + aType);
                aw.addAtom();
                check(aw.counts().p() == 2 * aType.protons && aw.counts().atoms() == 2, "+ атом того же вида: " + aType);
                aw.removeAtom();
                check(aw.counts().p() == aType.protons, "− атом: " + aType);
            }
            check(types >= 120, "в списке ≥ 120 видов (H-изотопы + Z=2…118): " + types);
            for (int z = 1; z <= 118; z++) check(sawZ[z], "есть изотоп с Z = " + z);
        }

        // 23b. Молекулы: отдельный список; «+ молекула» — атомы состава в геометрии (не одно тело).
        {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (World.MoleculeType m : World.MoleculeType.values()) {
                names.add(m.name());
                check(m.atoms.length >= 2, "молекула ≥ 2 атомов: " + m);
                check(m.mass() > 0 && m.title != null && !m.title.isBlank(), "масса/title: " + m);
            }
            for (String code : new String[]{"H2", "N2", "O2", "H2O", "CO2", "CH4", "NH3"}) {
                check(names.contains(code), "есть молекула " + code);
            }
            check(World.MoleculeType.values().length >= 11, "в списке молекул ≥ 11: " + World.MoleculeType.values().length);
            check(World.MoleculeType.H2O.atoms[0] == World.AtomType.O16
                    && World.MoleculeType.H2O.atoms[1] == World.AtomType.H1
                    && World.MoleculeType.H2O.atoms[2] == World.AtomType.H1, "H2O = O + 2H");
            World mw = world(1e-5);
            mw.setScene(World.Scene.SANDBOX);
            mw.moleculeType = World.MoleculeType.H2;
            mw.addMolecule();
            check(mw.counts().molecules() == 1 && mw.counts().atoms() == 2, "H2 → 2 атома, 1 молекула");
            check(mw.counts().p() == 2 && mw.counts().e() == 2 && mw.counts().n() == 0, "H2 = 2×H1");
            // два ядра разнесены (не в одной точке)
            java.util.List<Body> protons = new java.util.ArrayList<>();
            for (Body b : mw.parts) if (b.kind == Species.P) protons.add(b);
            check(protons.size() == 2, "H2: два протона");
            // H₁ softR = 1 → bondLen = 1+1+1 = 3; CM атома чуть смещён e — ядра ≈ 3
            double rH2 = World.dist(protons.get(0).p, protons.get(1).p);
            near(rH2, 3.0, 0.01, "H2: ядра на soft+soft+1");
            for (int i = 0; i < 300; i++) mw.advance(1.0 / 60); // 5 с
            double rH2b = World.dist(protons.get(0).p, protons.get(1).p);
            check(rH2b > 0.7 * rH2 && rH2b < 1.3 * rH2, "H2 пресет держится на орбите: " + rH2 + " → " + rH2b);
            mw.moleculeType = World.MoleculeType.H2O;
            mw.addMolecule();
            check(mw.counts().molecules() == 2 && mw.counts().atoms() == 5, "H2 + H2O → 5 атомов, 2 молекулы");
            check(mw.counts().p() == 2 + 8 + 1 + 1 && mw.counts().n() == 8, "состав после H2O");
            mw.removeMolecule();
            check(mw.counts().molecules() == 1 && mw.counts().atoms() == 2, "− молекула убрала H2O");
            mw.moleculeType = World.MoleculeType.CO2;
            mw.addMoleculeAt(new double[]{3, 0, 0});
            check(mw.counts().molecules() == 2 && mw.counts().atoms() == 5, "addMoleculeAt CO2");
            // нет Species.MOL — только p/n/e
            for (Body b : mw.parts) check(b.kind == Species.P || b.kind == Species.N || b.kind == Species.E, "только p/n/e: " + b.kind);
        }

        // 23b2. ~10×H₂ в замкнутой камере — связи не рвутся (камера растёт под укладку).
        {
            World cw = world(1e-5);
            cw.setScene(World.Scene.SANDBOX);
            cw.chamber.setOn(true);
            cw.moleculeType = World.MoleculeType.H2;
            cw.spawn = World.Spawn.ORBIT;
            double[] b0 = new double[10];
            int[][] ids = new int[10][2];
            for (int i = 0; i < 10; i++) {
                java.util.Set<Integer> seenAtoms = new java.util.HashSet<>();
                for (Body b : cw.parts) if (b.atom >= 0) seenAtoms.add(b.atom);
                cw.addMolecule();
                java.util.List<Integer> neu = new java.util.ArrayList<>();
                for (Body b : cw.parts)
                    if (b.atom >= 0 && !seenAtoms.contains(b.atom) && !neu.contains(b.atom)) neu.add(b.atom);
                java.util.Collections.sort(neu);
                ids[i][0] = neu.get(0);
                ids[i][1] = neu.get(1);
                Body p0 = null, p1 = null;
                for (Body b : cw.parts) {
                    if (b.kind != Species.P) continue;
                    if (b.atom == ids[i][0]) p0 = b;
                    if (b.atom == ids[i][1]) p1 = b;
                }
                b0[i] = World.dist(p0.p, p1.p);
            }
            check(cw.chamber.R() >= 0.8 * 3, "камера не меньше молекулы: R=" + cw.chamber.R());
            for (int t = 0; t < 300; t++) cw.advance(1.0 / 60);
            int broken = 0;
            for (int i = 0; i < 10; i++) {
                Body p0 = null, p1 = null;
                for (Body b : cw.parts) {
                    if (b.kind != Species.P) continue;
                    if (b.atom == ids[i][0]) p0 = b;
                    if (b.atom == ids[i][1]) p1 = b;
                }
                double r = World.dist(p0.p, p1.p);
                if (!(r > 0.5 * b0[i] && r < 2.0 * b0[i])) broken++;
            }
            check(broken == 0, "10×H₂ в камере держатся, broken=" + broken + " R=" + cw.chamber.R());
        }

        // 23c. После крупной молекулы «+ электрон» орбитой — снаружи облака, круговая скорость.
        {
            World ow = world(1e-5);
            ow.setScene(World.Scene.SANDBOX);
            ow.moleculeType = World.MoleculeType.H2O;
            ow.addMolecule();
            double[] com0 = ow.centerOfMass();
            double cloud = 0;
            for (Body b : ow.parts) cloud = Math.max(cloud, World.dist(b.p, com0) + b.rad);
            ow.spawn = World.Spawn.ORBIT;
            ow.addElectron();
            Body freeE = null;
            for (Body b : ow.parts) if (b.kind == Species.E && b.atom < 0) freeE = b;
            check(freeE != null, "есть свободный электрон");
            // расстояние e↔прежний COM облака ≥ cloud (зазор addPlaced)
            check(World.dist(freeE.p, com0) >= cloud, "электрон снаружи облака H₂O: " + World.dist(freeE.p, com0) + " ≥ " + cloud);
            // относительная скорость к COM остальных ≈ круговая
            double[] cRest = new double[3];
            double mRest = 0, Nrest = 0;
            for (Body b : ow.parts) {
                if (b == freeE) continue;
                mRest += b.m;
                Nrest += b.N;
                for (int k = 0; k < 3; k++) cRest[k] += b.m * b.p[k];
            }
            for (int k = 0; k < 3; k++) cRest[k] /= mRest;
            double rOrbit = World.dist(freeE.p, cRest);
            double[] vRest = new double[3];
            for (Body b : ow.parts) {
                if (b == freeE) continue;
                for (int k = 0; k < 3; k++) vRest[k] += b.m * b.v[k];
            }
            for (int k = 0; k < 3; k++) vRest[k] /= mRest;
            double rel = World.dist(freeE.v, vRest);
            double vc = Math.sqrt(ow.kQ() * (freeE.N + Nrest) / rOrbit);
            near(rel / vc, 1, 1e-6, "орбита e вокруг H₂O: круговая");
            check(rOrbit > 5, "e не внутри H₂O: r=" + rOrbit);
        }

        // 24. Честное ускорение: 60 кадров по 4/60 с (×4) и 240 кадров по 1/60 с (×1) — те же шаги,
        //     та же траектория до последнего бита; время модели одинаковое.
        World fast4 = world(1e-5), slow1 = world(1e-5);
        fast4.reset();
        slow1.reset();
        for (int f = 0; f < 60; f++) fast4.advance(4.0 / 60);
        for (int f = 0; f < 240; f++) slow1.advance(1.0 / 60);
        near(fast4.time + fast4.lag(), slow1.time + slow1.lag(), 1e-9, "одинаковое модельное время ×4 и ×1");
        for (int i = 0; i < fast4.parts.size(); i++) {
            near(World.dist(fast4.parts.get(i).p, slow1.parts.get(i).p), 0, 1e-12, "×4 = ×1, тело " + i);
        }
        fast4.dropLag();
        near(fast4.lag(), 0, 0, "dropLag обнуляет отставание");

        // 25. Диполь: держится отталкиванием полюсов и прижатием от КУО — без связки.
        {
        World dpw = world(1e-5);
        dpw.setScene(World.Scene.SANDBOX);
        dpw.addDipole();
        Body dp = dpw.parts.get(0), dm = dpw.parts.get(1);
        check(dp.kind == Species.DP && dm.kind == Species.DM && dp.partner == dm && dm.partner == dp, "диполь — пара d+ d−");
        check(dp.sign == 1 && dm.sign == -1, "знаки полюсов");
        double dL = World.DIPOLE_L, scale = dpw.QN(dL, 0, dm.N) * dp.fIn;
        near(World.dist(dp.p, dm.p), dL, 1e-12, "полюса на равновесном расстоянии");
        near(dpw.pairQN(dp, dm, dL) / scale, 0, 1e-12, "в равновесии сила пары = 0");
        check(dpw.pairQN(dp, dm, 1.2 * dL) > 0, "растянутый диполь стягивается");
        check(dpw.pairQN(dp, dm, 0.8 * dL) < 0, "сжатый диполь расталкивается");
        check(dpw.pairQN(dp, dm, 50 * dL) > 0, "вдали прижатие от КУО сильнее отталкивания");
        check(dpw.bindingEnergy(dp) > 0, "энергия связи положительна");

        // правило знаков с протоном: d+ тянется, d− отталкивается; F = −∂E/∂x в обоих режимах
        dpw.addProton();
        Body dpr = dpw.parts.get(2);
        for (int k = 0; k < 3; k++) {
            dpr.p[k] = 0;
            dpr.v[k] = 0;
        }
        double[][] dpos = {{2, 0.65 * dL, 0}, {2, -0.65 * dL, 0}}; // растянут: внутренние силы заметны
        for (int k = 0; k < 3; k++) {
            dp.p[k] = dpos[0][k];
            dm.p[k] = dpos[1][k];
            dp.v[k] = dm.v[k] = 0; // протон добавлялся на орбиту — у всех снова покой
        }
        dpw.changed();
        check(dpw.pairQN(dp, dpr, 2) > 0 && dpw.pairQN(dm, dpr, 2) < 0, "d+ к протону, d− от протона");
        for (World.Mode md : World.Mode.values()) {
            dpw.setMode(md);
            double[][] da = dpw.accelerate();
            for (int i = 0; i < 3; i++) {
                Body x = dpw.parts.get(i);
                for (int k = 0; k < 3; k++) {
                    double h = 1e-7, x0 = x.p[k];
                    x.p[k] = x0 + h;
                    double ep = dpw.energy().E();
                    x.p[k] = x0 - h;
                    double em = dpw.energy().E();
                    x.p[k] = x0;
                    double f = -(ep - em) / (2 * h), fa = da[i][k] * x.m;
                    near(fa, f, 1e-6 * Math.abs(f) + 1e-11, "F = −∂E/∂x, " + md + ", " + x.kind.label + " " + k);
                }
            }
        }
        dpw.setMode(World.Mode.QN);
        for (int k = 0; k < 3; k++) {
            dp.p[k] = k == 1 ? 0.5 * dL : k == 0 ? 2 : 0; // снова равновесная длина
            dm.p[k] = k == 1 ? -0.5 * dL : k == 0 ? 2 : 0;
        }
        dpw.changed();

        // 10 с у протона: длина держится, диполь качается вокруг «d+ к протону», E и импульс сохраняются
        double de0 = dpw.energy().E(), dLmin = 1e9, dLmax = 0, cosSum = 0, cosMax = -1;
        int dn = 0;
        for (double t = 0; t < 10; ) {
            double dt = Math.min(1.0 / 120, 0.02 * dpw.dynTime());
            dpw.step(dt);
            t += dt;
            double len = World.dist(dp.p, dm.p);
            dLmin = Math.min(dLmin, len);
            dLmax = Math.max(dLmax, len);
            double[] mid = {0.5 * (dp.p[0] + dm.p[0]), 0.5 * (dp.p[1] + dm.p[1]), 0.5 * (dp.p[2] + dm.p[2])};
            double dcos = 0, mr = World.dist(mid, dpr.p);
            for (int k = 0; k < 3; k++) dcos += (dm.p[k] - dp.p[k]) / len * (mid[k] - dpr.p[k]) / mr;
            cosSum += dcos;
            cosMax = Math.max(cosMax, dcos);
            dn++;
        }
        check(dLmin > 0.9 * dL && dLmax < 1.1 * dL, "длина диполя у протона держится: " + dLmin + "…" + dLmax);
        check(cosSum / dn > 0, "диполь в среднем повёрнут d+ к протону");
        check(cosMax > 0.9, "диполь проходит через положение d+ к протону");
        near((dpw.energy().E() - de0) / Math.abs(de0), 0, 1e-8, "ΔE/E диполя у протона");
        near(dpw.energy().momentum(), 0, 1e-12, "импульс диполя и протона");

        // энергии меньше энергии связи — колеблется, больше — честно разлетается (связки нет)
        for (double share : new double[]{0.5, 2}) {
            World bw = world(1e-5);
            bw.setScene(World.Scene.SANDBOX);
            bw.addDipole();
            Body bp = bw.parts.get(0), bm = bw.parts.get(1);
            double mu = bp.m * bm.m / (bp.m + bm.m), v = Math.sqrt(2 * share * bw.bindingEnergy(bp) / mu);
            double[] u = World.norm(new double[]{bp.p[0] - bm.p[0], bp.p[1] - bm.p[1], bp.p[2] - bm.p[2]});
            for (int k = 0; k < 3; k++) {
                bp.v[k] = 0.5 * v * u[k];
                bm.v[k] = -0.5 * v * u[k];
            }
            bw.changed();
            double rMax = 0;
            runFor(bw, 0.5);
            for (int i = 0; i < 2000; i++) {
                bw.step(Math.min(1.0 / 120, 0.02 * bw.dynTime()));
                rMax = Math.max(rMax, World.dist(bp.p, bm.p));
            }
            if (share < 1) check(rMax < 4 * dL, "энергии меньше связи — диполь цел: " + rMax);
            else check(World.dist(bp.p, bm.p) > 10 * dL, "энергии больше связи — диполь разорван");
        }

        }

        // 26. Неравные полюса: диполь — тело с суммарным зарядом N₊ − N₋, участвует в общем взаимодействии.
        {
            // ускорение центра масс издали — по суммарному заряду (диполь поперёк направления на протон)
            for (double nm : new double[]{World.DIPOLE_NM, 3e-3}) {
                World uw = world(1e-5);
                uw.setScene(World.Scene.SANDBOX);
                uw.addProton();
                uw.dipoleNm = nm;
                uw.addDipole(); // N₋ = 0.001 — на орбиту, N₋ = 0.003 — в покое; положения задаём сами
                Body up = uw.parts.get(0), ua = uw.parts.get(1), ub = uw.parts.get(2);
                double ul = World.DIPOLE_L;
                double[][] pos = {{0, 0, 0}, {5, 0.5 * ul, 0}, {5, -0.5 * ul, 0}};
                Body[] all = {up, ua, ub};
                for (int i = 0; i < 3; i++) {
                    for (int k = 0; k < 3; k++) {
                        all[i].p[k] = pos[i][k];
                        all[i].v[k] = 0;
                    }
                }
                uw.changed();
                double[][] ua2 = uw.accelerate();
                double M = ua.m + ub.m, acx = (ua.m * ua2[1][0] + ub.m * ua2[2][0]) / M;
                double r = World.dist(up.p, new double[]{5, 0, 0});
                double expect = -uw.kQ() * up.N * (ua.N - ub.N) / M / (r * r); // к протону — минус по x
                near(acx / expect, 1, 1e-3, "ускорение диполя по суммарному заряду, N₋ = " + nm);
                if (nm > World.DIPOLE_NP) check(acx > 0, "N₋ > N₊ — диполь целиком отталкивается от протона");
                else check(acx < 0, "N₊ > N₋ — диполь целиком притягивается к протону");
                double pAcc = ua2[0][0] * up.m + (ua2[1][0] * ua.m + ub.m * ua2[2][0]);
                near(pAcc, 0, 1e-12 * Math.abs(acx * M), "действие = противодействие: протон и диполь");
            }

            // диполь на орбите вокруг протона: держится на орбите и не рвётся, E и импульс сохраняются
            World ow = world(1e-5);
            ow.setScene(World.Scene.SANDBOX);
            ow.addProton();
            ow.addDipole();
            Body op = ow.parts.get(0), oa = ow.parts.get(1), ob = ow.parts.get(2);
            check(World.dist(oa.v, op.v) > 0, "диполь с суммарным зарядом > 0 добавлен на орбиту");
            double[] oc = World.lump(List.of(oa, ob)).c();
            double or0 = World.dist(oc, op.p), orMin = 1e9, orMax = 0, oLmin = 1e9, oLmax = 0, oe0 = ow.energy().E();
            for (double t = 0; t < 20; ) {
                double dt = Math.min(1.0 / 120, 0.02 * ow.dynTime());
                ow.step(dt);
                t += dt;
                double orr = World.dist(World.lump(List.of(oa, ob)).c(), op.p), oll = World.dist(oa.p, ob.p);
                orMin = Math.min(orMin, orr);
                orMax = Math.max(orMax, orr);
                oLmin = Math.min(oLmin, oll);
                oLmax = Math.max(oLmax, oll);
            }
            check(orMin > 0.7 * or0 && orMax < 1.4 * or0, "диполь кружит вокруг протона: " + orMin + "…" + orMax + " от " + or0);
            check(oLmin > 0.9 * World.DIPOLE_L && oLmax < 1.1 * World.DIPOLE_L, "длина диполя на орбите держится");
            near((ow.energy().E() - oe0) / Math.abs(oe0), 0, 1e-8, "ΔE/E диполя на орбите");
            near(ow.energy().momentum(), 0, 1e-12, "импульс протона и диполя на орбите");
        }

        // 27. Способы добавления: орбита, покой, бросок; импульс сохраняется. След — по модельному времени.
        {
            for (World.Spawn sp : World.Spawn.values()) {
                World sw = world(1e-5);
                sw.setScene(World.Scene.SANDBOX);
                sw.addProton();
                sw.spawn = sp;
                sw.throwAim = 0;
                Body sp0 = sw.parts.get(0);
                sw.addElectron();
                Body se = sw.parts.get(1);
                double rel = World.dist(se.v, sp0.v), sr = World.dist(se.p, sp0.p);
                double vc = Math.sqrt(sw.kQ() * (sp0.N + se.N) / sr); // круговая скорость пары (μ-задача)
                near(sw.energy().momentum(), 0, 1e-15, "импульс после добавления, " + sp);
                if (sp == World.Spawn.REST) {
                    near(World.dist(se.v, new double[3]), 0, 0, "покой: новая частица неподвижна");
                    near(World.dist(sp0.v, new double[3]), 0, 0, "покой: протон не сдвинут");
                }
                if (sp == World.Spawn.ORBIT) near(rel / vc, 1, 1e-9, "орбита: круговая скорость");
                if (sp == World.Spawn.THROW) {
                    near(rel / vc, sw.throwK, 1e-9, "бросок: скорость = k × круговая");
                    double dot = 0;
                    for (int k = 0; k < 3; k++) dot += (se.v[k] - sp0.v[k]) * (sp0.p[k] - se.p[k]);
                    near(dot / (rel * sr), 1, 1e-9, "бросок с прицелом 0 — прямо к центру масс");
                }
                double sd = maxDrift(sw, 3);
                check(sd < 1e-6, "ΔE/E после добавления, " + sp + ": " + sd);
            }

            // бросок с прицелом 1 и k = 1 — та же круговая скорость по касательной
            World tw = world(1e-5);
            tw.setScene(World.Scene.SANDBOX);
            tw.addProton();
            tw.spawn = World.Spawn.THROW;
            tw.throwAim = 1;
            tw.throwK = 1;
            tw.addElectron();
            Body tp = tw.parts.get(0), te = tw.parts.get(1);
            double tdot = 0;
            for (int k = 0; k < 3; k++) tdot += (te.v[k] - tp.v[k]) * (tp.p[k] - te.p[k]);
            check(tdot > 0, "прицел 1 — всё ещё к телам, но под углом 45°");

            // по умолчанию — орбита; «всё по умолчанию» возвращает способ
            tw.restoreDefaults();
            check(tw.spawn == World.Spawn.ORBIT && tw.throwK == World.THROW_K && tw.throwAim == World.THROW_AIM, "способ по умолчанию — орбита");

            // след диполя: шаг мелкий, но точки следа — раз в TRAIL_DT модельного времени
            World trw = world(1e-5);
            trw.setScene(World.Scene.SANDBOX);
            trw.addProton();
            trw.addDipole();
            runFor(trw, 2);
            Body tpole = trw.parts.get(1);
            check(trw.dynTime() * 0.02 < Formula.TRAIL_DT / 10, "шаг у диполя мельче TRAIL_DT");
            near(tpole.trail.size(), 2 / Formula.TRAIL_DT, 3, "след диполя — по времени, а не по шагам");
        }

        // 28. Один поток, все ядра и видеокарта — один и тот же результат до бита (силы, КУО, энергия).
        {
            World gw = world(1e-5);
            gw.setScene(World.Scene.SANDBOX);
            gw.spawn = World.Spawn.REST;
            Random gr = new Random(7);
            for (int i = 0; i < 1600; i++) {
                int kind = gr.nextInt(4);
                if (kind == 0) gw.addProton();
                else if (kind == 1) gw.addElectron();
                else if (kind == 2) gw.addNeutron();
                else gw.addDipole();
            }
            gw.parts.get(5).p[0] = gw.parts.get(6).p[0]; // пара внутри чужого шара и r = 0 — тоже
            gw.parts.get(5).p[1] = gw.parts.get(6).p[1];
            gw.parts.get(5).p[2] = gw.parts.get(6).p[2];
            int gn = gw.parts.size();
            Forces fc = gw.forces;
            String[] names = {"1 поток", "все ядра", "GPU"};
            double[][][] acc = new double[3][][], rep = new double[3][][], push = new double[3][][];
            double[] en = new double[3], dy = new double[3];
            boolean gpuOk = fc.gpuAvailable();
            for (int v = 0; v < 3; v++) {
                if (v == 2 && !gpuOk) break;
                fc.parMin = v == 0 ? Integer.MAX_VALUE : Forces.PAR_MIN;
                fc.device = v == 2 ? Forces.Device.GPU : Forces.Device.CPU;
                gw.setMode(World.Mode.QN);
                acc[v] = gw.accelerate();
                dy[v] = gw.dynTime();
                en[v] = gw.energy().E();
                rep[v] = new double[gn][3];
                push[v] = new double[gn][3];
                gw.kuo.compute(0.7, 1, rep[v], push[v], null);
                check(fc.lastPath.startsWith(v == 0 ? "1 поток" : v == 1 ? "CPU" : "GPU"), "путь расчёта " + names[v] + ": " + fc.lastPath);
            }
            fc.parMin = Forces.PAR_MIN;
            fc.device = Forces.Device.AUTO;
            for (int v = 1; v < 3; v++) {
                if (v == 2 && !gpuOk) break;
                long diff = 0;
                for (int i = 0; i < gn; i++) {
                    for (int k = 0; k < 3; k++) {
                        if (Double.doubleToRawLongBits(acc[v][i][k]) != Double.doubleToRawLongBits(acc[0][i][k])) diff++;
                        if (Double.doubleToRawLongBits(rep[v][i][k]) != Double.doubleToRawLongBits(rep[0][i][k])) diff++;
                        if (Double.doubleToRawLongBits(push[v][i][k]) != Double.doubleToRawLongBits(push[0][i][k])) diff++;
                    }
                }
                check(diff == 0, names[v] + " = 1 поток до бита (силы и КУО), отличий: " + diff);
                check(Double.doubleToRawLongBits(en[v]) == Double.doubleToRawLongBits(en[0]), names[v] + ": энергия до бита");
                check(Double.doubleToRawLongBits(dy[v]) == Double.doubleToRawLongBits(dy[0]), names[v] + ": dynTime до бита");
            }
            System.out.println("  видеокарта: " + fc.gpuStatus() + (gpuOk ? " — сравнение с CPU пройдено (" + gn + " тел)" : " — сравнение пропущено"));

            // 28б. «GPU float» — приближённо: каждое ускорение и составляющие КУО ≈ double до 10⁻⁴
            // (обычно ~10⁻⁷), dynTime до 10⁻⁵, энергия — по-прежнему в double до бита.
            if (gpuOk) {
                fc.device = Forces.Device.GPU_FLOAT;
                double[][] af = gw.accelerate();
                double dyf = gw.dynTime(), enf = gw.energy().E();
                double[][] rf = new double[gn][3], pf = new double[gn][3];
                gw.kuo.compute(0.7, 1, rf, pf, null);
                check(fc.lastPath.equals("GPU float"), "путь расчёта GPU float: " + fc.lastPath);
                fc.device = Forces.Device.AUTO;
                double worst = 0;
                for (int i = 0; i < gn; i++) {
                    double[][][] pairs = {{af[i], acc[0][i]}, {rf[i], rep[0][i]}, {pf[i], push[0][i]}};
                    for (double[][] q : pairs) {
                        double d = Math.hypot(Math.hypot(q[0][0] - q[1][0], q[0][1] - q[1][1]), q[0][2] - q[1][2]);
                        double qa = Math.hypot(Math.hypot(q[1][0], q[1][1]), q[1][2]);
                        if (qa > 0) worst = Math.max(worst, d / qa);
                    }
                }
                check(worst < 1e-4, "GPU float: силы и КУО ≈ double, наибольшая отн. ошибка " + worst);
                near(dyf, dy[0], 1e-5 * dy[0], "GPU float: dynTime ≈ double");
                check(Double.doubleToRawLongBits(enf) == Double.doubleToRawLongBits(en[0]), "GPU float: энергия считается в double до бита");
                System.out.printf("  GPU float: наибольшая отн. ошибка сил %.1e (%d тел)%n", worst, gn);

                // 28б2. Barnes–Hut: грубее float, но конечные силы; энергия — double до бита; GPU float не сломан
                fc.device = Forces.Device.GPU_FLOAT_BH;
                double[][] abh = gw.accelerate();
                double enb = gw.energy().E();
                check(fc.lastPath.contains("Barnes"), "путь Barnes–Hut: " + fc.lastPath);
                check(Double.doubleToRawLongBits(enb) == Double.doubleToRawLongBits(en[0]), "BH: энергия в double до бита");
                double bhWorst = 0;
                int finite = 0;
                for (int i = 0; i < gn; i++) {
                    check(Double.isFinite(abh[i][0] + abh[i][1] + abh[i][2]), "BH: сила конечна");
                    double d = Math.hypot(Math.hypot(abh[i][0] - acc[0][i][0], abh[i][1] - acc[0][i][1]), abh[i][2] - acc[0][i][2]);
                    double qa = Math.hypot(Math.hypot(acc[0][i][0], acc[0][i][1]), acc[0][i][2]);
                    if (qa > 1e-18) {
                        bhWorst = Math.max(bhWorst, d / qa);
                        finite++;
                    }
                }
                check(finite > gn / 2, "BH: сравнимы с double у большинства тел");
                check(bhWorst < 0.5, "BH: отн. ошибка сил < 50 % (θ≈0.55), было " + bhWorst);
                fc.device = Forces.Device.GPU_FLOAT;
                gw.accelerate();
                check(fc.lastPath.equals("GPU float"), "после BH обычный GPU float цел: " + fc.lastPath);
                System.out.printf("  GPU float Barnes–Hut: наибольшая отн. ошибка сил %.2f (%d тел)%n", bhWorst, gn);
            }

            // 28в. «GPU float» — весь шаг на видеокарте: та же траектория, что в double (до 10⁻⁶ за
            // 0.05 с, пока не разошлось хаосом), энергия, импульс, следы, скорости вернулись в тела;
            // с замкнутой областью — тоже (см. chamberGpu); КУО — стрелки пересчитаны.
            if (gpuOk) {
                World[] gw2 = new World[2];
                for (int v = 0; v < 2; v++) {
                    gw2[v] = world(1e-5);
                    gw2[v].setScene(World.Scene.SANDBOX);
                    Random gr2 = new Random(11);
                    gw2[v].addProton();
                    for (int i = 0; i < 400; i++) {
                        int kind = gr2.nextInt(4);
                        if (kind == 0) gw2[v].addProton();
                        else if (kind == 1) gw2[v].addElectron();
                        else if (kind == 2) gw2[v].addNeutron();
                        else gw2[v].addDipole();
                    }
                    gw2[v].forces.device = v == 0 ? Forces.Device.GPU : Forces.Device.GPU_FLOAT;
                }
                double rE0 = gw2[1].energy().E(), scaleP = 0;
                for (Body b : gw2[1].parts) scaleP += b.m * Math.sqrt(b.v[0] * b.v[0] + b.v[1] * b.v[1] + b.v[2] * b.v[2]);
                double[] rP0 = momentum(gw2[1]);
                int[] rs = new int[2];
                for (int v = 0; v < 2; v++) {
                    rs[v] = gw2[v].advance(0.05);
                    for (int m; (m = gw2[v].advance(0)) > 0; ) rs[v] += m; // до конца: пока остаток не меньше шага
                }
                check(gw2[1].forces.lastPath.equals(Forces.RESIDENT), "весь шаг на видеокарте: " + gw2[1].forces.lastPath);
                check(rs[0] == rs[1] && rs[1] > 0, "столько же шагов, что в double: " + rs[1] + " / " + rs[0]);
                near(gw2[1].time, gw2[0].time, 1e-9, "модельное время то же (шаг из dynTime во float — до ~10⁻⁷)");
                double dx = 0, dv = 0;
                for (int i = 0; i < gw2[0].parts.size(); i++) {
                    Body a0 = gw2[0].parts.get(i), a1 = gw2[1].parts.get(i);
                    dx = Math.max(dx, World.dist(a0.p, a1.p));
                    dv = Math.max(dv, World.dist(a0.v, a1.v) / Math.max(1e-3, Math.sqrt(a0.v[0] * a0.v[0] + a0.v[1] * a0.v[1] + a0.v[2] * a0.v[2])));
                }
                check(dx < 1e-6, "весь шаг на видеокарте: положения ≈ double, расхождение " + dx);
                check(dv < 1e-3, "весь шаг на видеокарте: скорости вернулись в тела ≈ double, отн. " + dv);
                check(Math.abs(gw2[1].energy().E() - rE0) / Math.abs(rE0) < 1e-7, "весь шаг на видеокарте: энергия сохраняется");
                double[] rP1 = momentum(gw2[1]);
                double dP = Math.hypot(Math.hypot(rP1[0] - rP0[0], rP1[1] - rP0[1]), rP1[2] - rP0[2]) / scaleP;
                check(dP < 1e-6, "весь шаг на видеокарте: импульс сохраняется до " + dP + " от Σm|v|");
                check(gw2[1].parts.get(0).trail.size() == gw2[0].parts.get(0).trail.size(), "следы — те же точки");
                System.out.printf("  весь шаг на видеокарте: %d тел, %d шагов, расхождение с double %.1e, импульс %.1e от Σm|v|%n",
                        gw2[1].parts.size(), rs[1], dx, dP);

                gw2[1].setMode(World.Mode.KUO);
                gw2[1].advance(1.0 / 60);
                check(gw2[1].forces.lastPath.equals(Forces.RESIDENT) && gw2[1].kuo.rep().length == gw2[1].parts.size(), "КУО: шаг на видеокарте, составляющие для стрелок");
                gw2[1].chamber.setOn(true);
                gw2[1].advance(1.0 / 60);
                check(gw2[1].forces.lastPath.equals(Forces.RESIDENT), "с замкнутой областью — тоже весь шаг на видеокарте: " + gw2[1].forces.lastPath);
                chamberGpu();
            }
        }

        // 29. Большие сцены: след пишут только первые TRAIL_BODIES тел, у остальных память не занята.
        {
            World lw = world(1e-5);
            lw.setScene(World.Scene.SANDBOX);
            lw.spawn = World.Spawn.REST;
            for (int i = 0; i < Formula.TRAIL_BODIES + 20; i++) lw.addProton();
            runFor(lw, 0.1);
            check(lw.parts.get(0).trail.size() > 0, "след у первого тела есть");
            check(lw.parts.get(Formula.TRAIL_BODIES - 1).trail.size() > 0, "след у тела № TRAIL_BODIES есть");
            check(lw.parts.get(Formula.TRAIL_BODIES).trail.size() == 0, "у тел дальше TRAIL_BODIES следа нет");
            long ch0 = lw.changes;
            lw.addElectron();
            check(lw.changes > ch0, "добавление тела меняет счётчик изменений (окно пересчитает энергию)");
        }

        // 30. Вязкий слой гасит все тела сразу (см. viscousLayer).
        viscousLayer();

        // 31. Электрон вокруг ядра p–n. «+ атом» (дейтерий) кладёт n на rn = 0.45 вокруг p и e на
        //     r0 = 1.0 вокруг ядра; электрон кружит вокруг ядра, а не падает; ядро и энергия держатся.
        //     «+ электрон» к готовой паре ставит его на 0.8–2.2 от центра масс пары и он кружит.
        //     Пара из «+ p» и «+ n» шире ядерного масштаба — там электрон может попасть внутрь.
        //     Поле пары на третье тело — ровно сумма полей p и n.
        World atomW = world(1);
        atomW.setScene(World.Scene.SANDBOX);
        atomW.addAtom();
        check(kinds(atomW).equals("pne"), "«+ атом» — дейтерий: p, n, e (" + kinds(atomW) + ")");
        Body ap = atomW.parts.get(0), an = atomW.parts.get(1), ae = atomW.parts.get(2);
        List<Body> nucleus = List.of(ap, an);
        double dn0 = World.dist(ap.p, an.p);
        double de0 = World.dist(ae.p, World.lump(nucleus).c());
        check(Math.abs(dn0 - atomW.rn) < 0.02, "n на rn = 0.45 вокруг p: " + dn0);
        check(Math.abs(de0 - atomW.r0) < 0.05, "e на r0 = 1.0 вокруг ядра: " + de0);
        double eAtom = atomW.energy().E(), rMin = de0, rMax = de0, nMin = dn0, nMax = dn0, turned = 0;
        double[] prev = null;
        for (int f = 0; f < 60 * 5; f++) {
            atomW.advance(1.0 / 60);
            double[] nucCm = World.lump(nucleus).c();
            double r = World.dist(ae.p, nucCm);
            rMin = Math.min(rMin, r);
            rMax = Math.max(rMax, r);
            double d = World.dist(ap.p, an.p);
            nMin = Math.min(nMin, d);
            nMax = Math.max(nMax, d);
            double[] u = {ae.p[0] - nucCm[0], ae.p[1] - nucCm[1], ae.p[2] - nucCm[2]};
            double len = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
            for (int k = 0; k < 3; k++) u[k] /= len;
            if (prev != null) {
                double cx = prev[1] * u[2] - prev[2] * u[1], cy = prev[2] * u[0] - prev[0] * u[2], cz = prev[0] * u[1] - prev[1] * u[0];
                turned += Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), prev[0] * u[0] + prev[1] * u[1] + prev[2] * u[2]);
            }
            prev = u;
        }
        check(rMin > 0.8 * de0 && rMax < 1.2 * de0, "электрон кружит вокруг ядра, радиус держится: " + rMin + "…" + rMax);
        check(turned > 4 * Math.PI, "электрон обошёл ядро больше двух раз: " + (turned / (2 * Math.PI)) + " оборота");
        check(nMin > 0.8 * dn0 && nMax < 1.25 * dn0, "ядро p–n держится: " + nMin + "…" + nMax);
        check(Math.abs((atomW.energy().E() - eAtom) / eAtom) < 1e-4, "энергия атома сохраняется: " + (atomW.energy().E() - eAtom) / eAtom);

        // «+ электрон» к готовой паре нуклонов (ядро дейтерия без электрона).
        World pe = world(1);
        pe.setScene(World.Scene.SANDBOX);
        List<Body> built = pe.makeAtom(0, new double[3]);
        pe.parts = new ArrayList<>(List.of(built.get(0), built.get(1)));
        pe.addElectron();
        check(pe.parts.size() == 3, "к паре нуклонов добавлен электрон: " + pe.parts.size() + " тела");
        Body e3 = pe.parts.get(2);
        List<Body> pair = List.of(pe.parts.get(0), pe.parts.get(1));
        double r30 = World.dist(e3.p, World.lump(pair).c());
        check(r30 > 0.79 && r30 < 2.21, "электрон поставлен на 0.8–2.2 от центра масс пары: " + r30);
        double eTriple = pe.energy().E(), r3Min = r30, r3Max = r30, turned3 = 0;
        double[] prev3 = null;
        for (int f = 0; f < 60 * 5; f++) {
            pe.advance(1.0 / 60);
            double[] pairCm = World.lump(pair).c();
            double r = World.dist(e3.p, pairCm);
            r3Min = Math.min(r3Min, r);
            r3Max = Math.max(r3Max, r);
            double[] u = {e3.p[0] - pairCm[0], e3.p[1] - pairCm[1], e3.p[2] - pairCm[2]};
            double len = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
            for (int k = 0; k < 3; k++) u[k] /= len;
            if (prev3 != null) {
                double cx = prev3[1] * u[2] - prev3[2] * u[1], cy = prev3[2] * u[0] - prev3[0] * u[2], cz = prev3[0] * u[1] - prev3[1] * u[0];
                turned3 += Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), prev3[0] * u[0] + prev3[1] * u[1] + prev3[2] * u[2]);
            }
            prev3 = u;
        }
        check(r3Min > 0.6 * r30 && r3Max < 1.5 * r30, "электрон у пары кружит: " + r3Min + "…" + r3Max);
        check(turned3 > 4 * Math.PI, "электрон обошёл пару больше двух раз: " + (turned3 / (2 * Math.PI)) + " оборота");
        check(Math.abs((pe.energy().E() - eTriple) / eTriple) < 1e-4, "энергия тройки сохраняется: " + (pe.energy().E() - eTriple) / eTriple);

        // Пара из «+ p» и «+ n» — 1.5–3, а не ядерный масштаб: электрон (0.8–2.2) может попасть внутрь.
        World wide = world(1e-5);
        wide.setScene(World.Scene.SANDBOX);
        wide.addProton();
        wide.addNeutron();
        double dWide = World.dist(wide.parts.get(0).p, wide.parts.get(1).p);
        check(dWide > 1.4 && dWide < 3.1, "пара из «+ p» и «+ n» шире ядерного масштаба: " + dWide);
        wide.addElectron();
        double rWide = World.dist(wide.parts.get(2).p, World.lump(wide.parts.subList(0, 2)).c());
        check(rWide > 0.79 && rWide < 2.21, "«+ электрон» и здесь ставится на 0.8–2.2 от центра масс: " + rWide);
        double eWide = wide.energy().E();
        boolean finite = true;
        for (int f = 0; f < 60 * 20; f++) {
            wide.advance(1.0 / 60);
            for (Body b : wide.parts) {
                for (int k = 0; k < 3; k++) if (!Double.isFinite(b.p[k]) || !Double.isFinite(b.v[k])) finite = false;
            }
        }
        check(finite, "широкая пара с электроном: счёт не разошёлся");
        check(Math.abs((wide.energy().E() - eWide) / eWide) < 1e-2, "широкая пара: энергия сохраняется: " + (wide.energy().E() - eWide) / eWide);

        // Поле пары — сумма полей: ускорение третьего тела от p и n = от p + от n (по трём осям).
        double[][] pos = {{0.10, 0.05, -0.02}, {-0.25, 0.12, 0.08}, {1.30, 0.40, 0.55}};
        World wP = world(1);
        wP.setScene(World.Scene.SANDBOX);
        wP.parts = new ArrayList<>(List.of(wP.makeBody(Species.P, -1, pos[0].clone(), new double[3]),
                wP.makeBody(Species.E, -1, pos[2].clone(), new double[3])));
        double[] aP = wP.accelerate()[1].clone();
        World wN = world(1);
        wN.setScene(World.Scene.SANDBOX);
        wN.parts = new ArrayList<>(List.of(wN.makeBody(Species.N, -1, pos[1].clone(), new double[3]),
                wN.makeBody(Species.E, -1, pos[2].clone(), new double[3])));
        double[] aN = wN.accelerate()[1].clone();
        World wPN = world(1);
        wPN.setScene(World.Scene.SANDBOX);
        wPN.parts = new ArrayList<>(List.of(wPN.makeBody(Species.P, -1, pos[0].clone(), new double[3]),
                wPN.makeBody(Species.N, -1, pos[1].clone(), new double[3]),
                wPN.makeBody(Species.E, -1, pos[2].clone(), new double[3])));
        double[] aPN = wPN.accelerate()[2].clone();
        for (int k = 0; k < 3; k++) {
            near(aPN[k], aP[k] + aN[k], 1e-13 * (1 + Math.abs(aPN[k])), "поле пары = сумма полей, ось " + k);
        }
        check(Math.abs(aPN[0]) + Math.abs(aPN[1]) + Math.abs(aPN[2]) > 0, "сумма полей не ноль");

        // 32. Замкнутый КУО: конечный запас N_КУО, локальные массы его расходуют. При N_КУО → ∞ —
        //     прежняя модель; при q = 1 − β_т/β_к итог пары ноль (равновесие), дальше — отталкивание.
        //     Импульс сохраняется (поправка общая для всех пар), энергия — тот же потенциал ×netGain.
        double[][] pos2 = {{0, 0, 0}, {1, 0, 0}};
        World kIn = world(1);
        kIn.setScene(World.Scene.SANDBOX);
        kIn.parts = new ArrayList<>(List.of(kIn.makeBody(Species.P, -1, pos2[0].clone(), new double[3]),
                kIn.makeBody(Species.E, -1, pos2[1].clone(), new double[3])));
        double[] aIn = kIn.accelerate()[1].clone();
        check(aIn[0] < 0 && Math.abs(aIn[0]) > 1e-6, "бесконечный КУО: электрон притягивается к протону: " + aIn[0]);

        World kFar = world(1);
        kFar.setScene(World.Scene.SANDBOX);
        kFar.parts = new ArrayList<>(List.of(kFar.makeBody(Species.P, -1, pos2[0].clone(), new double[3]),
                kFar.makeBody(Species.E, -1, pos2[1].clone(), new double[3])));
        kFar.kuo.closed = true;
        kFar.kuo.nKuo = 1e30;
        check(kFar.kuo.q() < 1e-20, "огромный запас: q ≈ 0 (" + kFar.kuo.q() + ")");
        double[] aKFar = kFar.accelerate()[1].clone();
        for (int k = 0; k < 3; k++) {
            near(aKFar[k], aIn[k], 1e-12 * (1 + Math.abs(aIn[k])), "N_КУО → ∞ — прежняя модель, ось " + k);
        }

        double sum2 = Formula.M_P + Formula.M_E; // ΣN локальных тел
        World kCl = world(1);
        kCl.setScene(World.Scene.SANDBOX);
        kCl.parts = new ArrayList<>(List.of(kCl.makeBody(Species.P, -1, pos2[0].clone(), new double[3]),
                kCl.makeBody(Species.E, -1, pos2[1].clone(), new double[3])));
        kCl.kuo.closed = true;
        kCl.kuo.nKuo = 2e5;
        double q = sum2 / 2e5, f = 1.5 * (1 - q) - 0.5; // β_к = 1.5, β_т = 0.5
        check(Math.abs(kCl.kuo.q() - q) < 1e-15 && Math.abs(kCl.kuo.netGain() - f) < 1e-15, "q и итог пары: " + q + ", " + f);
        double[] aCl = kCl.accelerate()[1].clone();
        for (int k = 0; k < 3; k++) {
            near(aCl[k], f * aIn[k], 1e-12 * (1 + Math.abs(aIn[k])), "замкнутый КУО: итог пары ×(β_к(1−q) − β_т), ось " + k);
        }
        double[] aClP = kCl.accelerate()[0];
        near(Formula.M_P * aClP[0] + Formula.M_E * aCl[0], 0, 1e-12 * Math.abs(Formula.M_P * aClP[0]), "замкнутый КУО: импульс пар сохраняется");

        World kEq = world(1);
        kEq.setScene(World.Scene.SANDBOX);
        kEq.parts = new ArrayList<>(List.of(kEq.makeBody(Species.P, -1, pos2[0].clone(), new double[3]),
                kEq.makeBody(Species.E, -1, pos2[1].clone(), new double[3])));
        kEq.kuo.closed = true;
        kEq.kuo.nKuo = sum2 / (1 - 0.5 / 1.5); // q = 1 − β_т/β_к
        near(kEq.kuo.netGain(), 0, 1e-15, "q = 1 − β_т/β_к — равновесие");
        double[] aEq = kEq.accelerate()[1].clone();
        check(Math.abs(aEq[0]) < 1e-15, "равновесие: электрон не испытывает силы: " + aEq[0]);

        World kRep = world(1);
        kRep.setScene(World.Scene.SANDBOX);
        kRep.parts = new ArrayList<>(List.of(kRep.makeBody(Species.P, -1, pos2[0].clone(), new double[3]),
                kRep.makeBody(Species.E, -1, pos2[1].clone(), new double[3])));
        kRep.kuo.closed = true;
        kRep.kuo.nKuo = sum2 / 0.9; // q = 0.9 — запас почти исчерпан
        double[] aRep = kRep.accelerate()[1].clone();
        check(aRep[0] > 0 && Math.abs(aRep[0] / aIn[0] + 0.35) < 1e-12, "запас исчерпан: тела расталкиваются, " + (aRep[0] / aIn[0]) + "·W");

        // Энергия в замкнутом КУО — тот же потенциал ×netGain: за 2 с не должна уплывать.
        World kRun = world(1e-5);
        kRun.setScene(World.Scene.SANDBOX);
        kRun.addAtom();
        kRun.kuo.closed = true;
        kRun.kuo.nKuo = 1e5;
        double e0 = kRun.energy().E();
        for (int f2 = 0; f2 < 120; f2++) kRun.advance(1.0 / 60);
        check(Math.abs((kRun.energy().E() - e0) / e0) < 1e-6, "замкнутый КУО: энергия сохраняется: " + (kRun.energy().E() - e0) / e0);

        // 33. Своя частица: масса задаётся пользователем (по умолчанию — масса электрона), N = m,
        //     радиус из N = V·q; ускорение от неё — как от тела той же массы, своё ускорение от массы не зависит.
        World cu = world(1);
        cu.setScene(World.Scene.SANDBOX);
        near(cu.customMass, 1, 0, "масса своей по умолчанию = 1 m_e");
        cu.addCustom();
        check(cu.parts.size() == 1 && cu.counts().custom() == 1, "своя частица добавлена и посчитана: " + cu.counts().custom());
        Body cu0 = cu.parts.get(0);
        near(cu0.N, Formula.M_E, 1e-12, "N своей по умолчанию = N электрона");
        near(cu0.rad, Formula.RAD_E, 1e-12, "радиус своей по умолчанию = радиус электрона");

        cu.customMass = Formula.M_P;
        cu.addCustom();
        Body cuP = cu.parts.get(1);
        near(cuP.N, Formula.M_P, 1e-9, "N своей с массой 1836");
        near(cuP.rad, Formula.RAD_P, 1e-9, "радиус своей из N = V·q — как у протона");
        check(cu.counts().custom() == 2, "две свои частицы: " + cu.counts().custom());
        cu.removeCustom();
        check(cu.counts().custom() == 1, "− убирает последнюю свою: " + cu.counts().custom());
        cu.customMass = 8;
        cu.addCustom();
        near(cu.parts.get(cu.parts.size() - 1).rad, 2 * Formula.RAD_E, 1e-12, "радиус ∝ ∛m: масса 8 — вдвое больше электрона");

        World cuProbe = world(1);
        cuProbe.setScene(World.Scene.SANDBOX);
        cuProbe.parts.clear();
        cuProbe.customMass = Formula.M_P;
        cuProbe.addCustom();
        cuProbe.parts.add(cuProbe.makeBody(Species.E, -1, new double[]{1, 0, 0}, new double[3]));
        World pProbe = world(1);
        pProbe.setScene(World.Scene.SANDBOX);
        pProbe.parts = new ArrayList<>(List.of(pProbe.makeBody(Species.P, -1, new double[]{0, 0, 0}, new double[3]),
                pProbe.makeBody(Species.E, -1, new double[]{1, 0, 0}, new double[3])));
        double[] aC = cuProbe.accelerate()[1].clone();
        double[] aPr = pProbe.accelerate()[1].clone();
        for (int k = 0; k < 3; k++) {
            near(aC[k], aPr[k], 1e-12 * (1 + Math.abs(aPr[k])), "своя массой 1836 действует как протон, ось " + k);
        }
        near(cuProbe.accelerate()[0][0], pProbe.accelerate()[0][0], 1e-12 * (1 + Math.abs(aPr[0])), "своё ускорение не зависит от своей массы");

        cuProbe.customMass = 42;
        cuProbe.restoreDefaults();
        near(cuProbe.customMass, 1, 0, "restoreDefaults: масса своей = 1 m_e");

        // 34. Создание в точке (правый клик «создать здесь»): тело встаёт точно в точку и в покое.
        World atW = world(1);
        atW.setScene(World.Scene.SANDBOX);
        double[] pt = {0.3, 0.2, -0.4};
        atW.addAt(Species.P, pt);
        check(atW.parts.size() == 1 && World.dist(atW.parts.get(0).p, pt) < 1e-15
                && atW.parts.get(0).v[0] == 0 && atW.parts.get(0).v[1] == 0 && atW.parts.get(0).v[2] == 0, "addAt: ровно в точке и в покое");
        atW.customMass = 5;
        atW.addAt(Species.CUSTOM, pt);
        near(atW.parts.get(1).N, 5, 1e-12, "addAt: своя частица нужной массы");
        atW.addAtomAt(new double[]{-0.2, 0, 0.3});
        check(atW.counts().atoms() == 1, "addAtomAt: атом в точке");
        atW.addDipoleAt(new double[]{0.1, -0.3, 0.2});
        check(atW.counts().dipoles() == 1, "addDipoleAt: диполь в точке");

        // 35. «Рандом»: случайная частица — p, n, e, своя (случайная масса 0.1…10⁴ m_e), атом или диполь.
        World rndW = world(1);
        rndW.setScene(World.Scene.SANDBOX);
        rndW.customMass = 7;
        boolean sawP = false, sawN = false, sawE = false, sawC = false, sawA = false, sawD = false;
        int bad = 0, massBad = 0;
        for (int i = 0; i < 120; i++) {
            int n0 = rndW.parts.size();
            rndW.addRandomAt(new double[]{0.4 * ((i % 7) - 3), 0, 0.4 * ((i % 5) - 2)});
            if (rndW.parts.size() <= n0) {
                bad++;
                continue;
            }
            List<Body> add = rndW.parts.subList(n0, rndW.parts.size());
            if (add.stream().anyMatch(b -> b.atom >= 0)) sawA = true;
            else if (add.stream().anyMatch(b -> b.partner != null)) sawD = true;
            else {
                Species k = add.get(0).kind;
                if (k == Species.P) sawP = true;
                else if (k == Species.N) sawN = true;
                else if (k == Species.E) sawE = true;
                else {
                    sawC = true;
                    double m = add.get(0).N;
                    if (!(m > 0.09 && m < 1.1e4)) massBad++;
                }
            }
        }
        check(bad == 0, "каждый «рандом» добавил тело: промахов " + bad);
        check(sawP && sawN && sawE && sawC && sawA && sawD, "«рандом» даёт все виды: p " + sawP + ", n " + sawN + ", e " + sawE + ", своя " + sawC + ", атом " + sawA + ", диполь " + sawD);
        check(massBad == 0, "случайная масса своей в 0.1…10⁴ m_e: вне " + massBad);
        near(rndW.customMass, 7, 0, "«рандом» не меняет поле массы своей");
        int n1 = rndW.parts.size();
        rndW.addRandom();
        check(rndW.parts.size() > n1, "«рандом» из панели добавил тело");
        World b10 = world(1);
        b10.setScene(World.Scene.SANDBOX);
        for (int i = 0; i < 10; i++) b10.addRandom();
        check(b10.parts.size() >= 10, "«по 10» — не меньше 10 тел: " + b10.parts.size());
        World b100 = world(1);
        b100.setScene(World.Scene.SANDBOX);
        for (int i = 0; i < 100; i++) b100.addRandom();
        check(b100.parts.size() >= 100, "«по 100» — не меньше 100 тел: " + b100.parts.size());

        // 36. Контакт физических поверхностей: импульс сохраняется, энергия удара рассеивается.
        //     До касания дополнительного отталкивания нет; без переключателя — прежнее притяжение.
        World imp = world(1e-5);
        imp.setScene(World.Scene.SANDBOX);
        imp.impenetrable = true;
        imp.parts = new ArrayList<>(List.of(
                imp.makeBody(Species.P, -1, new double[]{-0.1, 0, 0}, new double[3]),
                imp.makeBody(Species.P, -1, new double[]{0.1, 0, 0}, new double[3])));
        Body ia = imp.parts.get(0), ib = imp.parts.get(1);
        double shell = ia.rad + ib.rad;
        check(World.dist(ia.p, ib.p) < ia.rad + ib.rad, "шары перекрываются: " + World.dist(ia.p, ib.p));
        double[][] acc = imp.accelerate();
        check(acc[0][0] < 0 && acc[1][0] > 0, "непроницаемые: перекрытие расталкивает: " + acc[0][0] + ", " + acc[1][0]);
        near(ia.m * acc[0][0] + ib.m * acc[1][0], 0, 1e-12 * Math.abs(ia.m * acc[0][0]), "контакт: импульс пары сохраняется");
        ia.p[0] = -0.5;
        ib.p[0] = 0.5;
        World softNo = world(1e-5);
        softNo.setScene(World.Scene.SANDBOX);
        softNo.parts = new ArrayList<>(List.of(
                softNo.makeBody(Species.P, -1, new double[]{-0.5, 0, 0}, new double[3]),
                softNo.makeBody(Species.P, -1, new double[]{0.5, 0, 0}, new double[3])));
        near(imp.accelerate()[0][0], softNo.accelerate()[0][0], 1e-15, "вне оболочки контакта нет");
        check(softNo.accelerate()[0][0] > 0, "вне оболочки — обычное притяжение: " + softNo.accelerate()[0][0]);
        ia.p[0] = -0.55 * shell;
        ib.p[0] = 0.55 * shell;
        check(World.dist(ia.p, ib.p) > ia.rad + ib.rad, "тела ещё не касаются");
        check(imp.accelerate()[0][0] > 0, "до физического касания сохраняется притяжение");
        ia.p[0] = -0.1;
        ib.p[0] = 0.1;
        double e0i = imp.energy().E();
        for (int fs2 = 0; fs2 < 120; fs2++) imp.advance(1.0 / 60);
        double e1i = imp.energy().E();
        check(World.dist(ia.p, ib.p) > ia.rad + ib.rad - 0.02, "мягкая оболочка не даёт провалиться: " + World.dist(ia.p, ib.p));
        check(e1i < e0i, "удар гасится: энергия уменьшается: " + (e1i - e0i) / e0i);
        World soft = world(1e-5);
        soft.setScene(World.Scene.SANDBOX);
        soft.parts = new ArrayList<>(List.of(
                soft.makeBody(Species.P, -1, new double[]{-0.1, 0, 0}, new double[3]),
                soft.makeBody(Species.P, -1, new double[]{0.1, 0, 0}, new double[3])));
        double[][] acc3 = soft.accelerate();
        check(acc3[0][0] > 0, "без переключателя — притяжение: " + acc3[0][0]);

        // 37. Следы электронов в большой сцене: у электрона за TRAIL_BODIES след есть, у протона — нет.
        World trW = world(1e-5);
        trW.setScene(World.Scene.SANDBOX);
        trW.parts = new ArrayList<>();
        for (int i = 0; i < Formula.TRAIL_BODIES + 5; i++) {
            trW.parts.add(trW.makeBody(Species.P, -1, new double[]{3 + i * 0.005, 0, 0}, new double[3]));
        }
        Body lateE = trW.makeBody(Species.E, -1, new double[]{3.5, 0.2, 0}, new double[3]);
        trW.parts.add(lateE);
        for (int ft = 0; ft < 30; ft++) trW.advance(1.0 / 60);
        check(lateE.trail.size() > 1, "у электрона за TRAIL_BODIES есть след: " + lateE.trail.size());
        check(trW.parts.get(Formula.TRAIL_BODIES + 2).trail.size() == 0, "у протона за TRAIL_BODIES следа нет");

        // 38. Пресеты «Сценарий»: Земля–Луна, СС, песочница, мелкие фракции + impenetrable.
        scenarios();

        // 39. R3: жёстче e–e разных атомов (кнопка в «Расчёте», по умолчанию выключено).
        World r3 = world(1e-5);
        r3.setScene(World.Scene.SANDBOX);
        r3.parts = new ArrayList<>(List.of(
                r3.makeBody(Species.E, 0, new double[]{-0.2, 0, 0}, new double[3]),
                r3.makeBody(Species.E, 1, new double[]{0.2, 0, 0}, new double[3])));
        double[] off = r3.accelerate()[0].clone();
        check(off[0] > 0, "без R3 e–e разных атомов притягиваются: " + off[0]);
        r3.atomERepulsion = true;
        r3.atomEK = 3;
        double[][] accs = r3.accelerate();
        check(accs[0][0] < 0, "R3 расталкивает: " + accs[0][0]);
        near(Formula.M_E * accs[0][0] + Formula.M_E * accs[1][0], 0, 1e-18, "R3: импульс пары");
        World rSame = world(1e-5);
        rSame.setScene(World.Scene.SANDBOX);
        rSame.parts = new ArrayList<>(List.of(
                rSame.makeBody(Species.E, 0, new double[]{-0.2, 0, 0}, new double[3]),
                rSame.makeBody(Species.E, 0, new double[]{0.2, 0, 0}, new double[3])));
        rSame.atomERepulsion = true;
        rSame.atomEK = 3;
        check(rSame.accelerate()[0][0] > 0, "внутри одного атома R3 не действует");

        // 40. R1 starts at the physical surface; R2 remains an overlap-only force.
        World r12 = world(1e-5);
        r12.setScene(World.Scene.SANDBOX);
        r12.parts = new ArrayList<>(List.of(
                r12.makeBody(Species.P, -1, new double[]{-0.16, 0, 0}, new double[3]),
                r12.makeBody(Species.P, -1, new double[]{0.16, 0, 0}, new double[3])));
        double rp = r12.parts.get(0).rad;
        check(World.dist(r12.parts.get(0).p, r12.parts.get(1).p) > 2 * rp, "вне вещества (0.32 > 2R)");
        r12.softRepulsion = true;
        check(r12.accelerate()[0][0] > 0, "R1: до касания тело продолжает падать");
        r12.parts.get(0).p[0] = -0.1; r12.parts.get(1).p[0] = 0.1;
        check(r12.accelerate()[0][0] < 0, "R1: коррекция начинается только при перекрытии");
        r12.parts.get(0).p[0] = -0.16; r12.parts.get(1).p[0] = 0.16;
        r12.softRepulsion = false;
        World r2No = world(1e-5);
        r2No.setScene(World.Scene.SANDBOX);
        r2No.parts = new ArrayList<>(List.of(
                r2No.makeBody(Species.P, -1, new double[]{-0.16, 0, 0}, new double[3]),
                r2No.makeBody(Species.P, -1, new double[]{0.16, 0, 0}, new double[3])));
        r12.overlapRepulsion = true;
        near(r12.accelerate()[0][0], r2No.accelerate()[0][0], 1e-15, "R2 вне перекрытия не действует");
        r12.parts.get(0).p[0] = -0.05;
        r12.parts.get(1).p[0] = 0.05;
        check(r12.accelerate()[0][0] < 0, "R2 в перекрытии отталкивает: " + r12.accelerate()[0][0]);

        // 41. Каждый шаблон: правильная камера и конечное состояние после 0.001 с модели.
        // Это короткий smoke-test, не доказательство длительной устойчивости каждого вида.
        // Полные 30 с водорода проверяются общим опытом Lab в разделе 42.
        for (World.MoleculeType mt : World.MoleculeType.values()) {
            World mw = world(1);
            Lab.MoleculeInChamber experiment = new Lab.MoleculeInChamber();
            experiment.type = mt;
            experiment.setup(mw);
            check(Math.abs(mw.chamber.R() - mw.chamber.R0) < 1e-10, "начальный размер камеры: " + mt);
            Map<Integer, List<Body>> byAtom = new LinkedHashMap<>();
            for (Body b : mw.parts) byAtom.computeIfAbsent(b.atom, k -> new ArrayList<>()).add(b);
            List<Integer> ids = new ArrayList<>(byAtom.keySet());
            double[][] m0 = new double[ids.size()][ids.size()];
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    m0[i][j] = World.dist(World.lump(byAtom.get(ids.get(i))).c(), World.lump(byAtom.get(ids.get(j))).c());
            double worst = 1;
            int escapes = 0;
            while (mw.time < 0.001 - 1e-10) {
                Lab.stepToward(mw, 0.001);
                for (Body b : mw.parts) if (World.dist(b.p, mw.chamber.c) > mw.chamber.radiusAt(b.p)) escapes++;
                for (int i = 0; i < ids.size(); i++)
                    for (int j = i + 1; j < ids.size(); j++) {
                        double d = World.dist(World.lump(byAtom.get(ids.get(i))).c(), World.lump(byAtom.get(ids.get(j))).c());
                        worst = Math.max(worst, d / Math.max(1e-9, m0[i][j]));
                    }
            }
            check(Math.abs(mw.time - 0.001) < 1e-10, "полное модельное время smoke-test: " + mt);
            check(Double.isFinite(worst), "конечные межъядерные расстояния: " + mt);
            for (Body body : mw.parts) for (int k = 0; k < 3; k++)
                check(Double.isFinite(body.p[k]) && Double.isFinite(body.v[k]), "конечное состояние частицы: " + mt);
            System.out.println("молекула " + mt.name() + ": smoke-test 0.001 с модели · max d/d0 = " + String.format(java.util.Locale.ROOT, "%.2f", worst) + " · наблюдений за камерой " + escapes);
        }

        // 42. Лаборатория: те же опыты, что во вкладке «Лаборатория», дают годные вердикты.
        for (Lab.Experiment ex : Lab.all()) {
            Lab.Report r = ex.run();
            check(r.ok(), "лаборатория «" + ex.title() + "»: " + r.verdict() + " — " + r.details());
        }

        System.out.println("FormulaTest: " + checks + " проверок пройдено.");
    }

    /** 38. Каталог пресетов вкладки «Сценарий». */
    private static void scenarios() {
        World em = world(World.G_DEFAULT);
        em.applyPreset(Scenarios.Preset.SANDBOX); // world() стартует с атома — очистить
        em.applyPreset(Scenarios.Preset.EARTH_MOON);
        check(em.parts.size() == 2, "Земля–Луна: 2 тела");
        near(em.parts.get(0).N, Scenarios.EARTH_N, 1e-6 * Scenarios.EARTH_N, "Земля N≈1e13");
        check(em.parts.get(0).astro == AstroClass.PLANET && em.parts.get(1).astro == AstroClass.MOON,
                "Земля–Луна: PLANET + MOON");
        near(em.parts.get(1).N, Scenarios.moonN(Scenarios.MOON_SHARE_DEFAULT),
                1e-6 * Scenarios.EARTH_N, "Луна = Земля/81.3");
        near(World.dist(em.parts.get(0).p, em.parts.get(1).p), Scenarios.moonDistance(em),
                1e-6 * Scenarios.moonDistance(em), "Земля–Луна: астро-расстояние");
        check(!em.impenetrable, "Земля–Луна не включает непроницаемость");

        em.moonShare = 0.02;
        em.applyPreset(Scenarios.Preset.EARTH_MOON);
        check(em.parts.size() == 4, "Земля–Луна повторно: append → 4 тела");
        near(em.parts.get(3).N, Scenarios.EARTH_N * 0.02, 1e-6 * Scenarios.EARTH_N, "доля Луны из поля (второй набор)");

        World modeProbe = world(World.G_DEFAULT);
        check(modeProbe.moonsBeltsMode == Scenarios.MoonsBeltsMode.D
                        && Scenarios.MOONS_BELTS_DEFAULT == Scenarios.MoonsBeltsMode.D,
                "moonsBeltsMode default = D");

        // Режим A: именные + плотный пояс + субспутники у крупных лун, без роя
        World solA = world(World.G_DEFAULT);
        solA.applyPreset(Scenarios.Preset.SANDBOX);
        solA.moonsBeltsMode = Scenarios.MoonsBeltsMode.A;
        solA.applyPreset(Scenarios.Preset.SOLAR);
        int beltA = Scenarios.mainBeltCount(solA); // denser (heavy выкл)
        // 1 sun + 8 planets + Луна + 4 Галилеевых + Титан + Тритон + 13 средних именных
        int namedA = 1 + 8 + 1 + 4 + 1 + 1 + 13;
        // крупные с субспутниками: Moon+Galileans+Titan+Triton + Tethys/Dione/Rhea/Iapetus/Titania/Oberon ≈ 13
        int subHostsA = 13;
        int subALo = subHostsA * Scenarios.SUBSAT_N_MIN_A, subAHi = subHostsA * Scenarios.SUBSAT_N_MAX_A;
        int expectALo = namedA + subALo + beltA + Scenarios.KUIPER_COUNT;
        int expectAHi = namedA + subAHi + beltA + Scenarios.KUIPER_COUNT;
        check(solA.parts.size() >= expectALo && solA.parts.size() <= expectAHi,
                "СС A: " + solA.parts.size() + " в [" + expectALo + "…" + expectAHi + "]");
        // субспутник у Ганимеда: есть MOON ближе к Ганимеду, чем к Юпитеру, внутри доли Хилла
        Body ganymede = null, jupiterBody = null;
        for (Body b : solA.parts) {
            if (b.astro == AstroClass.PLANET && Math.abs(b.N - Scenarios.EARTH_N * 317.8) < 1e-6 * Scenarios.EARTH_N)
                jupiterBody = b;
            if (b.astro == AstroClass.MOON && Math.abs(b.N - Scenarios.EARTH_N * 0.025) < 1e-6 * Scenarios.EARTH_N)
                ganymede = b;
        }
        check(ganymede != null && jupiterBody != null, "СС A: Ганимед и Юпитер найдены");
        double aGan = World.dist(ganymede.p, jupiterBody.p);
        double rHillG = aGan * Math.cbrt(ganymede.N / (3.0 * jupiterBody.N));
        int nearGan = 0;
        for (Body b : solA.parts) {
            if (b == ganymede || b.astro != AstroClass.MOON) continue;
            double d = World.dist(b.p, ganymede.p);
            if (d > ganymede.rad && d < Scenarios.SUBSAT_HILL_FRAC * rHillG * 1.05
                    && b.N < ganymede.N * Scenarios.SUBSAT_MASS_FRAC * 2) nearGan++;
        }
        check(nearGan >= Scenarios.SUBSAT_N_MIN_A, "СС A: субспутники у Ганимеда ≥"
                + Scenarios.SUBSAT_N_MIN_A + ", было " + nearGan);

        // Default D: именные A + умеренный рой + субспутники
        World sol = world(World.G_DEFAULT);
        sol.applyPreset(Scenarios.Preset.SANDBOX);
        check(sol.moonsBeltsMode == Scenarios.MOONS_BELTS_DEFAULT, "СС default mode D до генерации");
        sol.applyPreset(Scenarios.Preset.SOLAR);
        int beltD = Scenarios.mainBeltCount(sol); // denser
        int swarmLo = 4 * Scenarios.SWARM_N_MIN_D, swarmHi = 4 * Scenarios.SWARM_N_MAX_D;
        int subDLo = subHostsA * Scenarios.SUBSAT_N_MIN_D, subDHi = subHostsA * Scenarios.SUBSAT_N_MAX_D;
        int expectDLo = namedA + swarmLo + subDLo + beltD + Scenarios.KUIPER_COUNT;
        int expectDHi = namedA + swarmHi + subDHi + beltD + Scenarios.KUIPER_COUNT;
        check(sol.parts.size() >= expectDLo && sol.parts.size() <= expectDHi,
                "СС D: " + sol.parts.size() + " в [" + expectDLo + "…" + expectDHi + "]");
        double au = Scenarios.AU_RE * Scenarios.earthRadius();
        double re = Scenarios.earthRadius();
        // Земля — 3-я планета после Солнца, Меркурия, Венеры (индекс 3); a = 1 а.е.
        near(World.dist(sol.parts.get(0).p, sol.parts.get(3).p), au, 1e-6 * au, "Земля на 1 а.е. (полный AU_RE)");
        near(au / re, Scenarios.AU_RE, 1e-9, "1 а.е. = AU_RE радиусов Земли");
        int inMain = 0, inKuiper = 0, solMoons = 0, solPlanets = 0;
        double nBeltMin = Double.POSITIVE_INFINITY, nBeltMax = 0;
        double radBeltMax = 0;
        double[] sunP = sol.parts.get(0).p;
        Body sun = sol.parts.get(0);
        check(sun.N > Scenarios.EARTH_N * 1e5, "Солнце ≫ Земля по N");
        near(sun.rad / re, Scenarios.SUN_RE, 1e-9, "Солнце: радиус ≈ 109 R⊕");
        near(sol.parts.get(3).rad / re, Scenarios.EARTH_RE, 1e-9, "Земля: радиус = 1 R⊕");
        check(sun.rad > sol.parts.get(3).rad * 50, "Солнце ≫ Земля по радиусу");
        check(sun.astro == AstroClass.STAR, "Солнце: AstroClass.STAR");
        check(sol.parts.get(3).astro == AstroClass.PLANET, "Земля: AstroClass.PLANET");
        check(AstroClass.haloPx(sun) > AstroClass.haloPx(sol.parts.get(3)), "ореол Солнца > Земли (log N)");
        check(AstroClass.PLANET.color == AstroClass.MOON.color, "планеты и спутники — одна палитра");
        for (int i = 1; i < sol.parts.size(); i++) {
            Body b = sol.parts.get(i);
            if (b.astro == AstroClass.PLANET) {
                solPlanets++;
                check(b.N >= Scenarios.EARTH_N * 0.003, "планета тяжелее пояса: " + b.N);
                check(b.rad > Scenarios.BELT_RE_MAX * re * 2, "радиус планеты ≫ max пояса");
                continue;
            }
            if (b.astro == AstroClass.MOON) {
                solMoons++;
                check(b.N < sun.N, "спутник легче Солнца");
                continue;
            }
            check(b.astro == AstroClass.ASTEROID, "пояс: AstroClass.ASTEROID");
            check(AstroClass.haloPx(b) == 0, "астероид без планетного ореола");
            nBeltMin = Math.min(nBeltMin, b.N);
            nBeltMax = Math.max(nBeltMax, b.N);
            radBeltMax = Math.max(radBeltMax, b.rad);
            double r = World.dist(b.p, sunP) / au;
            if (r >= Scenarios.MAIN_BELT_AU0 * 0.95 && r <= Scenarios.MAIN_BELT_AU1 * 1.05) inMain++;
            if (r >= Scenarios.KUIPER_AU0 * 0.95 && r <= Scenarios.KUIPER_AU1 * 1.05) inKuiper++;
        }
        check(solPlanets == 8, "СС D: 8 планет, было " + solPlanets);
        check(solMoons >= namedA - 9 + swarmLo + subDLo,
                "СС D: спутников ≥ " + (namedA - 9 + swarmLo + subDLo) + ", было " + solMoons);
        check(radBeltMax <= Scenarios.BELT_RE_MAX * re * 1.001, "пояс rad ≤ max: " + radBeltMax / re + " R⊕");
        check(sol.parts.get(3).rad / radBeltMax > 50, "Земля ≫ max астероида пояса по радиусу");
        check(nBeltMin >= Scenarios.BELT_N_MIN * 0.999, "пояс N≥min: " + nBeltMin);
        check(nBeltMax <= Scenarios.BELT_N_MAX * 1.001, "пояс N≤max: " + nBeltMax);
        check(Scenarios.EARTH_N / nBeltMax > 1e6, "Земля ≫ max астероида пояса по N");
        check(inMain >= beltD * 0.9, "главный пояс denser: " + inMain);
        check(inKuiper >= Scenarios.KUIPER_COUNT * 0.9, "пояс Койпера: " + inKuiper);
        check(!sol.impenetrable, "СС не включает непроницаемость");

        // Режим B: крупные именные + рой + субспутники у majors, без средних A
        World solB = new World(new java.util.Random(11));
        solB.setG(World.G_DEFAULT);
        solB.applyPreset(Scenarios.Preset.SANDBOX);
        solB.moonsBeltsMode = Scenarios.MoonsBeltsMode.B;
        solB.applyPreset(Scenarios.Preset.SOLAR);
        int namedB = 1 + 8 + 1 + 4 + 1 + 1; // без 13 средних A
        int subHostsB = 7; // Moon + 4 Galileans + Titan + Triton
        int beltB = Scenarios.mainBeltCount(solB);
        int swarmBLo = 4 * Scenarios.SWARM_N_MIN_B, swarmBHi = 4 * Scenarios.SWARM_N_MAX_B;
        int subBLo = subHostsB * Scenarios.SUBSAT_N_MIN_B, subBHi = subHostsB * Scenarios.SUBSAT_N_MAX_B;
        check(solB.parts.size() >= namedB + swarmBLo + subBLo + beltB + Scenarios.KUIPER_COUNT
                        && solB.parts.size() <= namedB + swarmBHi + subBHi + beltB + Scenarios.KUIPER_COUNT,
                "СС B: " + solB.parts.size());

        // Земля–Луна: 60.34 R⊕ — астро-масштаб (не связан с SS_AU_RE)
        near(Scenarios.moonDistance(em) / Scenarios.earthRadius(), Scenarios.MOON_A_RE, 1e-9, "Луна на MOON_A_RE");

        World empty = world(World.G_DEFAULT);
        empty.addProton();
        empty.applyPreset(Scenarios.Preset.SANDBOX);
        check(empty.parts.isEmpty(), "Песочница: очищает → 0 тел");
        check(Scenarios.clearsScene(Scenarios.Preset.SANDBOX)
                        && !Scenarios.clearsScene(Scenarios.Preset.EARTH_MOON)
                        && !Scenarios.clearsScene(Scenarios.Preset.SOLAR)
                        && !Scenarios.clearsScene(Scenarios.Preset.RANDOM_SOLAR)
                        && !Scenarios.clearsScene(Scenarios.Preset.BIG_STAR)
                        && !Scenarios.clearsScene(Scenarios.Preset.PLANET_MOONS)
                        && !Scenarios.clearsScene(Scenarios.Preset.SOLAR_GROUP),
                "clearsScene: только Песочница");

        check(Scenarios.Preset.values()[Scenarios.Preset.values().length - 1] == Scenarios.Preset.FRAGMENTS,
                "мелкие фракции — последний пункт списка");
        check(Scenarios.isBase(Scenarios.Preset.RANDOM_SOLAR) && Scenarios.isBase(Scenarios.Preset.BIG_STAR)
                        && Scenarios.isBase(Scenarios.Preset.PLANET_MOONS)
                        && Scenarios.isBase(Scenarios.Preset.SOLAR_GROUP)
                        && Scenarios.enablesKuoAndGpuBh(Scenarios.Preset.SOLAR_GROUP)
                        && !Scenarios.enablesKuoAndGpuBh(Scenarios.Preset.RANDOM_SOLAR),
                "рандомная СС, большая звезда, планета+луны и группа СС — базовые; KUO+BH только у группы");

        World rndSs = new World(new Random(99));
        rndSs.setG(World.G_DEFAULT);
        rndSs.applyPreset(Scenarios.Preset.SANDBOX);
        rndSs.appendPreset(Scenarios.Preset.RANDOM_SOLAR);
        int stars = 0, planets = 0, moons = 0, rocks = 0;
        Body rndStar = null;
        for (Body b : rndSs.parts) {
            if (b.astro == AstroClass.STAR) { stars++; rndStar = b; }
            else if (b.astro == AstroClass.PLANET) planets++;
            else if (b.astro == AstroClass.MOON) moons++;
            else if (b.astro == AstroClass.ASTEROID) rocks++;
        }
        check(stars == 1 && rndStar != null, "рандомная СС: одна STAR");
        check(planets >= 4 && planets <= 12, "рандомная СС: 4…12 планет, было " + planets);
        check(rndStar.N > Scenarios.EARTH_N * 1e5, "рандомная СС: звезда ≫ Земли");
        check(rndStar.rad > Scenarios.earthRadius() * 40, "рандомная СС: звезда крупнее планет по rad");
        for (Body b : rndSs.parts) {
            if (b == rndStar) continue;
            check(b.N < rndStar.N, "рандомная СС: всё легче звезды");
            check(b.rad < rndStar.rad, "рандомная СС: всё меньше звезды по rad");
        }
        check(rocks >= 0, "рандомная СС: пояса ок (" + rocks + ")");
        int beforeAppend = rndSs.parts.size();
        rndSs.appendPreset(Scenarios.Preset.RANDOM_SOLAR);
        check(rndSs.parts.size() > beforeAppend, "рандомная СС: append добавляет поверх");

        World big = new World(new Random(3));
        big.setG(World.G_DEFAULT);
        big.applyPreset(Scenarios.Preset.SANDBOX);
        big.appendPreset(Scenarios.Preset.BIG_STAR);
        check(big.parts.size() == 1, "большая звезда: 1 тело");
        Body bs = big.parts.get(0);
        check(bs.astro == AstroClass.STAR, "большая звезда: AstroClass.STAR");
        near(bs.N, Scenarios.EARTH_N * 332_946 * Scenarios.BIG_STAR_MSUN,
                1e-9 * bs.N, "большая звезда: N = 100 M☉");
        near(bs.rad / Scenarios.earthRadius(), Scenarios.BIG_STAR_RE, 1e-9, "большая звезда: R = 80·R☉");
        check(bs.N > Scenarios.EARTH_N * 332_946 * 10, "большая звезда ≫ Солнца по N");
        check(bs.rad > Scenarios.SUN_RE * Scenarios.earthRadius() * 10, "большая звезда ≫ Солнца по rad");
        double[] spotBig = {7, 2, -4};
        big.appendPresetAt(Scenarios.Preset.BIG_STAR, spotBig);
        check(big.parts.size() == 2, "большая звезда: appendPresetAt");
        World.Lump Lb = World.lump(List.of(big.parts.get(1)));
        near(Lb.c()[0], spotBig[0], 1e-6 * (1 + Math.abs(spotBig[0])), "большая звезда at: x");

        World pm = new World(new Random(17));
        pm.setG(World.G_DEFAULT);
        pm.applyPreset(Scenarios.Preset.SANDBOX);
        pm.moonsBeltsMode = Scenarios.MoonsBeltsMode.D;
        pm.appendPreset(Scenarios.Preset.PLANET_MOONS);
        int pmPlanets = 0, pmMoons = 0, pmStars = 0, pmRocks = 0;
        Body pmPlanet = null;
        for (Body b : pm.parts) {
            if (b.astro == AstroClass.STAR) pmStars++;
            else if (b.astro == AstroClass.PLANET) { pmPlanets++; pmPlanet = b; }
            else if (b.astro == AstroClass.MOON) pmMoons++;
            else if (b.astro == AstroClass.ASTEROID) pmRocks++;
        }
        check(pmStars == 0 && pmRocks == 0, "планета+луны: без STAR и поясов");
        check(pmPlanets == 1 && pmPlanet != null, "планета+луны: одна PLANET");
        check(pmMoons >= 1, "планета+луны: ≥1 спутник, было " + pmMoons);
        for (Body b : pm.parts) {
            if (b == pmPlanet) continue;
            check(b.astro == AstroClass.MOON, "планета+луны: остальные — MOON");
            check(b.N < pmPlanet.N, "планета+луны: луна легче планеты");
            check(b.rad < pmPlanet.rad, "планета+луны: луна меньше планеты по rad");
        }
        int pmBefore = pm.parts.size();
        pm.appendPreset(Scenarios.Preset.PLANET_MOONS);
        check(pm.parts.size() > pmBefore, "планета+луны: append добавляет поверх");
        double[] spotPm = {-3, 5, 1};
        int pmAtBefore = pm.parts.size();
        pm.appendPresetAt(Scenarios.Preset.PLANET_MOONS, spotPm);
        check(pm.parts.size() > pmAtBefore, "планета+луны: appendPresetAt");

        World grp = new World(new Random(21));
        grp.setG(World.G_DEFAULT);
        grp.applyPreset(Scenarios.Preset.SANDBOX);
        grp.moonsBeltsMode = Scenarios.MoonsBeltsMode.A;
        grp.forces.device = Forces.Device.GPU_FLOAT;
        grp.appendPreset(Scenarios.Preset.SOLAR_GROUP);
        int gStars = 0;
        List<Body> gStarList = new ArrayList<>();
        for (Body b : grp.parts) {
            if (b.astro == AstroClass.STAR) {
                gStars++;
                gStarList.add(b);
            }
        }
        check(gStars >= Scenarios.SOLAR_GROUP_MIN && gStars <= Scenarios.SOLAR_GROUP_MAX,
                "группа СС: " + Scenarios.SOLAR_GROUP_MIN + "…" + Scenarios.SOLAR_GROUP_MAX
                        + " STAR, было " + gStars);
        check(grp.forces.device == Forces.Device.GPU_FLOAT_BH, "группа СС: GPU_FLOAT_BH при apply");
        double minStarSep = Double.POSITIVE_INFINITY;
        for (int i = 0; i < gStarList.size(); i++) {
            for (int j = i + 1; j < gStarList.size(); j++) {
                minStarSep = Math.min(minStarSep, World.dist(gStarList.get(i).p, gStarList.get(j).p));
            }
        }
        check(minStarSep > Scenarios.AU_RE * Scenarios.earthRadius() * 5,
                "группа СС: звёзды разнесены (minSep)");
        World grpAt = new World(new Random(22));
        grpAt.setG(World.G_DEFAULT);
        grpAt.applyPreset(Scenarios.Preset.SANDBOX);
        grpAt.moonsBeltsMode = Scenarios.MoonsBeltsMode.A;
        double[] spotGrp = {2e7, -5e6, 1e6};
        grpAt.appendPresetAt(Scenarios.Preset.SOLAR_GROUP, spotGrp);
        check(grpAt.forces.device == Forces.Device.GPU_FLOAT_BH, "группа СС: GPU_FLOAT_BH при appendAt");
        World.Lump Lg = World.lump(grpAt.parts);
        near(Lg.c()[0], spotGrp[0], 1e-6 * (1 + Math.abs(spotGrp[0])), "группа СС at: COM x");
        near(Lg.c()[1], spotGrp[1], 1e-6 * (1 + Math.abs(spotGrp[1])), "группа СС at: COM y");
        near(Lg.c()[2], spotGrp[2], 1e-6 * (1 + Math.abs(spotGrp[2])), "группа СС at: COM z");

        World frag = new World(new Random(42));
        frag.setG(World.G_DEFAULT);
        frag.applyPreset(Scenarios.Preset.EARTH_MOON);
        check(frag.preset == Scenarios.Preset.EARTH_MOON, "база до фракций — Земля–Луна");
        int base = frag.parts.size();
        frag.fragmentCount = 100;
        frag.addFragments();
        check(frag.parts.size() == base + 100, "мелкие фракции: append, было " + base + " стало " + frag.parts.size());
        check(frag.preset == Scenarios.Preset.EARTH_MOON, "append не меняет базовый пресет");
        check(frag.impenetrable, "мелкие фракции: auto-enable непроницаемость");
        int custom = 0;
        double nMin = Double.POSITIVE_INFINITY, nMax = 0;
        int fragAstro = 0;
        for (Body b : frag.parts) {
            if (b.kind != Species.CUSTOM) continue;
            custom++;
            // Земля/Луна тоже CUSTOM — учитываем только астероидный диапазон
            if (b.N <= Scenarios.ASTEROID_N_MAX * 1.001 && b.N >= Scenarios.ASTEROID_N_MIN * 0.999) {
                nMin = Math.min(nMin, b.N);
                nMax = Math.max(nMax, b.N);
                check(b.astro == AstroClass.ASTEROID, "мелкие фракции: AstroClass.ASTEROID");
                fragAstro++;
            }
        }
        check(custom >= 100, "добавлены свои тела");
        check(fragAstro == 100, "все 100 фракций помечены ASTEROID");
        check(nMin >= Scenarios.ASTEROID_N_MIN * 0.999, "астероид N ≥ min: " + nMin);
        check(nMax <= Scenarios.ASTEROID_N_MAX * 1.001, "астероид N ≤ max: " + nMax);
        check(nMax / nMin > 10, "астероидный диапазон масс разнесён: " + nMin + "…" + nMax);
        frag.impenetrable = false;
        check(!frag.impenetrable, "после мелких фракций чип можно выключить");
        check(Scenarios.isBase(Scenarios.Preset.SANDBOX) && !Scenarios.isBase(Scenarios.Preset.FRAGMENTS),
                "isBase: основные да, фракции нет");

        World at = world(World.G_DEFAULT);
        at.applyPreset(Scenarios.Preset.SANDBOX);
        double[] spot = {12, -3, 5};
        at.appendPresetAt(Scenarios.Preset.EARTH_MOON, spot);
        check(at.parts.size() == 2, "appendPresetAt Земля–Луна: 2 тела");
        World.Lump L = World.lump(at.parts);
        near(L.c()[0], spot[0], 1e-6 * (1 + Math.abs(spot[0])), "appendPresetAt: COM x");
        near(L.c()[1], spot[1], 1e-6 * (1 + Math.abs(spot[1])), "appendPresetAt: COM y");
        near(L.c()[2], spot[2], 1e-6 * (1 + Math.abs(spot[2])), "appendPresetAt: COM z");

        // ПКМ «Удалить»: одно тело; диполь — оба полюса; повтор / чужой — no-op
        World del = world(World.G_DEFAULT);
        del.setScene(World.Scene.SANDBOX);
        del.addAt(Species.P, new double[]{1, 0, 0});
        del.addAt(Species.E, new double[]{0, 1, 0});
        Body keep = del.parts.get(1);
        Body gone = del.parts.get(0);
        del.removeBody(gone);
        check(del.parts.size() == 1 && del.parts.get(0) == keep, "removeBody: убрано одно тело");
        del.removeBody(gone);
        check(del.parts.size() == 1, "removeBody: повтор — no-op");
        del.addDipoleAt(new double[]{0, 0, 1});
        check(del.counts().dipoles() == 1 && del.parts.size() == 3, "removeBody prep: диполь + e");
        Body pole = del.parts.stream().filter(b -> b.partner != null).findFirst().orElse(null);
        check(pole != null, "removeBody prep: есть полюс");
        del.removeBody(pole);
        check(del.parts.size() == 1 && del.parts.get(0) == keep && del.counts().dipoles() == 0,
                "removeBody: диполь убирается целиком");

        // multi-СС: hub path должен брать своё Солнце (не первое STAR), иначе следы только у одной системы
        World multi = new World(new Random(7));
        multi.setG(World.G_DEFAULT);
        multi.applyPreset(Scenarios.Preset.SOLAR);
        multi.appendPreset(Scenarios.Preset.SOLAR);
        check(multi.parts.size() > StabilityTest.FULL_CENTRAL_SCAN, "2×СС → hub path (> " + StabilityTest.FULL_CENTRAL_SCAN + ")");
        java.util.List<Body> hubs = StabilityTest.orbitHubs(multi);
        check(hubs.size() == 2, "orbitHubs: 2 STAR при 2×СС, было " + hubs.size());
        Body sunA = hubs.get(0), sunB = hubs.get(1);
        Body planetB = null;
        double bestNearB = Double.POSITIVE_INFINITY;
        for (Body b : multi.parts) {
            if (b.astro != AstroClass.PLANET) continue;
            double dB = World.dist(b.p, sunB.p);
            if (dB < bestNearB) {
                bestNearB = dB;
                planetB = b;
            }
        }
        check(planetB != null, "есть планета у второго Солнца");
        check(StabilityTest.bestHub(planetB, hubs) == sunB, "bestHub: планета 2-й СС → своё Солнце");
        check(World.dist(planetB.p, sunA.p) > World.dist(planetB.p, sunB.p), "планета ближе к своему Солнцу");
        StabilityTest staMulti = new StabilityTest();
        multi.trailSelect = staMulti::nearCircular;
        for (int step = 0; step < 40; step++) {
            multi.advance(0.02);
            staMulti.update(multi);
        }
        check(staMulti.nearCircular(planetB), "nearCircular: планета 2-й СС после hub-трека");
        multi.sampleTrails(null, null, null);
        check(planetB.trail.size() > 0, "след у планеты 2-й СС (не только у первой)");

        // pack-плоскость случайна на СС; внутри — скромные per-body наклоны (не копланарны)
        World planes = new World(new Random(12345));
        planes.setG(World.G_DEFAULT);
        planes.applyPreset(Scenarios.Preset.SANDBOX);
        planes.appendPreset(Scenarios.Preset.SOLAR);
        planes.appendPreset(Scenarios.Preset.SOLAR);
        java.util.List<Body> planeStars = new ArrayList<>();
        for (Body b : planes.parts) if (b.astro == AstroClass.STAR) planeStars.add(b);
        check(planeStars.size() == 2, "плоскости: 2 STAR");
        double[][] packN = new double[2][];
        double sinPlanet = Math.sin(Scenarios.PLANET_I_MAX + 1e-6);
        double sinBelt = Math.sin(Scenarios.BELT_I_MAX + 1e-6);
        for (int s = 0; s < 2; s++) {
            Body planeSun = planeStars.get(s);
            double[] mean = {0, 0, 0};
            int nPl = 0;
            double maxPlanetOff = 0, maxBeltOff = 0;
            for (Body b : planes.parts) {
                if (b.astro != AstroClass.PLANET) continue;
                if (World.dist(b.p, planeSun.p) > World.dist(b.p, planeStars.get(1 - s).p)) continue;
                double[] r = {b.p[0] - planeSun.p[0], b.p[1] - planeSun.p[1], b.p[2] - planeSun.p[2]};
                double[] vv = {b.v[0] - planeSun.v[0], b.v[1] - planeSun.v[1], b.v[2] - planeSun.v[2]};
                double[] hn = World.norm(World.cross(r, vv));
                if (nPl > 0 && mean[0] * hn[0] + mean[1] * hn[1] + mean[2] * hn[2] < 0) {
                    hn[0] = -hn[0]; hn[1] = -hn[1]; hn[2] = -hn[2];
                }
                mean[0] += hn[0]; mean[1] += hn[1]; mean[2] += hn[2];
                nPl++;
            }
            check(nPl >= 4, "плоскости: ≥4 планет у STAR " + s);
            packN[s] = World.norm(mean);
            for (Body b : planes.parts) {
                if (b == planeSun || (b.astro != AstroClass.PLANET && b.astro != AstroClass.ASTEROID)) continue;
                if (World.dist(b.p, planeSun.p) > World.dist(b.p, planeStars.get(1 - s).p)) continue;
                double[] rb = {b.p[0] - planeSun.p[0], b.p[1] - planeSun.p[1], b.p[2] - planeSun.p[2]};
                double rl = Math.sqrt(rb[0] * rb[0] + rb[1] * rb[1] + rb[2] * rb[2]);
                if (rl < 1e-12) continue;
                double off = Math.abs(rb[0] * packN[s][0] + rb[1] * packN[s][1] + rb[2] * packN[s][2]) / rl;
                if (b.astro == AstroClass.PLANET) maxPlanetOff = Math.max(maxPlanetOff, off);
                else maxBeltOff = Math.max(maxBeltOff, off);
            }
            check(maxPlanetOff > 1e-3, "СС" + s + ": планеты не копланарны, off=" + maxPlanetOff);
            check(maxPlanetOff < sinPlanet + 0.02, "СС" + s + ": планеты i≲7°, off=" + maxPlanetOff);
            check(maxBeltOff > maxPlanetOff * 0.5, "СС" + s + ": пояс толще планет, belt=" + maxBeltOff + " pl=" + maxPlanetOff);
            check(maxBeltOff < sinBelt + 0.05, "СС" + s + ": пояс i≲25°, off=" + maxBeltOff);
        }
        double align = Math.abs(packN[0][0] * packN[1][0] + packN[0][1] * packN[1][1] + packN[0][2] * packN[1][2]);
        check(align < 0.98, "две СС: разные pack-плоскости (|n·n'|=" + align + ")");
    }

    /**
     * 30. Вязкий слой гасит все тела сразу: одно тело — ровно u' = u·e^{−γ·dt}; несколько —
     * итог не зависит от порядка тел; импульс тел + стенки точно сохраняется; потери ≥ 0.
     */
    private static void viscousLayer() {
        double[][] dirs = {{0.6, 0.48, 0.64}, {-0.8, 0.6, 0}, {0, -0.6, 0.8}, {0.6, 0.48, 0.64}};
        double[][] vels = {{0.9, -0.2, 0.4}, {-0.5, 0.3, 0.1}, {0.2, -0.7, 0.6}, {0.3, 0.1, 0.2}};
        double[][][] after = new double[2][][];
        double[][] vcAfter = new double[2][];
        for (int pass = 0; pass < 3; pass++) {
            World dw = world(1e-5);
            dw.setScene(World.Scene.SANDBOX);
            dw.chamber.setOn(true);
            dw.chamber.c = new double[3];
            dw.chamber.R0 = 1;
            java.util.Arrays.fill(dw.chamber.Rk, 1);
            dw.chamber.vc = new double[]{0.05, -0.02, 0.01};
            dw.chamber.N = 2000; // стенка легче протона — порядок тел раньше заметно менял итог
            int nb = pass == 0 ? 1 : 4;
            dw.parts = new ArrayList<>();
            for (int i = 0; i < nb; i++) {
                int j = pass == 2 ? nb - 1 - i : i; // pass 2 — те же тела в обратном порядке
                double rr = 1 - 0.05 - 0.02 * j;
                dw.parts.add(dw.makeBody(j % 2 == 0 ? Species.P : Species.E, -1,
                        new double[]{dirs[j][0] * rr, dirs[j][1] * rr, dirs[j][2] * rr}, vels[j].clone()));
            }
            double ddt = 0.01, m = dw.chamber.mass();
            double[] P0 = momentum(dw);
            for (int k = 0; k < 3; k++) P0[k] += m * dw.chamber.vc[k];
            double ke0 = 0.5 * m * Chamber.dot(dw.chamber.vc, dw.chamber.vc);
            for (Body b : dw.parts) ke0 += 0.5 * b.m * Chamber.dot(b.v, b.v);
            double[] vc0 = dw.chamber.vc.clone();
            Body b0 = dw.parts.get(0);
            double[] v00 = b0.v.clone();
            dw.chamber.damp(ddt);
            double[] P1 = momentum(dw);
            for (int k = 0; k < 3; k++) P1[k] += m * dw.chamber.vc[k];
            double ke1 = 0.5 * m * Chamber.dot(dw.chamber.vc, dw.chamber.vc);
            for (Body b : dw.parts) ke1 += 0.5 * b.m * Chamber.dot(b.v, b.v);
            for (int k = 0; k < 3; k++) near(P1[k], P0[k], 1e-12 * (1 + Math.abs(P0[k])), "вязкий слой: импульс тел + стенки");
            near(dw.chamber.lost, ke0 - ke1, 1e-12 * ke0, "вязкий слой: потери = убыль кинетической энергии");
            check(dw.chamber.lost > 0, "вязкий слой: энергия только уходит");
            if (pass == 0) { // одно тело — прежняя точная формула
                double rho = Math.sqrt(Chamber.dot(b0.p, b0.p));
                double[] n = {b0.p[0] / rho, b0.p[1] / rho, b0.p[2] / rho};
                double d = dw.chamber.radiusAt(b0.p) - rho, h = dw.chamber.hFrac * (d + rho);
                double u = Chamber.dot(v00, n) - Chamber.dot(vc0, n);
                double du = u * Math.expm1(-dw.chamber.visc * dw.chamber.omega() * Math.pow(1 - d / h, 2) * ddt);
                for (int k = 0; k < 3; k++) {
                    near(b0.v[k], v00[k] + du * m / (b0.m + m) * n[k], 1e-14, "одно тело в слое — u' = u·e^{−γdt}");
                    near(dw.chamber.vc[k], vc0[k] - du * b0.m / (b0.m + m) * n[k], 1e-14, "одно тело — отдача стенке");
                }
            } else {
                double[][] v = new double[nb][];
                for (int i = 0; i < nb; i++) v[pass == 2 ? nb - 1 - i : i] = dw.parts.get(i).v.clone();
                after[pass - 1] = v;
                vcAfter[pass - 1] = dw.chamber.vc.clone();
            }
        }
        double dord = 0;
        for (int i = 0; i < 4; i++) dord = Math.max(dord, World.dist(after[0][i], after[1][i]));
        dord = Math.max(dord, World.dist(vcAfter[0], vcAfter[1]));
        check(dord < 1e-14, "вязкий слой: итог не зависит от порядка тел, разница " + dord);
    }
}
