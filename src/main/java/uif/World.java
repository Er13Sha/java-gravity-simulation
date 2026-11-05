package uif;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Модель W = Q·N без интерфейса: тела, формула, шаг интегрирования, энергия.
 * Всё взаимодействие — через QN и φ; замкнутая область — в {@link Chamber}.
 */
public final class World {
    public enum Scene { ATOMS, SANDBOX }

    /**
     * Какие атомы собирать кнопкой «+ атом» и при сбросе сцены «атомы»:
     * число протонов Z и нейтронов. Полная таблица Менделеева (Z = 1…118):
     * для водорода — протий/дейтерий/тритий; для остальных — самый распространённый
     * или наиболее долгоживущий изотоп. Масса и радиус тела — из p/n/e (N = V·q),
     * отдельных полей mass/radius у вида атома нет.
     */
    public enum AtomType {
        H1("H-1 · водород (протий)", 1, 0),
        H2("H-2 · водород (дейтерий)", 1, 1),
        H3("H-3 · водород (тритий)", 1, 2),
        HE4("He-4 · гелий", 2, 2),
        LI7("Li-7 · литий", 3, 4),
        BE9("Be-9 · бериллий", 4, 5),
        B11("B-11 · бор", 5, 6),
        C12("C-12 · углерод", 6, 6),
        N14("N-14 · азот", 7, 7),
        O16("O-16 · кислород", 8, 8),
        F19("F-19 · фтор", 9, 10),
        NE20("Ne-20 · неон", 10, 10),
        NA23("Na-23 · натрий", 11, 12),
        MG24("Mg-24 · магний", 12, 12),
        AL27("Al-27 · алюминий", 13, 14),
        SI28("Si-28 · кремний", 14, 14),
        P31("P-31 · фосфор", 15, 16),
        S32("S-32 · сера", 16, 16),
        CL35("Cl-35 · хлор", 17, 18),
        AR40("Ar-40 · аргон", 18, 22),
        K39("K-39 · калий", 19, 20),
        CA40("Ca-40 · кальций", 20, 20),
        SC45("Sc-45 · скандий", 21, 24),
        TI48("Ti-48 · титан", 22, 26),
        V51("V-51 · ванадий", 23, 28),
        CR52("Cr-52 · хром", 24, 28),
        MN55("Mn-55 · марганец", 25, 30),
        FE56("Fe-56 · железо", 26, 30),
        CO59("Co-59 · кобальт", 27, 32),
        NI58("Ni-58 · никель", 28, 30),
        CU63("Cu-63 · медь", 29, 34),
        ZN64("Zn-64 · цинк", 30, 34),
        GA69("Ga-69 · галлий", 31, 38),
        GE74("Ge-74 · германий", 32, 42),
        AS75("As-75 · мышьяк", 33, 42),
        SE80("Se-80 · селен", 34, 46),
        BR79("Br-79 · бром", 35, 44),
        KR84("Kr-84 · криптон", 36, 48),
        RB85("Rb-85 · рубидий", 37, 48),
        SR88("Sr-88 · стронций", 38, 50),
        Y89("Y-89 · иттрий", 39, 50),
        ZR90("Zr-90 · цирконий", 40, 50),
        NB93("Nb-93 · ниобий", 41, 52),
        MO98("Mo-98 · молибден", 42, 56),
        TC98("Tc-98 · технеций", 43, 55),
        RU102("Ru-102 · рутений", 44, 58),
        RH103("Rh-103 · родий", 45, 58),
        PD106("Pd-106 · палладий", 46, 60),
        AG107("Ag-107 · серебро", 47, 60),
        CD114("Cd-114 · кадмий", 48, 66),
        IN115("In-115 · индий", 49, 66),
        SN120("Sn-120 · олово", 50, 70),
        SB121("Sb-121 · сурьма", 51, 70),
        TE130("Te-130 · теллур", 52, 78),
        I127("I-127 · иод", 53, 74),
        XE132("Xe-132 · ксенон", 54, 78),
        CS133("Cs-133 · цезий", 55, 78),
        BA138("Ba-138 · барий", 56, 82),
        LA139("La-139 · лантан", 57, 82),
        CE140("Ce-140 · церий", 58, 82),
        PR141("Pr-141 · празеодим", 59, 82),
        ND142("Nd-142 · неодим", 60, 82),
        PM145("Pm-145 · прометий", 61, 84),
        SM152("Sm-152 · самарий", 62, 90),
        EU153("Eu-153 · европий", 63, 90),
        GD158("Gd-158 · гадолиний", 64, 94),
        TB159("Tb-159 · тербий", 65, 94),
        DY164("Dy-164 · диспрозий", 66, 98),
        HO165("Ho-165 · гольмий", 67, 98),
        ER166("Er-166 · эрбий", 68, 98),
        TM169("Tm-169 · тулий", 69, 100),
        YB174("Yb-174 · иттербий", 70, 104),
        LU175("Lu-175 · лютеций", 71, 104),
        HF180("Hf-180 · гафний", 72, 108),
        TA181("Ta-181 · тантал", 73, 108),
        W184("W-184 · вольфрам", 74, 110),
        RE187("Re-187 · рений", 75, 112),
        OS192("Os-192 · осмий", 76, 116),
        IR193("Ir-193 · иридий", 77, 116),
        PT195("Pt-195 · платина", 78, 117),
        AU197("Au-197 · золото", 79, 118),
        HG202("Hg-202 · ртуть", 80, 122),
        TL205("Tl-205 · таллий", 81, 124),
        PB208("Pb-208 · свинец", 82, 126),
        BI209("Bi-209 · висмут", 83, 126),
        PO209("Po-209 · полоний", 84, 125),
        AT210("At-210 · астат", 85, 125),
        RN222("Rn-222 · радон", 86, 136),
        FR223("Fr-223 · франций", 87, 136),
        RA226("Ra-226 · радий", 88, 138),
        AC227("Ac-227 · актиний", 89, 138),
        TH232("Th-232 · торий", 90, 142),
        PA231("Pa-231 · протактиний", 91, 140),
        U238("U-238 · уран", 92, 146),
        NP237("Np-237 · нептуний", 93, 144),
        PU244("Pu-244 · плутоний", 94, 150),
        AM243("Am-243 · америций", 95, 148),
        CM247("Cm-247 · кюрий", 96, 151),
        BK247("Bk-247 · берклий", 97, 150),
        CF251("Cf-251 · калифорний", 98, 153),
        ES252("Es-252 · эйнштейний", 99, 153),
        FM257("Fm-257 · фермий", 100, 157),
        MD258("Md-258 · менделевий", 101, 157),
        NO259("No-259 · нобелий", 102, 157),
        LR266("Lr-266 · лоуренсий", 103, 163),
        RF267("Rf-267 · резерфордий", 104, 163),
        DB268("Db-268 · дубний", 105, 163),
        SG271("Sg-271 · сиборгий", 106, 165),
        BH270("Bh-270 · борий", 107, 163),
        HS277("Hs-277 · хассий", 108, 169),
        MT278("Mt-278 · мейтнерий", 109, 169),
        DS281("Ds-281 · дармштадтий", 110, 171),
        RG282("Rg-282 · рентгений", 111, 171),
        CN285("Cn-285 · коперниций", 112, 173),
        NH286("Nh-286 · нихоний", 113, 173),
        FL289("Fl-289 · флеровий", 114, 175),
        MC290("Mc-290 · московий", 115, 175),
        LV293("Lv-293 · ливерморий", 116, 177),
        TS294("Ts-294 · теннессин", 117, 177),
        OG294("Og-294 · оганесон", 118, 176);

        public final String title;
        public final int protons, neutrons;

        AtomType(String title, int protons, int neutrons) {
            this.title = title;
            this.protons = protons;
            this.neutrons = neutrons;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Молекулы для кнопки «+ молекула» — отдельный список, не смешанный с {@link AtomType}.
     * Состав — атомы {@link AtomType}; «+ молекула» собирает каждый атом через {@link #makeAtom}
     * и ставит их в типичную геометрию (линейная, угол, тетраэдр…).
     */
    public enum MoleculeType {
        H2("H₂", "H2 · водород", AtomType.H1, AtomType.H1),
        N2("N₂", "N2 · азот", AtomType.N14, AtomType.N14),
        O2("O₂", "O2 · кислород", AtomType.O16, AtomType.O16),
        H2O("H₂O", "H2O · вода", AtomType.O16, AtomType.H1, AtomType.H1),
        CO2("CO₂", "CO2 · углекислый газ", AtomType.C12, AtomType.O16, AtomType.O16),
        CH4("CH₄", "CH4 · метан", AtomType.C12, AtomType.H1, AtomType.H1, AtomType.H1, AtomType.H1),
        NH3("NH₃", "NH3 · аммиак", AtomType.N14, AtomType.H1, AtomType.H1, AtomType.H1),
        CO("CO", "CO · угарный газ", AtomType.C12, AtomType.O16),
        NO("NO", "NO · оксид азота", AtomType.N14, AtomType.O16),
        HCL("HCl", "HCl · хлороводород", AtomType.H1, AtomType.CL35),
        SO2("SO₂", "SO2 · сернистый газ", AtomType.S32, AtomType.O16, AtomType.O16);

        public final String formula, title;
        public final AtomType[] atoms;

        MoleculeType(String formula, String title, AtomType... atoms) {
            this.formula = formula;
            this.title = title;
            this.atoms = atoms;
        }

        /** Суммарная масса состава в m_e (справочно; на сцене — отдельные атомы). */
        public double mass() {
            double m = 0;
            for (AtomType a : atoms) {
                m += a.protons * Formula.M_P + a.neutrons * Formula.M_N + a.protons * Formula.M_E;
            }
            return m;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Как кнопки «+» добавляют частицы: на круговую орбиту вокруг имеющихся тел, в покое или броском
     * к их центру масс (скорость — throwK × круговая, прицел throwAim: 0 — в центр, 1 — по касательной).
     */
    public enum Spawn {
        ORBIT("Орбита"), REST("Покой"), THROW("Бросок");

        public final String title;

        Spawn(String title) {
            this.title = title;
        }
    }

    /**
     * Путь сил. {@link #KUO} — через {@link Kuo} (чип «КУО» вкл/выкл: полный комплекс или только
     * −β_т·W). {@link #QN} — прямое W = Q·N (тесты эквивалентности; UI чипом не ставит).
     */
    public enum Mode { QN, KUO }

    public static final double G_HONEST = 2.4e-43; // реальная G в атомных единицах
    /** Умеренный масштаб тяготения для наблюдения контактных опытов. */
    public static final double G_MEDIUM = 3e-5;
    /** Старт и «перезагрузить»: G = 1 (заметное тяготение без отдельного «демо»-пресета). */
    public static final double G_DEFAULT = 1;
    /**
     * Верх ползунка log₁₀ G (тема «Тяготение»). Было 6 (G≤10⁶); 6.5 → G≤10^{6.5}≈3.16·10⁶ (~3×).
     */
    public static final double G_LOG_MAX = 6.5;
    /** Низ ползунка log₁₀ G (до «честной» и слабее). */
    public static final double G_LOG_MIN = -45;
    private static final double[] ZERO = {0, 0, 0};

    public double G, C1;
    public static final double R0 = 1.0, RN = 0.45; // орбиты электрона и нейтрона
    public final double r0 = R0, rn = RN;
    public Scene scene = Scene.SANDBOX;
    /** Пресет наполнения (вкладка «Сценарий»); поверх {@link #scene}. */
    public Scenarios.Preset preset = Scenarios.Preset.SANDBOX;
    /** Доля массы Луны от Земли для пресетов Земля–Луна / СС (поле в UI). */
    public double moonShare = Scenarios.MOON_SHARE_DEFAULT;
    /**
     * Режим спутников/поясов для SOLAR / RANDOM_SOLAR / PLANET_MOONS / SOLAR_GROUP
     * (сегмент на вкладке «Сценарий»). Действует при следующей генерации / append /
     * RMB «создать здесь».
     */
    public Scenarios.MoonsBeltsMode moonsBeltsMode = Scenarios.MOONS_BELTS_DEFAULT;
    /**
     * Option B «приливы к экватору»: феноменологическое демпфирование v⊥ к экватору родителя.
     * Не из W=Q·N; выкл по умолчанию. Один чип в «Виде» включает физику и оверлей.
     */
    public boolean tidalEquator;
    /**
     * Нормаль pack/орбитальной плоскости последнего пресета (единичный); все нули — не задана.
     * Запасной экватор, если у hub нет орбиты вокруг более тяжёлого (планета со спутниками).
     */
    public final double[] packNormal = new double[3];
    /** Кэш нормалей экватора hub → n̂ для оверлея (обновляется в {@link #tidalEquatorAccel}). */
    public final IdentityHashMap<Body, double[]> tideEquatorN = new IdentityHashMap<>();
    /** Кэш радиуса диска экватора hub (мир) для оверлея. */
    public final IdentityHashMap<Body, Double> tideDiskR = new IdentityHashMap<>();
    /** Число тел пресета «мелкие фракции». */
    public int fragmentCount = Scenarios.FRAGMENT_COUNT_DEFAULT;
    public AtomType atomType = AtomType.H2;
    public MoleculeType moleculeType = MoleculeType.H2O;
    /**
     * Группы атомов, поставленных «+ молекула» (id атомов). Нужны для счётчика молекул и «− молекула».
     * Пустые/частично разобранные группы при подсчёте пропускаются.
     */
    private final List<int[]> moleculeGroups = new ArrayList<>();
    public List<Body> parts;
    public double time;
    public final Chamber chamber = new Chamber(this);
    public final Kuo kuo = new Kuo(this);
    /** Перебор пар: один поток, все ядра или видеокарта — результат один и тот же до бита. */
    public final Forces forces = new Forces(this);
    /** Всегда КУО при запуске / «перезагрузить»; UI другое не выбирает. */
    public Mode mode = Mode.KUO;
    public Spawn spawn = Spawn.ORBIT;
    public double throwK = THROW_K, throwAim = THROW_AIM;
    public static final double THROW_K = 1, THROW_AIM = 0.3;
    /**
     * Новые диполи (добавка модели): заряд полюса N (меньше электрона), равновесная длина L,
     * f(α) внутри диполя и сила экранирования χ (во сколько раз прижатие от КУО вдали сильнее
     * отталкивания полюсов). Радиус экранирования R_s = L·χ^{1/3}. Меняются только у новых диполей.
     */
    public double dipoleNp = DIPOLE_NP, dipoleNm = DIPOLE_NM, dipoleL = DIPOLE_L, dipoleF = DIPOLE_F, dipoleChi = DIPOLE_CHI;
    public static final double DIPOLE_NP = 2e-3, DIPOLE_NM = 1e-3, DIPOLE_L = 0.02, DIPOLE_F = 1e6, DIPOLE_CHI = 8;

    private final Random rnd;
    private int nextAtom = 1, nextNuc = 0;
    private boolean e0Set;
    private double e0, simAcc, trailT = Double.NEGATIVE_INFINITY;
    private double dyn = Double.POSITIVE_INFINITY; // dynTime без замкнутой области, из последнего accelerate()
    /** Копия последних a[i] после {@link #accelerate()} — для визуального squash (не трогает физику). */
    private double[][] lastA;
    /** true только сразу после host {@link #accelerate()}; resident GPU сбрасывает — иначе stale a. */
    private boolean accelFresh;

    public World() {
        this(new Random());
    }

    public World(Random rnd) {
        this.rnd = rnd;
        G = G_DEFAULT;
        C1 = 4 * Math.PI * G;
        chamber.R0 = scene == Scene.SANDBOX ? 8 : 5; // песочница: ≥ молекула H₂ (bond≈3) + запас; дальше ensureInner
        parts = makeParts();
    }

    // ---------- формула ----------

    /** Q = k_a·C1/(4πR²) — качество воздействия источника на расстоянии r. */
    public double Q(double r) {
        return Formula.K_A * C1 / (4 * Math.PI * r * r);
    }

    /** Q·r². */
    public double kQ() {
        return Formula.K_A * C1 / (4 * Math.PI);
    }

    /**
     * Q_j·N_j на расстоянии r. Внутри шара радиуса R действует только заряд, попавший
     * внутрь: N(r) = V(r)·q = N·(r/R)³ — та же N = Vq, без обрезки r.
     */
    public double QN(double r, double R, double Nj) {
        if (r <= 0) return 0;
        return Q(r) * (r >= R ? Nj : Nj * Math.pow(r / R, 3));
    }

    /**
     * Сила пары (как QN, со знаком: + — к B). Обычная пара — sᵢsⱼ·QN: одинаковые знаки притягиваются,
     * разные отталкиваются. Полюса одного диполя: f(α)·(χ·QN(r, R_s) − QN(r, R)) — отталкивание
     * друг от друга и прижатие от КУО, который каждый полюс закрывает от другого.
     */
    public double pairQN(Body A, Body B, double r) {
        double rad = Math.max(A.rad, B.rad);
        if (A.partner == B) return A.fIn * (A.chi * QN(r, A.rs, B.N) - QN(r, rad, B.N));
        return A.sign * B.sign * QN(r, rad, B.N);
    }

    /** Потенциал пары на kQ·N_A·N_B, согласованный с pairQN: U = −f(α)·k_v·kQ·N_A·N_B·pairPhi. */
    public static double pairPhi(Body A, Body B, double r) {
        double rad = Math.max(A.rad, B.rad);
        if (A.partner == B) return A.fIn * (A.chi * Formula.phi(r, A.rs) - Formula.phi(r, rad));
        return A.sign * B.sign * Formula.phi(r, rad);
    }

    /** Множитель силы пары для оценки времени колебаний: у полюсов диполя f(α)·(1 + χ), иначе 1. */
    static double pairScale(Body A, Body B) {
        return A.partner == B ? A.fIn * (1 + A.chi) : 1;
    }

    /** Энергия связи диполя: сколько энергии нужно, чтобы развести полюса из равновесия на бесконечность. */
    public double bindingEnergy(Body A) {
        if (A.partner == null) return 0;
        double r = Formula.F_ALPHA * Formula.K_V * kQ() * A.N * A.partner.N;
        double L = A.rs / Math.cbrt(A.chi);
        return r * pairPhi(A, A.partner, L);
    }

    public void setG(double g) {
        G = g;
        C1 = 4 * Math.PI * g;
        changed();
    }

    // ---------- тела и группы ----------

    public record Lump(double N, double m, double R, double[] c, double[] cv) {}

    /** Центр масс, суммарные N и m набора тел. */
    public static Lump lump(List<Body> bodies) {
        double N = 0, m = 0, R = 0;
        double[] c = new double[3], cv = new double[3];
        for (Body b : bodies) {
            N += b.sign * b.N; // заряд со знаком: у диполя — N₊ − N₋; у p, n, e знак +
            m += b.m;
            R = Math.max(R, b.rad);
            for (int k = 0; k < 3; k++) {
                c[k] += b.m * b.p[k];
                cv[k] += b.m * b.v[k];
            }
        }
        for (int k = 0; k < 3; k++) {
            c[k] /= m;
            cv[k] /= m;
        }
        return new Lump(N, m, R, c, cv);
    }

    public Body makeBody(Species kind, int atom, double[] pos, double[] vel) {
        return new Body(kind, atom, pos, vel);
    }

    /** Нуклон — одно тело с номером nuc; возвращается списком, чтобы работать с ним как с группой. */
    public List<Body> makeNucleon(Species kind, int atom, double[] pos) {
        Body b = new Body(kind, atom, pos, ZERO);
        b.nuc = nextNuc++;
        return List.of(b);
    }

    /** Круговая орбита составного тела moving вокруг группы group — см. launch(). */
    List<Body> orbit(List<Body> moving, List<Body> group, double r) {
        return launch(moving, group, r, Spawn.ORBIT);
    }

    /**
     * Круговая орбита с заданными единичными радиус-вектором {@code u} (от group к moving)
     * и касательной {@code t} — для астрономических пресетов в плоскости.
     */
    List<Body> circularOrbit(List<Body> moving, List<Body> group, double r, double[] u, double[] t) {
        return launch(moving, group, r, Spawn.ORBIT, norm(u), norm(t));
    }

    /**
     * Орбитальная плоскость: {@code [0]=ex}, {@code [1]=ey} в плоскости, {@code [2]=n} — нормаль (направление L).
     */
    double[][] randomOrbitPlane() {
        double[] n = norm(randDir());
        double[] ex = norm(cross(n, randDir()));
        if (ex[0] == 0 && ex[1] == 0 && ex[2] == 0) ex = norm(cross(n, new double[]{1, 0, 0}));
        double[] ey = norm(cross(n, ex));
        return new double[][]{ex, ey, n};
    }

    /**
     * Круговая орбита в плоскости {@code plane} при фазе {@code ang} (рад): u и t из ex/ey.
     */
    List<Body> orbitInPlane(List<Body> moving, List<Body> group, double r, double[][] plane, double ang) {
        double[] ex = plane[0], ey = plane[1];
        double c = Math.cos(ang), s = Math.sin(ang);
        double[] u = {c * ex[0] + s * ey[0], c * ex[1] + s * ey[1], c * ex[2] + s * ey[2]};
        double[] t = {-s * ex[0] + c * ey[0], -s * ex[1] + c * ey[1], -s * ex[2] + c * ey[2]};
        return circularOrbit(moving, group, r, u, t);
    }

    /**
     * Ставит составное тело moving на расстояние r от центра масс группы group (направление случайное).
     * Круговая скорость — из той же формулы: a = QN(r, R, 1)·(N_b·N_g/m_b + N_g·N_b/m_g) = v²/r.
     * ORBIT — относительная скорость v по касательной; THROW — throwK·v к центру масс группы,
     * отклонённая прицелом throwAim к касательной; REST — скорости не меняются (новое тело в покое).
     * В ORBIT и THROW импульс делится между moving и группой — суммарный импульс не меняется.
     * Все тела moving сдвигаются одинаково.
     */
    List<Body> launch(List<Body> moving, List<Body> group, double r, Spawn how) {
        double[] n = norm(randDir());
        double[] u = norm(cross(n, randDir()));
        double[] t = norm(cross(n, u));
        return launch(moving, group, r, how, u, t);
    }

    private List<Body> launch(List<Body> moving, List<Body> group, double r, Spawn how, double[] u, double[] t) {
        Lump b = lump(moving), g = lump(group);
        double aRel = QN(r, Math.max(b.R(), g.R()), 1) * (b.N() * g.N() / b.m() + g.N() * b.N() / g.m());
        double vRel = Math.sqrt(Math.abs(aRel) * r); // при отталкивании — та же величина, как мерка скорости
        double[] rel = new double[3];
        if (how == Spawn.ORBIT) rel = new double[]{t[0] * vRel, t[1] * vRel, t[2] * vRel};
        if (how == Spawn.THROW) {
            double[] d = norm(new double[]{-u[0] + throwAim * t[0], -u[1] + throwAim * t[1], -u[2] + throwAim * t[2]});
            double v = throwK * vRel;
            rel = new double[]{d[0] * v, d[1] * v, d[2] * v};
        }
        double mt = g.m() + b.m();
        double[] dp = new double[3], dvb = new double[3], dvg = new double[3];
        for (int k = 0; k < 3; k++) {
            dp[k] = g.c()[k] + u[k] * r - b.c()[k];
            if (how == Spawn.REST) continue;
            double V = (b.m() * b.cv()[k] + g.m() * g.cv()[k]) / mt; // скорость общего центра масс не меняется
            dvb[k] = V + rel[k] * g.m() / mt - b.cv()[k];
            dvg[k] = V - rel[k] * b.m() / mt - g.cv()[k];
        }
        for (Body x : moving) {
            for (int k = 0; k < 3; k++) {
                x.p[k] += dp[k];
                x.v[k] += dvb[k];
            }
        }
        for (Body x : group) {
            for (int k = 0; k < 3; k++) x.v[k] += dvg[k];
        }
        return moving;
    }

    /**
     * Атом выбранного типа ({@link #atomType}). Первый протон в покое; каждый следующий нуклон
     * (p и n по очереди) — на круговой орбите вокруг уже собранного ядра на радиусе rn·(1 + 0.35·k);
     * электроны — на круговых орбитах вокруг ядра на радиусах r0·(1 + 0.6·k).
     * Оболочек и квантовых уровней в модели нет: ядро и атом держатся только тяготением W = Q·N.
     * Плоскость каждой орбиты случайна ({@link #orbit}).
     */
    List<Body> makeAtom(int atom, double[] base) {
        return makeAtom(atomType, atom, base, null);
    }

    /**
     * Атом заданного вида в точке base (ядро около base). Для молекул — каждый атом состава отдельно.
     * {@code plane == null} — случайная плоскость на каждую орбиту; иначе все орбиты атома в {@code plane}.
     */
    List<Body> makeAtom(AtomType t, int atom, double[] base) {
        return makeAtom(t, atom, base, null);
    }

    /**
     * Атом вида {@code t}. При ненулевом {@code plane} ({@code [ex,ey,n]}) все нуклоны и электроны
     * на круговых орбитах в одной плоскости (фаза 0.7·индекс); иначе — {@link #orbit} со случайной нормалью.
     */
    List<Body> makeAtom(AtomType t, int atom, double[] base, double[][] plane) {
        List<Body> nucleus = new ArrayList<>(makeNucleon(Species.P, atom, base));
        int p = 1, n = 0, k = 0;
        while (p < t.protons || n < t.neutrons) {
            Species kind = (n < t.neutrons && (n < p || p >= t.protons)) ? Species.N : Species.P;
            double r = rn * (1 + 0.35 * k);
            List<Body> nuc = makeNucleon(kind, atom, base);
            List<Body> around = new ArrayList<>(nucleus);
            if (plane == null) nucleus.addAll(orbit(nuc, around, r));
            else nucleus.addAll(orbitInPlane(nuc, around, r, plane, 0.7 * k));
            k++;
            if (kind == Species.N) n++;
            else p++;
        }
        List<Body> all = new ArrayList<>(nucleus);
        for (int e = 0; e < t.protons; e++) {
            double r = r0 * (1 + 0.6 * e);
            List<Body> el = List.of(new Body(Species.E, atom, base, ZERO));
            List<Body> around = new ArrayList<>(all);
            if (plane == null) all.addAll(orbit(el, around, r));
            else all.addAll(orbitInPlane(el, around, r, plane, 0.7 * e));
        }
        return all;
    }

    /** Сцена «атомы» — старт с одним атомом выбранного типа; «песочница» — пусто, частицы по одной. */
    List<Body> makeParts() {
        return scene == Scene.SANDBOX ? new ArrayList<>() : new ArrayList<>(makeAtom(0, new double[3]));
    }

    public record Nucleon(int nuc, Species kind, int atom, List<Body> bodies) {}

    /** Нуклоны как группы тел, в порядке появления. */
    public List<Nucleon> nucleons() {
        Map<Integer, Nucleon> m = new LinkedHashMap<>();
        for (Body q : parts) {
            if (!q.isNucleon()) continue;
            m.computeIfAbsent(q.nuc, k -> new Nucleon(k, q.kind, q.atom, new ArrayList<>())).bodies().add(q);
        }
        return new ArrayList<>(m.values());
    }

    List<Integer> atomIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Body q : parts) if (q.isNucleon() && q.kind == Species.P && q.atom >= 0) ids.add(q.atom);
        return new ArrayList<>(ids);
    }

    /** Состав или параметры изменились: ΔE/E считается от новой энергии. */
    public void changed() {
        e0Set = false;
        changes++;
    }

    /** Счётчик изменений состава и параметров — по нему окно понимает, что энергию пора пересчитать. */
    public long changes;

    private void removeLast(Predicate<Body> pred) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (pred.test(parts.get(i))) {
                parts.remove(i);
                changed();
                return;
            }
        }
    }

    /** Нуклон удаляется целиком — всеми своими телами. */
    private void removeNucleon(Predicate<Nucleon> pred) {
        List<Nucleon> list = nucleons();
        for (int i = list.size() - 1; i >= 0; i--) {
            Nucleon g = list.get(i);
            if (pred.test(g)) {
                parts.removeIf(q -> q.nuc == g.nuc());
                changed();
                return;
            }
        }
    }

    public double[] centerOfMass() {
        double mx = 0, my = 0, mz = 0, mm = 0;
        for (Body p : parts) {
            mx += p.m * p.p[0];
            my += p.m * p.p[1];
            mz += p.m * p.p[2];
            mm += p.m;
        }
        return mm > 0 ? new double[]{mx / mm, my / mm, mz / mm} : new double[3];
    }

    // ---------- кнопки панели ----------

    public void addAtom() {
        double soft = atomSoftR(atomType);
        // от центра масс: ≥ 2.5·мягкого радиуса, чтобы новый атом не садился внутрь уже стоящего
        addPlaced(makeAtom(nextAtom++, centerOfMass()), 2.5 * soft, 2.5 * soft + 2.0, spawn);
    }

    public void removeAtom() {
        List<Integer> ids = atomIds();
        if (ids.isEmpty()) return;
        int atom = ids.get(ids.size() - 1);
        parts.removeIf(q -> q.atom == atom);
        changed();
    }

    /**
     * Молекула выбранного вида — атомы состава ({@link MoleculeType#atoms}) через {@link #makeAtom},
     * расставленные в типичной геометрии вокруг base. Одна общая орбитальная плоскость на всю молекулу:
     * внутренние орбиты атомов и межъядерные {@link #bindMoleculeAtoms} — с одной нормалью L.
     * Связей нет: периферийные атомы получают круговую орбиту вокруг первого, иначе при нулевой
     * относительной скорости ядра падают насквозь и разлетаются.
     */
    List<Body> makeMolecule(double[] base) {
        MoleculeType mol = moleculeType;
        return makeMolecule(base, moleculeOffsets(mol));
    }

    List<Body> makeMolecule(double[] base, double[][] offs) {
        MoleculeType mol = moleculeType;
        double[][] plane = randomOrbitPlane(); // одна плоскость на всю молекулу
        List<Body> all = new ArrayList<>();
        int[] ids = new int[mol.atoms.length];
        for (int i = 0; i < mol.atoms.length; i++) {
            int id = nextAtom++;
            ids[i] = id;
            double[] pos = {base[0] + offs[i][0], base[1] + offs[i][1], base[2] + offs[i][2]};
            List<Body> atom = makeAtom(mol.atoms[i], id, pos, plane);
            alignNucleus(atom, pos);
            all.addAll(atom);
        }
        bindMoleculeAtoms(all, ids, plane);
        moleculeGroups.add(ids);
        return all;
    }

    /**
     * Круговая орбита каждого атома ids[i], i≥1, вокруг атомов ids[0] (и уже привязанных).
     * u — текущий радиус CM→CM; t = n×u в плоскости {@code plane} (та же нормаль, что у атомов).
     */
    private void bindMoleculeAtoms(List<Body> all, int[] ids, double[][] plane) {
        if (ids.length < 2) return;
        Map<Integer, List<Body>> byAtom = new LinkedHashMap<>();
        for (int id : ids) byAtom.put(id, new ArrayList<>());
        for (Body b : all) {
            List<Body> g = byAtom.get(b.atom);
            if (g != null) g.add(b);
        }
        double[] n = plane[2];
        List<Body> central = new ArrayList<>(byAtom.get(ids[0]));
        for (int i = 1; i < ids.length; i++) {
            List<Body> sat = byAtom.get(ids[i]);
            if (sat == null || sat.isEmpty() || central.isEmpty()) continue;
            double[] cs = lump(sat).c(), cc = lump(central).c();
            double r = dist(cs, cc);
            if (r <= 1e-12) {
                central.addAll(sat);
                continue;
            }
            double[] u = norm(new double[]{cs[0] - cc[0], cs[1] - cc[1], cs[2] - cc[2]});
            double[] t = cross(n, u);
            double tl = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
            if (tl < 1e-12) t = plane[1].clone(); // радиус вдоль нормали — касательная из плоскости
            else t = new double[]{t[0] / tl, t[1] / tl, t[2] / tl};
            circularOrbit(sat, central, r, u, t);
            central.addAll(sat); // следующий спутник — вокруг уже собранной группы
        }
    }

    /**
     * Смещения ядер атомов относительно центра молекулы (в a₀).
     * Двухатомные — вдоль оси; H₂O / SO₂ — угол; CO₂ — линейная; NH₃ — пирамида; CH₄ — тетраэдр.
     */
    private double[][] moleculeOffsets(MoleculeType mol) {
        AtomType[] a = mol.atoms;
        double d = bondLen(a[0], a.length > 1 ? a[1] : a[0]);
        return switch (mol) {
            case H2, N2, O2, CO, NO, HCL -> new double[][]{
                    {-0.5 * d, 0, 0}, {0.5 * d, 0, 0}};
            case H2O -> { // O в центре, H под углом ~104.5°
                double half = Math.toRadians(104.5 / 2);
                double dh = bondLen(a[0], a[1]);
                yield new double[][]{
                        {0, 0, 0},
                        {dh * Math.sin(half), dh * Math.cos(half), 0},
                        {-dh * Math.sin(half), dh * Math.cos(half), 0}};
            }
            case CO2 -> { // O–C–O линейно
                double dc = bondLen(a[0], a[1]);
                yield new double[][]{{0, 0, 0}, {-dc, 0, 0}, {dc, 0, 0}};
            }
            case SO2 -> { // S в центре, O под углом ~119°
                double half = Math.toRadians(119.0 / 2);
                double ds = bondLen(a[0], a[1]);
                yield new double[][]{
                        {0, 0, 0},
                        {ds * Math.sin(half), ds * Math.cos(half), 0},
                        {-ds * Math.sin(half), ds * Math.cos(half), 0}};
            }
            case NH3 -> { // N в центре, 3 H — равносторонний треугольник чуть ниже
                double dn = bondLen(a[0], a[1]);
                double r = dn * 0.94, z = -dn * 0.35;
                yield new double[][]{
                        {0, 0, 0},
                        {r, 0, z},
                        {-0.5 * r, r * Math.sqrt(3) / 2, z},
                        {-0.5 * r, -r * Math.sqrt(3) / 2, z}};
            }
            case CH4 -> { // C в центре, 4 H — тетраэдр
                double dc = bondLen(a[0], a[1]);
                double s = dc / Math.sqrt(3);
                yield new double[][]{
                        {0, 0, 0},
                        {s, s, s}, {s, -s, -s}, {-s, s, -s}, {-s, -s, s}};
            }
        };
    }

    /**
     * Мягкий радиус атома в a₀ — орбита внешнего электрона ({@code r0·(1 + 0.6·(Z−1))}).
     * Радиусы тел p/n/e из N = V·q на порядки меньше и для расстановки молекулы не годятся.
     */
    private double atomSoftR(AtomType t) {
        return r0 * (1 + 0.6 * Math.max(0, t.protons - 1));
    }

    /**
     * Расстояние между ядрами соседних атомов: {@code Rₐ + Rᵦ + 1}, где R — {@link #atomSoftR}.
     * Облака e почти соприкасаются с зазором 1 a₀ (H₂ ≈ 3). Раньше 2.5·(Rₐ+Rᵦ) разносило H₂ на 5
     * без орбитальной скорости — ядра падали насквозь; с {@link #bindMoleculeAtoms} и этим d держится.
     */
    private double bondLen(AtomType a, AtomType b) {
        return atomSoftR(a) + atomSoftR(b) + 1.0;
    }

    /** Макс. |смещение| ядра от центра молекулы (половина характерного размера). */
    private double moleculeExtent(MoleculeType mol) {
        double[][] offs = moleculeOffsets(mol);
        double m = 0;
        for (double[] o : offs) {
            double r = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
            if (r > m) m = r;
        }
        return m;
    }

    public void addMolecule() {
        double ext = moleculeExtent(moleculeType);
        addPlaced(makeMolecule(centerOfMass()), ext + 2.0, ext + 4.0, spawn);
    }

    public void removeMolecule() {
        for (int g = moleculeGroups.size() - 1; g >= 0; g--) {
            int[] ids = moleculeGroups.get(g);
            Set<Integer> live = new LinkedHashSet<>(atomIds());
            boolean any = false;
            for (int id : ids) if (live.contains(id)) { any = true; break; }
            if (!any) {
                moleculeGroups.remove(g);
                continue;
            }
            Set<Integer> drop = new LinkedHashSet<>();
            for (int id : ids) drop.add(id);
            parts.removeIf(q -> drop.contains(q.atom));
            moleculeGroups.remove(g);
            changed();
            return;
        }
    }

    /** Молекула выбранного вида в заданной точке (центр геометрии). */
    public void addMoleculeAt(double[] at) {
        double[] p = at.clone();
        if (chamber.on) {
            double ext = moleculeExtent(moleculeType) + atomSoftR(moleculeType.atoms[0]) + 2.0;
            chamber.ensureInner(dist(p, chamber.c) + ext);
            p = inside(p);
        }
        parts.addAll(makeMolecule(p));
        if (chamber.on) chamber.ensureInner(cloudRadius(parts) + 2.0);
        changed();
    }

    /** Сколько молекул ещё имеют хотя бы один живой атом на сцене. */
    private int liveMolecules() {
        Set<Integer> live = new LinkedHashSet<>(atomIds());
        int n = 0;
        for (int[] ids : moleculeGroups) {
            for (int id : ids) {
                if (live.contains(id)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    public void addNeutron() {
        if (scene == Scene.SANDBOX) {
            addPlaced(makeNucleon(Species.N, -1, centerOfMass()), 1.5, 3, spawn);
            return;
        }
        List<Integer> ids = atomIds();
        if (ids.isEmpty()) return;
        Set<Integer> withN = new LinkedHashSet<>();
        for (Body q : parts) if (q.isNucleon() && q.kind == Species.N) withN.add(q.atom);
        int atom = ids.stream().filter(a -> !withN.contains(a)).findFirst().orElse(ids.get(ids.size() - 1));
        List<Body> nucleus = new ArrayList<>();
        for (Body q : parts) if (q.isNucleon() && q.atom == atom) nucleus.add(q);
        long index = nucleus.stream().filter(q -> q.kind == Species.N).map(q -> q.nuc).distinct().count();
        Body host = nucleus.get(0);
        parts.addAll(orbit(makeNucleon(Species.N, atom, host.p), nucleus, rn * (1 + 0.35 * index)));
        changed();
    }

    public void removeNeutron() {
        removeNucleon(g -> g.kind() == Species.N);
    }

    public void addProton() {
        addPlaced(makeNucleon(Species.P, -1, centerOfMass()), 1.5, 3, spawn);
    }

    public void removeProton() {
        removeNucleon(g -> g.kind() == Species.P && g.atom() == -1);
    }

    public void addElectron() {
        addPlaced(List.of(new Body(Species.E, -1, centerOfMass(), ZERO)), 0.8, 2.2, spawn);
    }

    /** Масса своей частицы в массах электрона (N = m, радиус из N = V·q); 1 — как электрон. */
    public double customMass = 1;

    /** Своя частица: тело с заданной массой N = customMass. */
    public void addCustom() {
        double r0 = customMass >= 10 ? 1.5 : 0.8, r1 = customMass >= 10 ? 3 : 2.2; // тяжёлую ставим дальше
        addPlaced(List.of(new Body(Species.CUSTOM, customMass, centerOfMass(), ZERO)), r0, r1, spawn);
    }

    public void removeCustom() {
        removeLast(q -> q.kind == Species.CUSTOM);
    }

    /** Случайная масса своей частицы: 0.1…10⁴ m_e, равномерно по логарифму. */
    public double randomMass() {
        return Math.pow(10, -1 + 5 * rnd.nextDouble());
    }

    /** Случайная частица выбранным способом: p, n, e, своя (случайная масса), атом или диполь. */
    public void addRandom() {
        switch (rnd.nextInt(6)) {
            case 0 -> addProton();
            case 1 -> addNeutron();
            case 2 -> addElectron();
            case 3 -> {
                double keep = customMass;
                customMass = randomMass();
                addCustom();
                customMass = keep;
            }
            case 4 -> addAtom();
            default -> addDipole();
        }
    }

    /** Случайная частица в заданной точке (правый клик «создать здесь»). */
    public void addRandomAt(double[] at) {
        switch (rnd.nextInt(6)) {
            case 0 -> addAt(Species.P, at);
            case 1 -> addAt(Species.N, at);
            case 2 -> addAt(Species.E, at);
            case 3 -> addAtMass(randomMass(), at);
            case 4 -> addAtomAt(at);
            default -> addDipoleAt(at);
        }
    }

    /** Своя частица заданной массы в точке. */
    private void addAtMass(double m, double[] at) {
        parts.add(new Body(Species.CUSTOM, m, inside(at.clone()), ZERO));
        changed();
    }

    /** Точка правого клика в пределах замкнутой области (если она включена) — не дальше 0.8·R. */
    private double[] inside(double[] p) {
        if (!chamber.on) return p;
        double d = dist(p, chamber.c);
        double lim = 0.8 * chamber.R();
        if (d <= lim || d == 0) return p;
        double k = lim / d;
        return new double[]{chamber.c[0] + (p[0] - chamber.c[0]) * k, chamber.c[1] + (p[1] - chamber.c[1]) * k,
                chamber.c[2] + (p[2] - chamber.c[2]) * k};
    }

    /** Поставить тело в заданную точку (правый клик «создать здесь»); скорость нулевая. */
    public void addAt(Species kind, double[] at) {
        double[] p = inside(at.clone());
        Body b = kind == Species.CUSTOM ? new Body(Species.CUSTOM, customMass, p, ZERO) : new Body(kind, -1, p, ZERO);
        parts.add(b);
        changed();
    }

    /** Атом выбранного вида в заданной точке. */
    public void addAtomAt(double[] at) {
        parts.addAll(makeAtom(nextAtom++, inside(at.clone())));
        changed();
    }

    /** Диполь в заданной точке: полюса на равновесном расстоянии, ориентация случайная. */
    public void addDipoleAt(double[] at) {
        double Np = dipoleNp, Nm = dipoleNm, L = Math.max(dipoleL, 3 * Formula.radOf(Math.max(Np, Nm)));
        double[] c = inside(at.clone()), d = randDir();
        Body a = new Body(Species.DP, Np, new double[]{c[0] + 0.5 * L * d[0], c[1] + 0.5 * L * d[1], c[2] + 0.5 * L * d[2]}, ZERO);
        Body b = new Body(Species.DM, Nm, new double[]{c[0] - 0.5 * L * d[0], c[1] - 0.5 * L * d[1], c[2] - 0.5 * L * d[2]}, ZERO);
        for (Body x : new Body[]{a, b}) {
            x.partner = x == a ? b : a;
            x.fIn = dipoleF;
            x.chi = dipoleChi;
            x.rs = Math.cbrt(x.chi) * L;
        }
        parts.add(a);
        parts.add(b);
        changed();
    }

    /**
     * Радиус облака набора: max |r − COM| + rad тела (для молекул/атомов — до внешнего e).
     * Нужен, чтобы «+» не ставил новое тело внутрь уже стоящей группы.
     */
    private static double cloudRadius(List<Body> bodies) {
        if (bodies.isEmpty()) return 0;
        Lump L = lump(bodies);
        double m = 0;
        for (Body b : bodies) {
            double d = dist(b.p, L.c()) + b.rad;
            if (d > m) m = d;
        }
        return m;
    }

    /**
     * Новая частица (или группа) — на расстояние rMin…rMax от центра масс всех уже имеющихся тел
     * (как одного тела) способом how: орбита, покой или бросок (см. launch). Первая частица в
     * пустой сцене остаётся в покое в центре. Если облако сцены уже больше rMax (крупная
     * молекула/атом), r поднимается до {@code cloud(scene)+cloud(moving)+gap} — иначе p/e
     * с r≈1…3 оказываются внутри и «орбита» ломается. Плотные наборы в малом rMax не трогаем.
     * С замкнутой областью: сначала {@link Chamber#ensureInner}, чтобы желаемый r и облако
     * умещались в 0.8·R (не сжимать молекулы в тесную сферу — иначе рвутся связи).
     */
    private void addPlaced(List<Body> moving, double rMin, double rMax, Spawn how) {
        double movingR = cloudRadius(moving);
        double gap = 2.0; // зазор между облаками, a₀
        if (!parts.isEmpty()) {
            double existing = cloudRadius(parts);
            // только если облако явно больше полосы rMax (молекула ≫ 1…3), не при плотной укладке
            if (existing > rMax + gap) {
                double need = existing + movingR + gap;
                double span = Math.max(0.5, rMax - rMin);
                rMin = need;
                rMax = need + span;
            }
            double r = rMin + rnd.nextDouble() * (rMax - rMin);
            if (chamber.on) {
                // место под центр на r и под радиус новой группы — иначе clamp 0.8R рвёт H₂
                chamber.ensureInner(Math.max(existing + gap, r + movingR + gap));
                r = Math.min(r, 0.8 * chamber.R());
            }
            launch(moving, new ArrayList<>(parts), r, how);
        } else if (chamber.on) {
            chamber.ensureInner(movingR + gap);
        }
        parts.addAll(moving);
        if (chamber.on) chamber.ensureInner(cloudRadius(parts) + gap);
        changed();
    }

    /**
     * Диполь — полюса d+ (N₊) и d− (N₋) на равновесном расстоянии L, ориентация случайная.
     * Ставится выбранным способом, как p и e (как одно тело с зарядом N₊ − N₋); орбита при
     * заряде ≤ 0 невозможна (отталкивание) — тогда в покое. В пустой сцене — в покое в центре. Держатся только силами pairQN: разорвать можно, лишь отдав энергию больше энергии
     * связи. L не меньше трёх радиусов большего полюса.
     */
    public void addDipole() {
        double Np = dipoleNp, Nm = dipoleNm, chi = dipoleChi;
        double L = Math.max(dipoleL, 3 * Formula.radOf(Math.max(Np, Nm)));
        double[] c = centerOfMass(), d = randDir();
        Body a = new Body(Species.DP, Np, new double[]{c[0] + 0.5 * L * d[0], c[1] + 0.5 * L * d[1], c[2] + 0.5 * L * d[2]}, ZERO);
        Body b = new Body(Species.DM, Nm, new double[]{c[0] - 0.5 * L * d[0], c[1] - 0.5 * L * d[1], c[2] - 0.5 * L * d[2]}, ZERO);
        for (Body x : new Body[]{a, b}) {
            x.fIn = dipoleF;
            x.chi = chi;
            x.rs = L * Math.cbrt(chi);
        }
        a.partner = b;
        b.partner = a;
        addPlaced(List.of(a, b), 1.5, 3, spawn == Spawn.ORBIT && Np <= Nm ? Spawn.REST : spawn);
    }

    /** Убирает последний диполь — оба полюса сразу. */
    public void removeDipole() {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Body q = parts.get(i);
            if (q.partner != null) {
                parts.remove(q.partner);
                parts.remove(q);
                changed();
                return;
            }
        }
    }

    public void removeElectron() {
        removeLast(q -> q.kind == Species.E && q.atom == -1);
    }

    /**
     * Убрать выбранное тело со сцены (ПКМ «Удалить»). Диполь — оба полюса сразу, как
     * {@link #removeDipole()}. Захват мышью сбрасывается; стабилизация/фокус чистятся в кадре App.
     */
    public void removeBody(Body b) {
        if (b == null || !parts.contains(b)) return;
        if (held == b || (b.partner != null && held == b.partner)) setHeld(null);
        if (b.partner != null) parts.remove(b.partner);
        parts.remove(b);
        changed();
    }

    public void reset() {
        changes++;
        nextNuc = 0;
        nextAtom = 1;
        simAcc = 0;
        time = 0;
        trailT = Double.NEGATIVE_INFINITY;
        held = null;
        stabilize = null;
        stabilizeCentral = null;
        stabilizeGood = 0;
        stabilizePower = 0;
        stabilizeThrust[0] = stabilizeThrust[1] = stabilizeThrust[2] = 0;
        clearPackNormal();
        tideEquatorN.clear();
        tideDiskR.clear();
        moleculeGroups.clear();
        // атомы (legacy) — один атом; иначе — текущий пресет «Сценария»
        parts = scene == Scene.ATOMS ? makeParts() : Scenarios.build(this);
        chamber.reset();
        e0Set = false;
        forces.markPropsDirty();
    }

    /**
     * Базовый пресет «Сценария»: {@link Scenarios.Preset#SANDBOX} — очистить;
     * Земля–Луна / СС / рандом / большая звезда / планета+луны / группа СС —
     * {@link #appendPreset} (можно несколько раз). «Мелкие фракции» — см. {@link #addFragments()}.
     */
    public void applyPreset(Scenarios.Preset p) {
        if (!Scenarios.isBase(p)) {
            addFragments();
            return;
        }
        if (Scenarios.clearsScene(p)) {
            preset = p;
            scene = Scene.SANDBOX;
            reset();
            return;
        }
        appendPreset(p);
    }

    /**
     * Добавить Земля–Луна / СС / рандом / большую звезду / планету+луны / группу СС
     * поверх текущих тел (без замены). Новый набор — рядом по +X от текущего COM
     * (если сцена не пуста). {@link Scenarios.Preset#SOLAR_GROUP} также ставит
     * {@link Forces.Device#GPU_FLOAT_BH}.
     */
    public void appendPreset(Scenarios.Preset p) {
        if (!Scenarios.isBase(p) || Scenarios.clearsScene(p)) {
            applyPreset(p);
            return;
        }
        preset = p;
        scene = Scene.SANDBOX;
        List<Body> neu = Scenarios.makeBase(this, p);
        double[] at = new double[3];
        if (!parts.isEmpty()) {
            Lump oldL = lump(parts);
            double gap = Scenarios.packRadius(parts) + Scenarios.packRadius(neu);
            gap += 0.25 * Math.max(gap, 1);
            at[0] = oldL.c()[0] + gap;
            at[1] = oldL.c()[1];
            at[2] = oldL.c()[2];
        }
        shiftPackTo(neu, at);
        parts.addAll(neu);
        if (Scenarios.enablesKuoAndGpuBh(p)) forces.device = Forces.Device.GPU_FLOAT_BH;
        e0Set = false;
        forces.markPropsDirty();
        changed();
    }

    /**
     * Добавить пресет (Земля–Луна / СС / рандом / большая звезда / планета+луны /
     * группа СС / мелкие фракции) так, чтобы COM набора был в {@code at}
     * (ПКМ «создать здесь» → сценарий). Песочница сюда не входит.
     * {@link Scenarios.Preset#SOLAR_GROUP} ставит {@link Forces.Device#GPU_FLOAT_BH}.
     */
    public void appendPresetAt(Scenarios.Preset p, double[] at) {
        if (p == null || at == null || Scenarios.clearsScene(p)) return;
        List<Body> neu;
        if (!Scenarios.isBase(p)) {
            impenetrable = true;
            neu = Scenarios.makeFragments(this);
        } else {
            preset = p;
            scene = Scene.SANDBOX;
            neu = Scenarios.makeBase(this, p);
        }
        shiftPackTo(neu, at);
        parts.addAll(neu);
        if (Scenarios.enablesKuoAndGpuBh(p)) forces.device = Forces.Device.GPU_FLOAT_BH;
        e0Set = false;
        forces.markPropsDirty();
        changed();
    }

    /** Сдвинуть набор так, чтобы его центр масс совпал с {@code at}. */
    static void shiftPackTo(List<Body> pack, double[] at) {
        if (pack == null || pack.isEmpty() || at == null) return;
        Lump L = lump(pack);
        for (Body b : pack) {
            for (int k = 0; k < 3; k++) b.p[k] += at[k] - L.c()[k];
        }
    }

    /**
     * Добавить «мелкие фракции» поверх текущей сцены (без замены). Включает непроницаемость
     * (пользователь может потом выключить). Базовый {@link #preset} не меняется.
     */
    public void addFragments() {
        impenetrable = true;
        parts.addAll(Scenarios.makeFragments(this));
        e0Set = false;
        changed();
    }

    /** Случайное число [0, 1) из ГПСЧ мира (для пресетов). */
    double nextU() {
        return rnd.nextDouble();
    }

    /** Смена сцены: у песочницы замкнутая область меньше (R₀ = 1.5), у атомов — 5. */
    public void setMode(Mode m) {
        mode = m;
        changed();
    }

    /** Все параметры модели — как при запуске: песочница, G = 1, комплексное тяготение, β = 0.5, замкнутая область по умолчанию и выключена. */
    public void restoreDefaults() {
        mode = Mode.KUO;
        atomType = AtomType.H2;
        moleculeType = MoleculeType.H2O;
        kuo.on = true;
        kuo.beta = 0.5;
        kuo.closed = false;
        kuo.nKuo = 1e6;
        kuo.link();
        customMass = 1;
        impenetrable = false;
        atomERepulsion = false;
        atomEK = 1;
        softRepulsion = false;
        overlapRepulsion = false;
        overlapK = 1;
        tidalEquator = false;
        clearPackNormal();
        tideEquatorN.clear();
        tideDiskR.clear();
        moonShare = Scenarios.MOON_SHARE_DEFAULT;
        moonsBeltsMode = Scenarios.MOONS_BELTS_DEFAULT;
        fragmentCount = Scenarios.FRAGMENT_COUNT_DEFAULT;
        preset = Scenarios.Preset.SANDBOX;
        spawn = Spawn.ORBIT;
        throwK = THROW_K;
        throwAim = THROW_AIM;
        dipoleNp = DIPOLE_NP;
        dipoleNm = DIPOLE_NM;
        dipoleL = DIPOLE_L;
        dipoleF = DIPOLE_F;
        dipoleChi = DIPOLE_CHI;
        chamber.defaults();
        forces.device = Forces.Device.CPU;
        setG(G_DEFAULT);
        setScene(Scene.SANDBOX);
    }

    public void setScene(Scene s) {
        scene = s;
        chamber.R0 = s == Scene.SANDBOX ? 8 : 5; // H₂/молекулы; addPlaced → Chamber.ensureInner при нехватке
        reset();
    }

    // ---------- динамика ----------

    /** W_i = Σ_j Q_j·N_j (вектор к источнику), F_i = f(α)·n_f·W_i, n_f = k_v·N_i, a_i = F_i/m_i. */
    public double[][] accelerate() {
        int n = parts.size();
        double[][] a = new double[n][3];
        if (mode == Mode.KUO) dyn = kuo.accelerate(a); // составляющие от КУО, итог — их сумма
        else dyn = forces.direct(a); // пары — в Forces: QN/r = kQ·N_j/max(r, R)³, как pairQN
        if (chamber.on) chamber.accelerate(a);
        contact(a);
        atomERepulsion(a);
        softRepulsion(a);
        overlapRepulsion(a);
        stabilizeAccel(a);
        tidalEquatorAccel(a);
        if (held != null) {
            int hi = parts.indexOf(held);
            if (hi >= 0) a[hi][0] = a[hi][1] = a[hi][2] = 0;
        }
        cacheAccel(a);
        return a;
    }

    private void cacheAccel(double[][] a) {
        int n = a.length;
        if (lastA == null || lastA.length != n) lastA = new double[n][3];
        for (int i = 0; i < n; i++) {
            lastA[i][0] = a[i][0];
            lastA[i][1] = a[i][1];
            lastA[i][2] = a[i][2];
        }
        accelFresh = true;
    }

    /**
     * Последние ускорения после {@link #accelerate()} (копия). null / не {@link #accelFresh()} —
     * resident GPU или ещё не считали; для рисования брать Δv/Δt каждый кадр.
     */
    public double[][] lastAccel() {
        return lastA;
    }

    /** Host a актуален для текущего кадра (сброс после resident GPU). */
    public boolean accelFresh() {
        return accelFresh && lastA != null;
    }

    /**
     * Тело, взятое мышью (Alt+ЛКМ): кинематика сцены, для сил — «призрак» (N=0 в {@link Forces#pack}).
     * null — никто не удерживается.
     */
    public Body held;

    public void setHeld(Body b) {
        if (held == b) return;
        if (b != null) clearStabilize(); // захват отменяет стабилизацию
        held = b;
        forces.markPropsDirty();
        changed();
    }

    /**
     * Тело под стабилизацией орбиты (ПКМ «стабилизировать»): непрерывный односторонний «крафт»
     * гасит радиальное v и подтягивает |v| к круговой вокруг {@link #stabilizeCentral}. null — нет.
     */
    public Body stabilize;
    /** Централ текущего кадра (Солнце / самый тяжёлый «рядом»); для отрисовки. */
    public Body stabilizeCentral;
    /** Направление ускорения стабилизации (не обязательно единичный) — луч крафта. */
    public final double[] stabilizeThrust = new double[3];
    /** 0…1 яркость луча (ошибка орбиты). */
    public double stabilizePower;
    private int stabilizeGood;

    /** Усиление демпфирования в долях орбитальной частоты ω = v_circ/r. */
    public static final double STAB_GAIN = 3;
    /** Опорный потолок |a| при m = m_e; тяжёлые — медленнее (∛m). */
    public static final double STAB_A0 = 120;
    /** Порог относительной ошибки (v_r, v_t − v_circ) для авто-стопа. */
    public static final double STAB_ERR = 0.04;
    /** Сколько шагов подряд ниже порога — считать орбиту круговой. */
    public static final int STAB_GOOD_N = 24;

    /** Вкл/выкл стабилизации для тела (тот же пункт меню). */
    public void toggleStabilize(Body b) {
        if (b == null) return;
        if (stabilize == b) clearStabilize();
        else setStabilize(b);
    }

    public void setStabilize(Body b) {
        if (b != null && held != null) setHeld(null);
        stabilize = b;
        stabilizeCentral = b == null ? null : pickCentral(b);
        stabilizeGood = 0;
        stabilizePower = 0;
        stabilizeThrust[0] = stabilizeThrust[1] = stabilizeThrust[2] = 0;
        changed();
    }

    public void clearStabilize() {
        if (stabilize == null && stabilizeCentral == null) return;
        stabilize = null;
        stabilizeCentral = null;
        stabilizeGood = 0;
        stabilizePower = 0;
        stabilizeThrust[0] = stabilizeThrust[1] = stabilizeThrust[2] = 0;
        changed();
    }

    /**
     * Централ: звезда сценария (Солнце), иначе тело с наибольшим m/r² к цели («самый тяжёлый рядом»).
     */
    public Body pickCentral(Body b) {
        Body star = null, best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Body o : parts) {
            if (o == b) continue;
            if (o.astro == AstroClass.STAR) star = o;
            double dx = o.p[0] - b.p[0], dy = o.p[1] - b.p[1], dz = o.p[2] - b.p[2];
            double score = o.m / (dx * dx + dy * dy + dz * dz + 1e-18);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return star != null ? star : best;
    }

    /** Можно ли стабилизировать: есть отличный от тела централ. */
    public boolean canStabilize(Body b) {
        return b != null && parts.contains(b) && pickCentral(b) != null;
    }

    /**
     * Непрерывная коррекция: гасит v_r, подтягивает касательную к v_circ(r), слегка гасит
     * нормаль к плоскости орбиты. Ускорение только у цели (крафт); |a| усечён по ∛m.
     * Авто-стоп при малой относительной ошибке несколько шагов подряд.
     */
    private void stabilizeAccel(double[][] a) {
        if (stabilize == null) return;
        if (!parts.contains(stabilize)) {
            clearStabilize();
            return;
        }
        Body b = stabilize;
        Body c = pickCentral(b);
        stabilizeCentral = c;
        if (c == null) {
            clearStabilize();
            return;
        }
        int bi = parts.indexOf(b);
        if (bi < 0) {
            clearStabilize();
            return;
        }
        double rx = b.p[0] - c.p[0], ry = b.p[1] - c.p[1], rz = b.p[2] - c.p[2];
        double r2 = rx * rx + ry * ry + rz * rz;
        if (r2 < 1e-18) {
            stabilizeThrust[0] = stabilizeThrust[1] = stabilizeThrust[2] = 0;
            stabilizePower = 0;
            return;
        }
        double r = Math.sqrt(r2);
        double ux = rx / r, uy = ry / r, uz = rz / r;
        double vx = b.v[0] - c.v[0], vy = b.v[1] - c.v[1], vz = b.v[2] - c.v[2];
        double vr = vx * ux + vy * uy + vz * uz;
        double hx = uy * vz - uz * vy, hy = uz * vx - ux * vz, hz = ux * vy - uy * vx;
        double h = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double tx, ty, tz, nx, ny, nz;
        if (h > 1e-12) {
            nx = hx / h;
            ny = hy / h;
            nz = hz / h;
            tx = ny * uz - nz * uy;
            ty = nz * ux - nx * uz;
            tz = nx * uy - ny * ux;
        } else {
            // почти радиальная скорость — запасная касательная
            if (Math.abs(ux) < 0.9) {
                tx = 0;
                ty = -uz;
                tz = uy;
            } else {
                tx = -uy;
                ty = ux;
                tz = 0;
            }
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            tx /= tl;
            ty /= tl;
            tz /= tl;
            nx = uy * tz - uz * ty;
            ny = uz * tx - ux * tz;
            nz = ux * ty - uy * tx;
        }
        double vt = vx * tx + vy * ty + vz * tz;
        double vn = vx * nx + vy * ny + vz * nz;
        double R = Math.max(b.rad, c.rad);
        double aRel = QN(r, R, 1) * (b.N * c.N / b.m + c.N * b.N / c.m);
        double vCirc = Math.sqrt(Math.abs(aRel) * r);
        double omega = vCirc > 1e-12 ? vCirc / r : 1;
        double ax = -STAB_GAIN * omega * vr * ux
                + STAB_GAIN * omega * (vCirc - vt) * tx
                - 0.5 * STAB_GAIN * omega * vn * nx;
        double ay = -STAB_GAIN * omega * vr * uy
                + STAB_GAIN * omega * (vCirc - vt) * ty
                - 0.5 * STAB_GAIN * omega * vn * ny;
        double az = -STAB_GAIN * omega * vr * uz
                + STAB_GAIN * omega * (vCirc - vt) * tz
                - 0.5 * STAB_GAIN * omega * vn * nz;
        double amag = Math.sqrt(ax * ax + ay * ay + az * az);
        double amax = STAB_A0 / Math.cbrt(Math.max(1, b.m / Formula.M_E));
        if (amag > amax && amag > 0) {
            double s = amax / amag;
            ax *= s;
            ay *= s;
            az *= s;
            amag = amax;
        }
        a[bi][0] += ax;
        a[bi][1] += ay;
        a[bi][2] += az;
        stabilizeThrust[0] = ax;
        stabilizeThrust[1] = ay;
        stabilizeThrust[2] = az;
        double vRef = Math.max(vCirc, 1e-9);
        double err = Math.hypot(vr / vRef, (vt - vCirc) / vRef);
        stabilizePower = Math.min(1, err / 0.25);
        if (err < STAB_ERR && Math.abs(vn) / vRef < STAB_ERR) {
            stabilizeGood++;
            if (stabilizeGood >= STAB_GOOD_N) clearStabilize();
        } else {
            stabilizeGood = 0;
        }
    }

    /**
     * Базовое время затухания v⊥ при R/a = 1 (модельные секунды). Эффективное
     * τ = TIDE_TAU / (Love · (R/a)^n). Подобрано так, что у лун a∼10…60 R при ×16…×64
     * наклон заметно падает за сессию, но не за секунду.
     */
    public static final double TIDE_TAU = 0.55;
    /** Степень (R_hub / a)^n — ближние быстрее; 1.25 мягче классических приливов ^(5–6). */
    public static final double TIDE_N = 1.25;
    /** Мягкий Love-подобный множитель (&lt; 1). */
    public static final double TIDE_LOVE = 0.65;
    /** Слабое кругование: доля того же rate на радиальной относительной скорости. */
    public static final double TIDE_ECC = 0.10;
    /**
     * Защитный потолок |a| в долях |v_rel|·rate (не ∛m как у stabilize — луны слишком тяжёлые,
     * электронный потолок убивал бы весь эффект).
     */
    public static final double TIDE_A_CAP = 4;

    /** Запомнить нормаль pack-плоскости пресета (единичный вектор). */
    public void setPackNormal(double nx, double ny, double nz) {
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-18) {
            clearPackNormal();
            return;
        }
        packNormal[0] = nx / l;
        packNormal[1] = ny / l;
        packNormal[2] = nz / l;
    }

    public void clearPackNormal() {
        packNormal[0] = packNormal[1] = packNormal[2] = 0;
    }

    public boolean hasPackNormal() {
        return packNormal[0] != 0 || packNormal[1] != 0 || packNormal[2] != 0;
    }

    /**
     * Option B: для каждого спутника гасит компоненту относительной скорости, нормальную к
     * экватору hub (орбитальная плоскость родителя / pack). Equal-opposite импульс на hub.
     * Ядро W=Q·N / КУО не трогает — только добавка к a после сил.
     */
    private void tidalEquatorAccel(double[][] a) {
        if (!tidalEquator) {
            tideEquatorN.clear();
            tideDiskR.clear();
            return;
        }
        int n = parts.size();
        if (n < 2) {
            tideEquatorN.clear();
            tideDiskR.clear();
            return;
        }

        IdentityHashMap<Body, Integer> index = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(parts.get(i), i);

        List<Body> bigHubs = tideBigHubs(n);
        IdentityHashMap<Body, double[]> equatorCache = new IdentityHashMap<>();
        IdentityHashMap<Body, double[]> hubDelta = new IdentityHashMap<>();
        rebuildTideOverlay(bigHubs, equatorCache);

        for (int i = 0; i < n; i++) {
            Body b = parts.get(i);
            if (b == held) continue;
            if (b.partner != null && b.kind == Species.DM) continue;
            Body hub = tideHubFor(b, bigHubs);
            if (hub == null || hub == b) continue;
            if (!isTideHubClass(hub)) continue;

            double[] nh = equatorOf(hub, equatorCache);
            if (nh == null) continue;

            double rx = b.p[0] - hub.p[0], ry = b.p[1] - hub.p[1], rz = b.p[2] - hub.p[2];
            double r2 = rx * rx + ry * ry + rz * rz;
            if (r2 < 1e-18) continue;
            double r = Math.sqrt(r2);
            double R = Math.max(hub.rad, 1e-9);
            double ra = Math.min(1.0, R / r);
            double rate = TIDE_LOVE * Math.pow(ra, TIDE_N) / TIDE_TAU;
            if (!(rate > 1e-14)) continue;

            double vx = b.v[0] - hub.v[0], vy = b.v[1] - hub.v[1], vz = b.v[2] - hub.v[2];
            double vn = vx * nh[0] + vy * nh[1] + vz * nh[2];
            double ux = rx / r, uy = ry / r, uz = rz / r;
            double vr = vx * ux + vy * uy + vz * uz;

            double ax = -rate * vn * nh[0] - TIDE_ECC * rate * vr * ux;
            double ay = -rate * vn * nh[1] - TIDE_ECC * rate * vr * uy;
            double az = -rate * vn * nh[2] - TIDE_ECC * rate * vr * uz;
            double amag = Math.sqrt(ax * ax + ay * ay + az * az);
            double vRel = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double amax = TIDE_A_CAP * Math.max(rate * vRel, 1e-12);
            if (amag > amax && amag > 0) {
                double s = amax / amag;
                ax *= s;
                ay *= s;
                az *= s;
            }
            a[i][0] += ax;
            a[i][1] += ay;
            a[i][2] += az;

            double[] dH = hubDelta.get(hub);
            if (dH == null) {
                dH = new double[3];
                hubDelta.put(hub, dH);
            }
            double mr = b.m / Math.max(hub.m, 1e-30);
            dH[0] -= mr * ax;
            dH[1] -= mr * ay;
            dH[2] -= mr * az;
        }

        for (Map.Entry<Body, double[]> e : hubDelta.entrySet()) {
            Integer hi = index.get(e.getKey());
            if (hi == null) continue;
            double[] d = e.getValue();
            a[hi][0] += d[0];
            a[hi][1] += d[1];
            a[hi][2] += d[2];
        }
    }

    /** Обновить кэш оверлея (пауза / только что включили чип, без шага сил). */
    public void refreshTideOverlay() {
        if (!tidalEquator || parts.size() < 2) {
            tideEquatorN.clear();
            tideDiskR.clear();
            return;
        }
        rebuildTideOverlay(tideBigHubs(parts.size()), new IdentityHashMap<>());
    }

    private List<Body> tideBigHubs(int n) {
        if (n <= StabilityTest.FULL_CENTRAL_SCAN) return null;
        List<Body> bigHubs = new ArrayList<>();
        for (Body o : parts) {
            if (o.astro == AstroClass.PLANET || o.astro == AstroClass.STAR) bigHubs.add(o);
        }
        return bigHubs;
    }

    private Body tideHubFor(Body b, List<Body> bigHubs) {
        return bigHubs == null
                ? StabilityTest.orbitCentral(this, b)
                : StabilityTest.bestHub(b, bigHubs);
    }

    private static boolean isTideHubClass(Body hub) {
        return hub.astro == AstroClass.PLANET || hub.astro == AstroClass.STAR
                || hub.astro == AstroClass.MOON;
    }

    private void rebuildTideOverlay(List<Body> bigHubs, IdentityHashMap<Body, double[]> equatorCache) {
        tideEquatorN.clear();
        tideDiskR.clear();
        if (bigHubs != null && bigHubs.isEmpty()) return;
        for (Body b : parts) {
            if (b.partner != null && b.kind == Species.DM) continue;
            Body hub = tideHubFor(b, bigHubs);
            if (hub == null || hub == b || !isTideHubClass(hub)) continue;
            double[] nh = equatorOf(hub, equatorCache);
            if (nh == null) continue;
            double rx = b.p[0] - hub.p[0], ry = b.p[1] - hub.p[1], rz = b.p[2] - hub.p[2];
            double r = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (!(r > 0)) continue;
            tideEquatorN.put(hub, nh);
            double disk = tideDiskR.containsKey(hub) ? tideDiskR.get(hub) : hub.rad * 4;
            tideDiskR.put(hub, Math.max(disk, r * 1.15));
        }
    }

    /**
     * Нормаль экватора hub: орбитальная плоскость вокруг более тяжёлого родителя,
     * иначе {@link #packNormal}, иначе средняя L спутников вокруг hub.
     */
    public double[] equatorOf(Body hub, IdentityHashMap<Body, double[]> cache) {
        double[] cached = cache.get(hub);
        if (cached != null) return cached;
        Body parent = StabilityTest.orbitCentral(this, hub);
        if (parent != null) {
            double rx = hub.p[0] - parent.p[0], ry = hub.p[1] - parent.p[1], rz = hub.p[2] - parent.p[2];
            double vx = hub.v[0] - parent.v[0], vy = hub.v[1] - parent.v[1], vz = hub.v[2] - parent.v[2];
            double[] L = cross(new double[]{rx, ry, rz}, new double[]{vx, vy, vz});
            double ll = Math.sqrt(L[0] * L[0] + L[1] * L[1] + L[2] * L[2]);
            if (ll > 1e-18) {
                double[] n = {L[0] / ll, L[1] / ll, L[2] / ll};
                cache.put(hub, n);
                return n;
            }
        }
        if (hasPackNormal()) {
            double[] n = {packNormal[0], packNormal[1], packNormal[2]};
            cache.put(hub, n);
            return n;
        }
        double sx = 0, sy = 0, sz = 0;
        for (Body b : parts) {
            if (b == hub || b.m >= hub.m) continue;
            if (b.partner != null && b.kind == Species.DM) continue;
            Body c = StabilityTest.orbitCentral(this, b);
            if (c != hub) continue;
            double rx = b.p[0] - hub.p[0], ry = b.p[1] - hub.p[1], rz = b.p[2] - hub.p[2];
            double vx = b.v[0] - hub.v[0], vy = b.v[1] - hub.v[1], vz = b.v[2] - hub.v[2];
            double[] L = cross(new double[]{rx, ry, rz}, new double[]{vx, vy, vz});
            sx += L[0];
            sy += L[1];
            sz += L[2];
        }
        double ll = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (ll < 1e-18) return null;
        double[] n = {sx / ll, sy / ll, sz / ll};
        cache.put(hub, n);
        return n;
    }

    public boolean impenetrable;
    /** Частота контактной пружины, рад/с модельного времени: период 2π/ω ≈ 0.21 с. */
    public static final double CONTACT_W = 30;

    /**
     * Непроницаемые тела: пробное перекрытие корректируется пружиной μ·ω²·s и демпфированием.
     * После полного шага resolveSurfaceContacts убирает остаточное проникновение и скорость
     * сближения по нормали. Контакт происходит на физических поверхностях; импульс пары
     * сохраняется, нормальная кинетическая энергия удара рассеивается.
     * Удерживаемое тело пропускается (призрак — клип через контакт).
     */
    private void contact(double[][] a) {
        if (!impenetrable) return;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            if (bi == held) continue;
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                if (bj == held) continue;
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double rs = bi.rad + bj.rad, r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= rs * rs || r2 == 0) continue;
                double r = Math.sqrt(r2), s = rs - r;
                double vr = ((bj.v[0] - bi.v[0]) * dx + (bj.v[1] - bi.v[1]) * dy + (bj.v[2] - bi.v[2]) * dz) / r;
                double acc = CONTACT_W * CONTACT_W * s - 2 * CONTACT_DAMP * vr; // сближение (vr < 0) — сильнее отталкивает
                double g = acc / (bi.m + bj.m);
                double ai = g * bj.m / r, aj = g * bi.m / r;
                a[i][0] -= ai * dx; a[i][1] -= ai * dy; a[i][2] -= ai * dz;
                a[j][0] += aj * dx; a[j][1] += aj * dy; a[j][2] += aj * dz;
            }
        }
    }

    /** Демпфирование пробного перекрытия, 1/с; итоговый нормальный контакт неупругий. */
    public static final double CONTACT_DAMP = 6;

    /** Потенциал пружинной коррекции перекрытия: ½μω²s². */
    private double contactEnergy() {
        if (!impenetrable) return 0;
        double U = 0;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double rs = bi.rad + bj.rad, r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= rs * rs) continue;
                double s = rs - Math.sqrt(r2), mu = bi.m * bj.m / (bi.m + bj.m);
                U += 0.5 * mu * CONTACT_W * CONTACT_W * s * s;
            }
        }
        return U;
    }

    /**
     * R3 (вкладка «Расчёт», по умолчанию выключено): отталкивание электронов разных атомов сильнее
     * в {@link #atomEK} раз. Текущий расчёт не меняется — добавка включается кнопкой. Сила как у
     * W = Q·N, но со знаком «от»; e–e одного атома не трогаются; импульс пары сохраняется.
     */
    public boolean atomERepulsion;
    /** Во сколько раз сильнее e–e разных атомов (0…10; 0 — выключено). */
    public double atomEK = 1;

    private void atomERepulsion(double[][] a) {
        if (!atomERepulsion || atomEK == 0) return;
        double kq = kQ() * atomEK;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            if (bi.kind != Species.E || bi.atom < 0) continue;
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                if (bj.kind != Species.E || bj.atom < 0 || bj.atom == bi.atom) continue;
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double r2 = dx * dx + dy * dy + dz * dz;
                if (r2 == 0) continue;
                double s = kq / (r2 * Math.sqrt(r2));
                a[i][0] -= s * bj.N * dx; a[i][1] -= s * bj.N * dy; a[i][2] -= s * bj.N * dz;
                a[j][0] += s * bi.N * dx; a[j][1] += s * bi.N * dy; a[j][2] += s * bi.N * dz;
            }
        }
    }

    private double atomERepulsionEnergy() {
        if (!atomERepulsion || atomEK == 0) return 0;
        double kq = kQ() * atomEK, U = 0;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            if (bi.kind != Species.E || bi.atom < 0) continue;
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                if (bj.kind != Species.E || bj.atom < 0 || bj.atom == bi.atom) continue;
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (r > 0) U += kq * bi.N * bj.N / r;
            }
        }
        return U;
    }

    /** R1: контакт физических поверхностей без увеличенного радиуса оболочки. */
    public boolean softRepulsion;
    /** Частота пружины для коррекции перекрытия, рад/с. */
    public static final double SOFT_W = 12;

    private void softRepulsion(double[][] a) {
        if (!softRepulsion) return;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double rs = bi.rad + bj.rad, r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= rs * rs || r2 == 0) continue;
                double r = Math.sqrt(r2), s = rs - r, g = SOFT_W * SOFT_W * s / (bi.m + bj.m);
                double ai = g * bj.m / r, aj = g * bi.m / r;
                a[i][0] -= ai * dx; a[i][1] -= ai * dy; a[i][2] -= ai * dz;
                a[j][0] += aj * dx; a[j][1] += aj * dy; a[j][2] += aj * dz;
            }
        }
    }

    private double softRepulsionEnergy() {
        if (!softRepulsion) return 0;
        double U = 0;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double rs = bi.rad + bj.rad, r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= rs * rs) continue;
                double s = rs - Math.sqrt(r2), mu = bi.m * bj.m / (bi.m + bj.m);
                U += 0.5 * mu * SOFT_W * SOFT_W * s * s;
            }
        }
        return U;
    }

    /** R2 (кнопка в «Расчёте», по умолчанию выключено): в зоне перекрытия шаров отталкивание сильнее W. */
    public boolean overlapRepulsion;
    /** Во сколько раз: +K·W наружу в зоне перекрытия (0…3). */
    public double overlapK = 1;

    private void overlapRepulsion(double[][] a) {
        if (!overlapRepulsion || overlapK == 0) return;
        double kq = kQ() * overlapK;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double R = Math.max(bi.rad, bj.rad), r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= R * R || r2 == 0) continue;
                double r = Math.sqrt(r2), s = kq / (R * R * R * r);
                a[i][0] -= s * bj.N * dx; a[i][1] -= s * bj.N * dy; a[i][2] -= s * bj.N * dz;
                a[j][0] += s * bi.N * dx; a[j][1] += s * bi.N * dy; a[j][2] += s * bi.N * dz;
            }
        }
    }

    private double overlapRepulsionEnergy() {
        if (!overlapRepulsion || overlapK == 0) return 0;
        double kq = kQ() * overlapK, U = 0;
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            Body bi = parts.get(i);
            for (int j = i + 1; j < n; j++) {
                Body bj = parts.get(j);
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double R = Math.max(bi.rad, bj.rad), r2 = dx * dx + dy * dy + dz * dz;
                if (r2 >= R * R) continue;
                U += kq * bi.N * bj.N * Formula.phi(Math.sqrt(r2), R);
            }
        }
        return U;
    }
    public double dynTime() {
        double t = forces.dyn();
        if (mode == Mode.KUO) t *= kuo.dynScale();
        if (chamber.on) t = Math.min(t, chamber.dynTime());
        return t;
    }

    /** Веса Йошиды: три симметричных подшага w1, w0, w1 дают схему 4-го порядка. */
    private static final double Y1 = 1 / (2 - Math.cbrt(2)), Y0 = 1 - 2 * Y1;

    /** Предел работы одного вызова advance(); недосчитанное время остаётся в simAcc. */
    private static final long BUDGET_NS = 30_000_000L;

    /** Шаг 4-го порядка (Йошида): ошибка O(dt⁴), симплектический, как и leapfrog. */
    public void step(double dt) {
        stepFrom(accelerate(), dt);
    }

    /** Inelastic normal contact at the actual surfaces. Tangential velocity remains free. */
    private boolean resolveSurfaceContacts() {
        if (!impenetrable && !softRepulsion) return false;
        boolean changed = false;
        for (int i = 0; i < parts.size(); i++) {
            Body bi = parts.get(i);
            if (bi == held) continue;
            for (int j = i + 1; j < parts.size(); j++) {
                Body bj = parts.get(j);
                if (bj == held) continue;
                double dx = bj.p[0] - bi.p[0], dy = bj.p[1] - bi.p[1], dz = bj.p[2] - bi.p[2];
                double surface = bi.rad + bj.rad, r2 = dx * dx + dy * dy + dz * dz;
                if (r2 > surface * surface) continue;
                double r = Math.sqrt(r2);
                double nx = r > 0 ? dx / r : 1, ny = r > 0 ? dy / r : 0, nz = r > 0 ? dz / r : 0;
                double wi = bj.m / (bi.m + bj.m), wj = bi.m / (bi.m + bj.m);
                double penetration = surface - r;
                if (penetration > 0) {
                    bi.p[0] -= nx * penetration * wi; bi.p[1] -= ny * penetration * wi; bi.p[2] -= nz * penetration * wi;
                    bj.p[0] += nx * penetration * wj; bj.p[1] += ny * penetration * wj; bj.p[2] += nz * penetration * wj;
                    changed = true;
                }
                double closing = (bj.v[0] - bi.v[0]) * nx + (bj.v[1] - bi.v[1]) * ny + (bj.v[2] - bi.v[2]) * nz;
                if (closing < 0) {
                    bi.v[0] += closing * nx * wi; bi.v[1] += closing * ny * wi; bi.v[2] += closing * nz * wi;
                    bj.v[0] -= closing * nx * wj; bj.v[1] -= closing * ny * wj; bj.v[2] -= closing * nz * wj;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** a — ускорения в текущих позициях; возвращает ускорения в конце шага. */
    private double[][] stepFrom(double[][] a, double dt) {
        a = substep(a, Y1 * dt);
        a = substep(a, Y0 * dt);
        a = substep(a, Y1 * dt);
        if (dt > 0 && resolveSurfaceContacts()) a = accelerate();
        time += dt;
        // точка следа — не чаще раза в TRAIL_DT модельного времени: при мелком шаге (жёсткий диполь,
        // тесное сближение) след иначе укладывался бы в доли секунды и был бы не виден
        if (time >= trailT + Formula.TRAIL_DT * (1 - 1e-9)) {
            sampleTrails(null, null, null);
            trailT = time;
        }
        return a;
    }

    /**
     * Кого писать в след. {@code null} — наследие тестов: первые {@link Formula#TRAIL_BODIES} и все e.
     * В UI — все тела ({@code b -> true}), без фильтра круговых орбит.
     */
    public java.util.function.Predicate<Body> trailSelect;

    /**
     * Точки следа. {@code null} trailSelect — legacy (первые TRAIL_BODIES + все e).
     * Иначе — все тела, для которых предикат истинен (в UI — все), без обрезки по массе.
     * {@code px/py/pz} — с видеокарты (индекс = номер в {@link #parts}); null — взять {@link Body#p}.
     */
    void sampleTrails(double[] px, double[] py, double[] pz) {
        if (trailSelect == null) {
            int k = Math.min(parts.size(), Formula.TRAIL_BODIES);
            for (int i = 0; i < k; i++) parts.get(i).trail.add(parts.get(i).p);
            for (int i = k; i < parts.size(); i++) {
                Body b = parts.get(i);
                if (b.kind == Species.E) b.trail.add(b.p);
            }
            return;
        }
        double[] p = new double[3];
        for (int i = 0; i < parts.size(); i++) {
            Body b = parts.get(i);
            if (!trailSelect.test(b)) {
                if (b.trail.size() > 0) b.trail.clear();
                continue;
            }
            if (px != null) {
                p[0] = px[i];
                p[1] = py[i];
                p[2] = pz[i];
                b.trail.add(p);
            } else {
                b.trail.add(b.p);
            }
        }
    }

    /**
     * Симметричный подшаг kick–damp–drift–damp–kick (палиндром — основа для схемы
     * Йошиды; вязкость замкнутой области по обе стороны drift). Ускорения a уже в текущих позициях.
     */
    private double[][] substep(double[][] a, double h) {
        kick(a, 0.5 * h);
        if (chamber.on) chamber.damp(0.5 * h);
        for (Body p : parts) {
            if (p == held) continue; // кинематика мыши — не drift
            for (int k = 0; k < 3; k++) p.p[k] += p.v[k] * h;
        }
        if (chamber.on) chamber.drift(h);
        if (chamber.on) chamber.damp(0.5 * h);
        a = accelerate();
        kick(a, 0.5 * h);
        return a;
    }

    private void kick(double[][] a, double h) {
        if (chamber.on) chamber.kick(h);
        for (int i = 0; i < parts.size(); i++) {
            Body p = parts.get(i);
            if (p == held) continue;
            for (int k = 0; k < 3; k++) p.v[k] += a[i][k] * h;
        }
    }

    /** Шаг из текущего состояния: dt = min(1/120, 0.02·dynTime), dynTime — из того же прохода, что и силы. */
    private double stepDt() {
        return Math.min(1.0 / 120, 0.02 * (chamber.on ? Math.min(dyn, chamber.dynTime()) : dyn));
    }

    /** Laboratory step without a wall-clock budget: reproducible model time. */
    void laboratoryStep(double remaining) {
        double[][] a = accelerate();
        double dt = Math.min(remaining, stepDt());
        if (!Double.isFinite(dt) || dt <= 1e-12) throw new IllegalStateException("Слишком малый шаг расчёта");
        stepFrom(a, dt);
    }

    /** The same initial settings and random stream for visible and isolated experiments. */
    public void prepareLaboratory(long seed) {
        restoreDefaults();
        rnd.setSeed(seed);
    }

    private static void alignNucleus(List<Body> group, double[] position) {
        double[] center = new double[3];
        double mass = 0;
        for (Body body : group) if (body.kind != Species.E) {
            mass += body.m;
            for (int k = 0; k < 3; k++) center[k] += body.m * body.p[k];
        }
        for (Body body : group) for (int k = 0; k < 3; k++) body.p[k] += position[k] - center[k] / mass;
    }

    public void prepareMoleculeLab(MoleculeLab.Setup config) {
        prepareLaboratory(config.seed());
        if (config instanceof MoleculeLab.Config template) {
            moleculeType = template.type();
            parts = new ArrayList<>(makeMolecule(new double[3], template.offsets()));
        } else if (config instanceof MoleculeLab.Assembly assembly) {
            parts = new ArrayList<>();
            int[] ids = new int[assembly.atoms().size()];
            double[][] plane = randomOrbitPlane();
            for (int i = 0; i < ids.length; i++) {
                MoleculeLab.Atom atom = assembly.atoms().get(i);
                ids[i] = nextAtom++;
                List<Body> group = makeAtom(atom.type(), ids[i], atom.position(), plane);
                alignNucleus(group, atom.position());
                // Internal orbital motion stays intact; the requested velocity is the atom's COM velocity.
                double[] internalVelocity = new double[3];
                double mass = 0;
                for (Body body : group) {
                    mass += body.m;
                    for (int k = 0; k < 3; k++) internalVelocity[k] += body.m * body.v[k];
                }
                double[] velocity = atom.velocity();
                for (Body body : group) for (int k = 0; k < 3; k++) body.v[k] += velocity[k] - internalVelocity[k] / mass;
                parts.addAll(group);
            }
            if (ids.length > 1) moleculeGroups.add(ids);
        }
        chamber.R0 = Math.max(8, 2.5 * Lab.maxBond(parts));
        chamber.setOn(true);
        changed();
    }

    /**
     * Продвинуть модель на realDt секунд. dt выбирается перед каждым шагом; время не
     * выбрасывается: что не успели за BUDGET_NS, считается в следующих вызовах (см. lag()).
     */
    public int advance(double realDt) {
        long deadline = System.nanoTime() + BUDGET_NS;
        simAcc += realDt;
        if (forces.resident() && !impenetrable && !softRepulsion && !overlapRepulsion && !atomERepulsion) {
            int r = advanceGpu(deadline);
            if (r >= 0) return r;
        }
        int n = 0;
        double[][] a = accelerate();
        while (System.nanoTime() < deadline) {
            double dt = stepDt();
            if (simAcc < dt) break;
            a = stepFrom(a, dt);
            simAcc -= dt;
            n++;
        }
        return n;
    }

    /**
     * Те же шаги, что в advance, но целиком на видеокарте («GPU float»): kick, drift, силы, dynTime,
     * замкнутая область (поле стенки, узлы, вязкий слой, затухание) и выбор шага не покидают её; на процессор приходит одно число за шаг (dynTime), координаты
     * первых тел для следа и в конце — координаты и скорости всех и состояние стенки. В КУО при связанных шкалах итог
     * пары — тот же W = Q·N (−β·W + (1+β)·W), поэтому видеокарта считает его прямо, а составляющие для
     * стрелок — раз в конце; при несвязанных шаг идёт по силам (см. Forces.resident). Ошибка видеокарты — состояние до вызова не тронуто, возвращает −1 (дальше CPU).
     */
    private int advanceGpu(long deadline) {
        double time0 = time, acc0 = simAcc, trail0 = trailT;
        try {
            dyn = forces.resBegin();
            int n = 0;
            while (System.nanoTime() < deadline) {
                double dt = stepDt();
                if (simAcc < dt) break;
                dyn = forces.resStep(dt, Y1, Y0);
                time += dt;
                if (time >= trailT + Formula.TRAIL_DT * (1 - 1e-9)) {
                    forces.resTrail(parts.size()); // sampleTrails: UI — все тела; null — legacy
                    trailT = time;
                }
                simAcc -= dt;
                n++;
            }
            forces.resEnd();
            forces.lastPath = Forces.RESIDENT;
            lastA = null; // host a больше не соответствует шагу на GPU
            accelFresh = false;
            return n;
        } catch (Throwable e) {
            time = time0;
            simAcc = acc0;
            trailT = trail0;
            forces.resFailed(e);
            return -1;
        }
    }

    /** Модельное время, которое ещё предстоит досчитать (отставание от реального). */
    public double lag() {
        return simAcc;
    }

    /**
     * Забыть недосчитанное модельное время (при смене скорости). Траектория не меняется — каждый
     * сделанный шаг остаётся тем же; модель лишь перестаёт догонять прежнюю заданную скорость.
     */
    public void dropLag() {
        simAcc = 0;
    }

    public record Energy(double E, double momentum) {}

    /** E = KE + U, U = −kQ·N_i·N_j·φ(r): потенциал той же силы, что в accelerate(). */
    public Energy energy() {
        double E = 0;
        double[] px = new double[3];
        for (Body p : parts) {
            E += 0.5 * p.m * (p.v[0] * p.v[0] + p.v[1] * p.v[1] + p.v[2] * p.v[2]);
            for (int k = 0; k < 3; k++) px[k] += p.m * p.v[k];
        }
        double U = forces.pairEnergy(); // Σ f(α)·k_v·kQ·N_i·N_j·pairPhi по парам — в Forces
        double g = mode == Mode.KUO ? kuo.netGain() : 1;
        if (g != 1) { // несвязанные шкалы КУО: пары не из одного диполя — ×g
            double up = forces.partnerEnergy();
            U = up + g * (U - up);
        }
        E -= U;
        E += contactEnergy(); // непроницаемые тела: ½μω²s² у перекрывающихся шаров
        E += atomERepulsionEnergy(); // R3: kQ·K·N_i·N_j/r у пар e–e разных атомов
        E += softRepulsionEnergy(); // R1: ½μω²s² мягких оболочек
        E += overlapRepulsionEnergy(); // R2: +K·kQ·N_iN_j·φ в зоне перекрытия
        if (chamber.on) {
            E += chamber.energy();
            double[] pc = chamber.momentum(); // стенка свободна: импульс тел + замкнутой области сохраняется
            for (int k = 0; k < 3; k++) px[k] += pc[k];
        }
        return new Energy(E, Math.sqrt(px[0] * px[0] + px[1] * px[1] + px[2] * px[2]));
    }

    /** ΔE/E от последнего изменения; с замкнутой областью — для E + потери (вязкость и затухание). */
    public double energyDrift(Energy en) {
        if (!e0Set) {
            e0 = en.E();
            e0Set = true;
            chamber.lost = 0;
        }
        double lost = chamber.on ? chamber.lost : 0;
        return e0 != 0 ? (en.E() + lost - e0) / Math.abs(e0) : 0;
    }

    /** Пара групп: расстояние центров масс и энергия их относительного движения. */
    public record Pair(double r, double E) {
        public boolean bound() {
            return E < 0;
        }
    }

    /**
     * Энергия группы a относительно группы b как двух тел: ½μ·v² − f(α)·k_v·kQ·N_a·N_b·φ(r, R),
     * тот же потенциал, что в energy(). Только измерение — на движение не влияет.
     */
    public Pair pair(List<Body> a, List<Body> b) {
        Lump la = lump(a), lb = lump(b);
        double r = dist(la.c(), lb.c()), v = dist(la.cv(), lb.cv());
        double mu = la.m() * lb.m() / (la.m() + lb.m());
        double U = -Formula.F_ALPHA * Formula.K_V * kQ() * la.N() * lb.N() * Formula.phi(r, Math.max(la.R(), lb.R()));
        if (mode == Mode.KUO) U *= kuo.netGain(); // итог КУО — g·W (в замкнутом — с поправкой на q)
        return new Pair(r, 0.5 * mu * v * v + U);
    }

    public record Counts(int p, int n, int e, int atoms, int pFree, int eFree, int dipoles, int custom, int molecules) {}

    public Counts counts() {
        int p = 0, n = 0, e = 0, pFree = 0, eFree = 0, custom = 0;
        for (Nucleon g : nucleons()) {
            if (g.kind() == Species.P) {
                p++;
                if (g.atom() == -1) pFree++;
            } else n++;
        }
        int d = 0;
        for (Body q : parts) {
            if (q.kind == Species.DP && q.partner != null) d++;
            if (q.kind == Species.CUSTOM) custom++;
            if (q.kind == Species.E) {
                e++;
                if (q.atom == -1) eFree++;
            }
        }
        return new Counts(p, n, e, atomIds().size(), pFree, eFree, d, custom, liveMolecules());
    }

    // ---------- векторы ----------

    double[] randDir() {
        double u = rnd.nextDouble(), v = rnd.nextDouble();
        double th = 2 * Math.PI * u, ph = Math.acos(2 * v - 1), s = Math.sin(ph);
        return new double[]{Math.cos(th) * s, Math.sin(th) * s, Math.cos(ph)};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (l == 0) l = 1;
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    public static double dist(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }
}
