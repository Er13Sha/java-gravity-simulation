package uif.ui;

import uif.Chamber;
import uif.Forces;
import uif.Kuo;
import uif.Lab;
import uif.Scenarios;
import uif.World;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Светлая полоса над сценой: темы с иконками «Сценарий», «Вещество/частица», «Тяготение», «Расчёт», «Вид»
 * и справа — пауза, «перезагрузить», сброс, скорость времени. Пресеты наполнения — во вкладке
 * «Сценарий» (radio-list + детали + «применить»); непроницаемость — там же. Тема «Вещество/частица» —
 * секции Элементарные / Атомы / Молекулы / Добавки модели / Как добавлять; стенка — в «Виде».
 * Клик по теме выдвигает под ней панель поверх сцены; повторный клик, клик по сцене или Esc закрывают.
 */
final class ControlPanel extends JPanel {
    private static final Map<World.Scene, List<String>> ROWS = Map.of(
            World.Scene.ATOMS, List.of("p", "n", "e", "a", "mol", "d", "m"),
            World.Scene.SANDBOX, List.of("p", "n", "e", "a", "mol", "d", "m"));

    private final World world;
    private final Camera cam;
    private final Settings s;

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final FlatButton honestBtn = new FlatButton("Честный"), mediumBtn = new FlatButton("Средняя", "G = 3·10⁻⁵: умеренное тяготение для наблюдения падения и контакта поверхностей"), oneBtn = new FlatButton("1");
    private final FlatButton pauseBtn = new FlatButton("", "пауза (Space)"), resetBtn = new FlatButton("", "сброс сцены (R)");
    private final FlatButton reloadBtn = new FlatButton("", "перезагрузить: все параметры как при запуске — сцена, атом, G = 1, тяготение, стенка, показ");
    private final FlatButton fieldBtn = new FlatButton("Поле", "поле частиц Q·N и подсветка воздействий на стенку замкнутой области (клавиша G)");
    private static final String CHAMBER_TIP = "замкнутая упругая оболочка (добавка модели): стенка с зарядом N отталкивает тела по Q·N и прогибается от их давления";
    private final FlatButton chamberOff = new FlatButton("Выкл", CHAMBER_TIP), chamberOn = new FlatButton("Вкл", CHAMBER_TIP);
    private final FlatButton trailBtn = new FlatButton("След",
            "следы у всех тел на сцене (клавиша T); точка раз в 1/120 с модельного времени, длина ~19 с");
    private final FlatButton orbitBtn = new FlatButton("Орбиты",
            "эллипсы связанных орбит: планеты, луны, астероиды, частицы и электроны атомов (вокруг более тяжёлого централа)");
    private final FlatButton labelsBtn = new FlatButton("Подписи",
            "подписи частиц у каждого тела: символ вида и масса (для сцен до " + ScenePanel.LABELS_MAX + " тел; на больших сценах подписей нет)");
    private final FlatButton randomBtn = new FlatButton("+ Рандом",
            "добавить случайные частицы (столько, сколько выбрано в «Сколько за нажатие»): протон, нейтрон, электрон, своя со случайной массой (0.1…10⁴ m_e), атом выбранного вида или диполь — каждый раз заново случайно");
    private final FlatButton impenBtn = new FlatButton("Контакт поверхностей: выкл",
            "контакт при расстоянии R_i+R_j: без зазора и проникновения; скорость сближения гасится, малое тело может остаться на большом. Выключено — тела проходят друг сквозь друга");
    /** Выбранный пункт списка «Сценарий» (у основных — совпадает с загруженным; у фракций — только UI). */
    private Scenarios.Preset selectedPreset = Scenarios.Preset.SANDBOX;
    private final Map<Scenarios.Preset, FlatButton> presetBtns = new LinkedHashMap<>();
    private final FlatButton applyBtn = new FlatButton("Применить",
            "добавить выбранное число астероидных тел поверх текущей сцены (не заменяя её)");
    private final JPanel applyRow = column();
    private final JLabel scenarioDetails = note("");
    private final JLabel replaceNote = note("");
    private JTextField moonShareField;
    private final JPanel moonShareRow = column();
    private final JPanel moonsBeltsRow = column();
    private final Map<Scenarios.MoonsBeltsMode, FlatButton> moonsBeltsBtns = new LinkedHashMap<>();
    private final JPanel fragmentCountRow = column();
    private final Map<Integer, FlatButton> fragmentCountBtns = new LinkedHashMap<>();
    private Runnable onScenarioApply;
    private static final String BODY_TIP = "как рисовать частицу: шар — тело с ядром и свечением; поле — тусклый ореол её поля Q·N (до 5% поля протона на r0) и яркая точка-ядро в центре; цвет по виду: протон оранжевый, нейтрон светло-серый, электрон голубой; «нагрев» меняет размер и яркость; яркость насыщается — облака рисуются при любом числе тел";
    private final FlatButton ballBtn = new FlatButton("Шар", BODY_TIP), cloudBtn = new FlatButton("Поле", BODY_TIP);
    private final FlatButton groundBtn = new FlatButton("3D-опоры", "пол-сетка, проекции частиц на пол и оси X/Y/Z — чтобы видеть глубину и наклон");
    private final FlatButton equatorBtn = new FlatButton("Экватор", "показать экваториальную линию на телах: при деформации — поперёк её оси; иначе — по орбитальной плоскости, при её отсутствии — XZ. Только отображение");
    private static final String EXTREME_TIP = "аналог сверхсильного поля (магнетары): стенка с зарядом N = 10⁹ давит внутрь в десятки тысяч раз сильнее, чем ядро держит электрон; только W = Q·N, не магнитное поле. «Выкл» возвращает прежние N, κ, τ";
    private final FlatButton extremeOff = new FlatButton("Выкл", EXTREME_TIP), extremeOn = new FlatButton("Вкл", EXTREME_TIP);
    private final FlatButton forcesBtn = new FlatButton("Силы",
            "стрелки у частиц: белая — итоговая сила; в комплексном тяготении ещё красная — частное отталкивание тел и голубая — приталкивание от КУО");
    private final FlatButton kuoViewBtn = new FlatButton("Отображение КУО",
            "только картинка: сфера комплекса прогибается к скоплениям массы (дефицит) и сжимается с q, пятна/конусы и полоса q; силы и физика не меняются");
    private final FlatButton polesSidesBtn = new FlatButton("Полюса как стороны",
            "только картинка: у каждого тела две стороны силы — красная отталкивание, голубая приталкивание от КУО (у диполей — те же стороны связи); без белого итога; физика не меняется");
    private final FlatButton tidalEquatorBtn = new FlatButton("Приливы к экватору",
            "добавка (не из W=Q·N): спутники гасят скорость перпендикулярно экватору родителя ~ (R/a)²; диск экватора и тонкие метки к плоскости; выкл — ни физики, ни картинки");
    private final FlatButton atomERepBtn = new FlatButton("Жёстче e–e",
            "добавка R3: отталкивание электронов разных атомов сильнее в K раз; внутри атома и текущий расчёт не меняются; по умолчанию выключено");
    private final JPanel atomEKBox = column();
    private final FlatButton overlapRepBtn = new FlatButton("В зоне overlap (R2)",
            "добавка R2: там, где шары перекрываются, добавляется отталкивание в K раз сильнее W — итог пары становится отталкивающим; вне перекрытия расчёт не меняется");
    private final JPanel overlapKBox = column();
    private final FlatButton shaderBtn = new FlatButton("Шейдеры", "тела и следы рисует видеокарта (OpenGL)");
    private static final String DEFORM_TIP = "визуальная деформация (физика — сферы): разница осей не более 8 %, плавное изменение формы; при взгляде вдоль оси тело круглое. Прилив — мягкая чувствительность без раннего насыщения";
    private final Map<Settings.DeformMode, FlatButton> deformBtns = new LinkedHashMap<>();
    private final FlatButton testBtn = new FlatButton("Тест устойчивости",
            "по каждому атому: связаны ли p–n и e (энергия пары < 0), r_e / r_pn, эксцентриситет; только наблюдение");
    private final Map<Double, FlatButton> speedBtns = new LinkedHashMap<>();
    /** Поле массы своей частицы (в массах электрона). */
    private JTextField customField;
    /** Радиус своей частицы при текущей массе — видно, что он растёт как ∛m. */
    private final JLabel customVal = note("");
    private final JComboBox<World.AtomType> atomBox = new JComboBox<>(World.AtomType.values());
    private final JComboBox<World.MoleculeType> moleculeBox = new JComboBox<>(World.MoleculeType.values());
    private final JComponent atomRowBox;
    private final JComponent moleculeRowBox;
    private final JComponent betaBox;
    private final FlatButton kuoOnBtn = new FlatButton("КУО",
            "вкл — комплексное: −β_т·W + приталкивание от КУО (итог W при связанных шкалах); выкл — только частное отталкивание −β_т·W, без голубого push — два тела разлетаются; по умолчанию вкл");
    private final JLabel kuoModeVal = note("");
    /** Ползунок β_т — нужен и при выкл КУО (сила отталкивания). */
    private final JComponent betaTRow;
    /** β_к, связь шкал, бесконечный/замкнутый — только при вкл КУО. */
    private final JComponent kuoPushBox;
    private final FlatButton linkBtn = new FlatButton("Связать: β_к = 1 + β_т",
            "как в монографии: приталкивание от КУО на единицу больше частного отталкивания — итог пары ровно W = Q·N");
    private final JLabel gainVal = note("");
    private static final String KUO_SCALE_TIP = "бесконечный — как в монографии: приталкивание от КУО (1 + β_т)·W; замкнутый — у комплекса конечный запас N_КУО, локальные массы его расходуют, и приталкивание ослабляется на q = ΣN/N_КУО: итог пары (β_к·(1 − q) − β_т)·W";
    private final FlatButton kuoInfBtn = new FlatButton("Бесконечный", KUO_SCALE_TIP), kuoClosedBtn = new FlatButton("Замкнутый", KUO_SCALE_TIP);
    private final JPanel kuoBox = column();
    private final JLabel kuoVal = note("");
    private final JPanel throwBox = column();
    /** Сколько частиц добавляет или убирает одно нажатие «+» / «−». */
    private int batch = 1;
    private final Map<Integer, FlatButton> batchBtns = new LinkedHashMap<>();
    private final Map<Forces.Device, FlatButton> deviceBtns = new LinkedHashMap<>();
    private final JLabel deviceVal = label("", Theme.UI_MUTED);
    private final Map<World.Spawn, FlatButton> spawnBtns = new LinkedHashMap<>();
    private final JPanel chBox = column();
    private final JLabel chOff = note("Включите замкнутую область, чтобы настроить стенку.");
    private final JLabel chVal = note("");

    /** Ползунки и их подписи-значения — обновляются в sync(). */
    private final List<Runnable> syncers = new ArrayList<>();

    private final List<JScrollPane> pages = new ArrayList<>();
    /** Карточки панелей (скруглённый низ, рамка) — их кладём в слой окна. */
    private final List<Card> cards = new ArrayList<>();
    private final FlatButton speedBtn = new FlatButton("×1", "скорость времени: клик — быстрее, правый клик — медленнее (все значения — в «Расчёте»)");
    private final FlatButton helpBtn = new FlatButton("Справочник F1", "разделы о модели, частицах и управлении (F1 или правый клик на сцене)");
    private final FlatButton labBtn = new FlatButton("Лаборатория", "опыты на модели: молекула в камере, контакт R1, падение на поверхность, электрон вокруг ядра — запуск и вердикт");
    private final List<Lab.Experiment> labExps = Lab.all();
    private int labIdx;
    private long labRevision;
    private final FlatButton labRunBtn = new FlatButton("запустить", "разложить опыт в сцене и получить вердикт по копии");
    private final JLabel labHint = new JLabel(" ");
    private final JLabel labVerdict = new JLabel(" ");
    private final javax.swing.JTextArea labDetails = new javax.swing.JTextArea(5, 30);
    private final JLabel shaderVal = new JLabel("");
    private final List<TabButton> tabs = new ArrayList<>();

    ControlPanel(World world, Camera cam, Settings s) {
        this.world = world;
        this.cam = cam;
        this.s = s;
        Chamber ch = world.chamber;
        // ---------- «Сценарий»: СС/Земля–Луна — append; Песочница — очистить; фракции — apply ----------
        JPanel scenario = column();
        scenario.add(section("Выбрать"));
        for (Scenarios.Preset p : Scenarios.Preset.values()) {
            String tip = p == Scenarios.Preset.FRAGMENTS
                    ? "добавка поверх текущей сцены: число тел и «применить»"
                    : Scenarios.clearsScene(p)
                    ? "очистить сцену (пустая песочница)"
                    : "добавить «" + p.title + "» поверх сцены (можно несколько раз)";
            FlatButton b = new FlatButton(p.title, tip);
            b.addActionListener(e -> selectScenario(p));
            presetBtns.put(p, b);
            scenario.add(chips(b));
        }
        scenario.add(section("Детали"));
        scenario.add(scenarioDetails);
        String moonsTip = "спутники/пояса до генерации СС / рандома / планеты со спутниками / группы СС: A — именные + плотный пояс; B — рой у гигантов (крошечные N); D — гибрид (по умолчанию). Без visual-колец · честный N-body";
        FlatButton[] mbb = new FlatButton[Scenarios.MoonsBeltsMode.values().length];
        int mi = 0;
        for (Scenarios.MoonsBeltsMode m : Scenarios.MoonsBeltsMode.values()) {
            FlatButton bt = new FlatButton(m.title, moonsTip);
            final Scenarios.MoonsBeltsMode mode = m;
            bt.addActionListener(e -> {
                world.moonsBeltsMode = mode;
                refreshScenarioDetails();
            });
            moonsBeltsBtns.put(m, bt);
            mbb[mi++] = bt;
        }
        moonsBeltsRow.add(labeled("Спутники/пояса", moonsTip, seg(mbb)));
        scenario.add(moonsBeltsRow);
        moonShareField = new JTextField(formatMoonShare(), 8);
        moonShareField.setFont(Theme.UI_MONO);
        moonShareField.setForeground(Theme.UI_TEXT);
        moonShareField.setCaretColor(Theme.UI_TEXT);
        moonShareField.setBackground(Theme.UI_CARD);
        moonShareField.setBorder(new Round(Theme.UI_BORDER));
        moonShareField.setToolTipText("доля массы Луны от Земли (по умолчанию 1/81.3 ≈ 0.0123); действует при следующем добавлении Земля–Луна / СС");
        moonShareField.addActionListener(e -> applyMoonShare());
        moonShareField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyMoonShare();
            }
        });
        moonShareRow.add(labeled("Доля массы Луны", moonShareField.getToolTipText(), moonShareField));
        scenario.add(moonShareRow);
        int[] fragCounts = {100, 500, 2000, 5000};
        FlatButton[] fcb = new FlatButton[fragCounts.length];
        String fragTip = "сколько астероидных тел добавить при «Применить»";
        for (int i = 0; i < fragCounts.length; i++) {
            final int n = fragCounts[i];
            fcb[i] = new FlatButton(String.valueOf(n), fragTip);
            fcb[i].addActionListener(e -> {
                world.fragmentCount = n;
                refreshScenarioDetails();
            });
            fragmentCountBtns.put(n, fcb[i]);
        }
        fragmentCountRow.add(labeled("Сколько тел", fragTip, seg(fcb)));
        scenario.add(fragmentCountRow);
        scenario.add(replaceNote);
        applyRow.add(chips(applyBtn));
        scenario.add(applyRow);
        applyBtn.addActionListener(e -> applyFragments());
        fragmentCountRow.setVisible(false);
        applyRow.setVisible(false);
        refreshScenarioDetails();

        // ---------- «Вещество/частица»: L1 — Элементарные / Атомы / Молекулы / Добавки / Как добавлять ----------
        JPanel parts = column(), how = column(), dip = column(), grav = column(), calc = column();

        atomBox.setFont(Theme.UI_BODY);
        atomBox.setBackground(Theme.UI_CARD);
        atomBox.setForeground(Theme.UI_TEXT);
        atomBox.setFocusable(false); // чтобы Space и стрелки не меняли выбор
        atomBox.setUI(new FlatCombo());
        atomBox.setBorder(new Round(Theme.UI_BORDER));
        atomBox.setMaximumRowCount(16); // полный список Z=1…118 — прокручиваемый
        atomBox.setToolTipText("какой атом добавлять кнопкой «+ атом» (вся таблица Менделеева, Z = 1…118); атом ставится рядом с телами на орбиту");
        atomBox.setSelectedItem(world.atomType);
        atomBox.addActionListener(e -> {
            World.AtomType t = (World.AtomType) atomBox.getSelectedItem();
            if (t == null || t == world.atomType) return;
            world.atomType = t;
            if (world.scene == World.Scene.ATOMS) world.reset();
        });
        JPanel atomRow = new JPanel(new BorderLayout(10, 0));
        atomRow.setOpaque(false);
        atomRow.add(label("Атом", Theme.UI_TEXT), BorderLayout.WEST);
        atomRow.add(atomBox, BorderLayout.CENTER);
        atomRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        atomRowBox = stretch(atomRow);

        moleculeBox.setFont(Theme.UI_BODY);
        moleculeBox.setBackground(Theme.UI_CARD);
        moleculeBox.setForeground(Theme.UI_TEXT);
        moleculeBox.setFocusable(false);
        moleculeBox.setUI(new FlatCombo());
        moleculeBox.setBorder(new Round(Theme.UI_BORDER));
        moleculeBox.setMaximumRowCount(12);
        moleculeBox.setToolTipText("какую молекулу добавлять кнопкой «+ молекула»: атомы состава (как «+ атом») в типичной геометрии — H₂ два H рядом, H₂O угол, CO₂ линейно…");
        moleculeBox.setSelectedItem(world.moleculeType);
        moleculeBox.addActionListener(e -> {
            World.MoleculeType t = (World.MoleculeType) moleculeBox.getSelectedItem();
            if (t == null || t == world.moleculeType) return;
            world.moleculeType = t; // только вид следующих «+ молекула» — сцену не сбрасывает
        });
        JPanel moleculeRow = new JPanel(new BorderLayout(10, 0));
        moleculeRow.setOpaque(false);
        moleculeRow.add(label("Молекула", Theme.UI_TEXT), BorderLayout.WEST);
        moleculeRow.add(moleculeBox, BorderLayout.CENTER);
        moleculeRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        moleculeRowBox = stretch(moleculeRow);

        reloadBtn.addActionListener(e -> {
            world.restoreDefaults();
            s.defaults();
            cam.defaults();
            cam.camR = Scenarios.camR(world);
            selectedPreset = world.preset;
            if (onScenarioApply != null) onScenarioApply.run();
        });
        parts.add(note("Сцена — песочница или пресет из «Сценария»: частицы добавляются кнопками «+»; «+ атом» / «+ молекула» — выбранный вид рядом с телами."));

        parts.add(section("Элементарные"));
        addRow(parts, "p", "Протон", "p", "+ свободный протон выбранным способом («Как добавлять»; первый — в покое в центре)\n− убирает последний свободный протон",
                world::removeProton, world::addProton);
        addRow(parts, "n", "Нейтрон", "n", "+ нейтрон: в атомах — на орбиту вокруг ядра (достраивает ядро), в песочнице — выбранным способом\n− убирает последний нейтрон",
                world::removeNeutron, world::addNeutron);
        addRow(parts, "e", "Электрон", "e", "+ свободный электрон выбранным способом («Как добавлять»; первый — в покое в центре)\n− убирает последний свободный электрон",
                world::removeElectron, world::addElectron);

        parts.add(section("Атомы"));
        parts.add(atomRowBox);
        addRow(parts, "a", "Атом", "целиком", "+ атом выбранного вида (список «Атом» в секции «Атомы» темы «Вещество/частица») выбранным способом\n− убирает последний атом целиком",
                world::removeAtom, world::addAtom);

        parts.add(section("Молекулы"));
        parts.add(moleculeRowBox);
        addRow(parts, "mol", "Молекула", "атомы", "+ молекула выбранного вида: атомы состава рядом в геометрии молекулы (не одно тело)\n− убирает последнюю молекулу целиком (все её атомы)",
                world::removeMolecule, world::addMolecule);
        parts.add(note("На сцене — отдельные атомы в геометрии, не одно тело"));

        parts.add(section("Добавки модели"));
        addRow(parts, "d", "Диполь", "d+ d−", "+ диполь: полюса d+ и d− на равновесном расстоянии, выбранным способом (добавка модели)\n− убирает последний диполь целиком",
                world::removeDipole, world::addDipole);
        dip.add(note("Для новых диполей — уже созданные не меняются."));
        dip.add(section("Заряды"));
        slider(dip, "Заряд d+, log₁₀ N", "N полюса d+; у электрона N = 1 (log = 0). Суммарный заряд диполя N₊ − N₋: больше нуля — диполь притягивается к телам целиком и кружит на орбите",
                -6, 0, 0.1, v -> world.dipoleNp = Math.pow(10, v), () -> Math.log10(world.dipoleNp), () -> Theme.exp(world.dipoleNp, 1), false);
        slider(dip, "Заряд d−, log₁₀ N", "N полюса d−; если он не меньше d+, суммарный заряд ≤ 0 и диполь целиком отталкивается от тел",
                -6, 0, 0.1, v -> world.dipoleNm = Math.pow(10, v), () -> Math.log10(world.dipoleNm), () -> Theme.exp(world.dipoleNm, 1)
                        + " (Σ " + Theme.exp(world.dipoleNp - world.dipoleNm, 1) + ")", false);
        dip.add(section("Форма и связь"));
        slider(dip, "Длина диполя L", "равновесное расстояние между полюсами: там отталкивание полюсов и прижатие от КУО равны",
                0.005, 0.2, 0.005, v -> world.dipoleL = v, () -> world.dipoleL, () -> Theme.fixed(world.dipoleL, 3), false);
        slider(dip, "Сила экранирования χ", "во сколько раз прижатие от КУО вдали сильнее отталкивания полюсов; радиус экранирования Rₛ = L·χ^(1/3)",
                1.5, 64, 0.5, v -> world.dipoleChi = v, () -> world.dipoleChi, () -> Theme.fixed(world.dipoleChi, 1), false);
        slider(dip, "F(α) внутри диполя, log₁₀", "коэффициент f(α) формулы F = f(α)·n_f·W для пары полюсов одного диполя: чем больше, тем жёстче связь и больше энергия связи",
                0, 9, 0.1, v -> world.dipoleF = Math.pow(10, v), () -> Math.log10(world.dipoleF), () -> Theme.exp(world.dipoleF, 1), false);
        FlatButton dipToggle = new FlatButton("Параметры диполя — развернуть",
                "заряды d+ и d−, длина L, экранирование χ и f(α) — для новых диполей; развернуть или свернуть");
        dip.setVisible(false);
        dipToggle.addActionListener(e -> {
            boolean v = !dip.isVisible();
            dip.setVisible(v);
            dipToggle.setText(v ? "Параметры диполя — свернуть" : "Параметры диполя — развернуть");
            for (JScrollPane p : pages) p.revalidate();
        });
        parts.add(stretch(dipToggle));
        parts.add(dip);
        customField = new JTextField(formatCustom(), 6);
        customField.setFont(Theme.UI_MONO);
        customField.setForeground(Theme.UI_TEXT);
        customField.setCaretColor(Theme.UI_TEXT);
        customField.setBackground(Theme.UI_CARD);
        customField.setBorder(new Round(Theme.UI_BORDER));
        customField.setToolTipText("масса новой своей частицы в массах электрона: 1 — как электрон, 1836 — как протон; N = m, радиус из N = V·q (∛m). Enter — применить");
        customField.addActionListener(e -> applyCustom());
        customField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyCustom();
            }
        });
        // «+» сначала забирает массу из поля — иначе частица добавится с прежней
        addRow(parts, "m", "Своя", "m", "+ своя частица с массой, заданной ниже (в массах электрона), выбранным способом; радиус — из N = V·q, поэтому ∝ ∛m\n− убирает последнюю свою частицу",
                world::removeCustom, () -> {
                    applyCustom();
                    world.addCustom();
                });
        parts.add(labeled("Масса своей, m_e", customField.getToolTipText(), customField));
        parts.add(customVal);
        parts.add(chips(randomBtn));
        randomBtn.addActionListener(e -> repeat(world::addRandom));

        String spawnTip = "как кнопки «+» добавляют частицы (p, n, e, атом, молекула, диполь): орбита — на круговую орбиту вокруг всех тел; покой — неподвижно рядом с телами, дальше только силы; бросок — летит к центру масс тел с заданной скоростью. Суммарный импульс не меняется";
        FlatButton[] spb = new FlatButton[World.Spawn.values().length];
        for (World.Spawn sp : World.Spawn.values()) {
            FlatButton bt = new FlatButton(sp.title, spawnTip);
            bt.addActionListener(e -> world.spawn = sp);
            spawnBtns.put(sp, bt);
            spb[sp.ordinal()] = bt;
        }
        how.add(labeled("Способ", spawnTip, seg(spb)));
        String batchTip = "сколько частиц добавляет «+» или убирает «−» за одно нажатие; каждая ставится выбранным способом, как по одной";
        int[] batches = {1, 10, 100};
        FlatButton[] bb = new FlatButton[batches.length];
        for (int i = 0; i < batches.length; i++) {
            final int v = batches[i];
            bb[i] = new FlatButton("По " + v, batchTip);
            bb[i].addActionListener(e -> batch = v);
            batchBtns.put(v, bb[i]);
        }
        how.add(labeled("Сколько за нажатие", batchTip, seg(bb)));
        throwBox.add(section("Бросок"));
        slider(throwBox, "Скорость, \u00d7 круговой", "скорость относительно тел в долях круговой скорости на этом расстоянии: 1 — как на орбите, \u221a2 \u2248 1.41 — ровно хватает улететь, 0 — в покое",
                0, 3, 0.05, v -> world.throwK = v, () -> world.throwK, () -> Theme.fixed(world.throwK, 2), false);
        slider(throwBox, "Прицел", "0 — прямо в центр масс тел (столкновение), 1 — по касательной (почти орбита); между — пролёт мимо",
                0, 1, 0.05, v -> world.throwAim = v, () -> world.throwAim, () -> Theme.fixed(world.throwAim, 2), false);
        how.add(throwBox);
        parts.add(section("Как добавлять"));
        parts.add(how);
        // ---------- «Тяготение» — чип КУО вкл/выкл (по умолчанию вкл = комплексное) ----------
        world.setMode(World.Mode.KUO);
        world.kuo.on = true;
        JPanel beta = column();
        beta.add(section("Тяготение"));
        beta.add(chips(kuoOnBtn));
        beta.add(kuoModeVal);
        kuoOnBtn.addActionListener(e -> {
            world.kuo.on = !world.kuo.on;
            world.setMode(World.Mode.KUO);
            world.changed();
        });
        JPanel betaT = column();
        betaT.add(section("Частное отталкивание"));
        slider(betaT, "β_т, log₁₀",
                "β_т — тела отталкивают друг друга: −β_т·W; крайнее левое — 0, справа до 10¹⁵. При выкл КУО это единственная сила пары (разлёт). При вкл и связанных шкалах КУО: β_к = 1 + β_т",
                -3, 15, 0.1, v -> world.kuo.beta = logBeta(v), () -> sliderLog(world.kuo.beta), () -> Theme.beta(world.kuo.beta), true);
        betaTRow = betaT;
        beta.add(betaTRow);
        JPanel push = column();
        push.add(section("Приталкивание от КУО"));
        slider(push, "Отталкивание КУО β_к, log₁₀",
                "β_к — комплекс удалённых объектов отталкивает тело, а дефицит массы КУО за соседом приталкивает: +β_к·W; крайнее левое — 0, справа до 10¹⁵. Сдвиг ползунка развязывает шкалы",
                -3, 15, 0.1, v -> world.kuo.setBetaK(logBeta(v)), () -> sliderLog(world.kuo.betaK()), () -> Theme.beta(world.kuo.betaK()), true);
        linkBtn.addActionListener(e -> {
            world.kuo.link();
            world.changed();
        });
        push.add(chips(linkBtn));
        push.add(gainVal);
        push.add(section("Комплекс удалённых объектов"));
        push.add(labeled("Масштаб", KUO_SCALE_TIP, seg(kuoInfBtn, kuoClosedBtn)));
        kuoInfBtn.addActionListener(e -> {
            world.kuo.closed = false;
            world.changed();
        });
        kuoClosedBtn.addActionListener(e -> {
            world.kuo.closed = true;
            world.changed();
        });
        slider(kuoBox, "Запас N_КУО, log₁₀",
                "запас комплекса удалённых объектов в единицах N: чем меньше, тем сильнее локальные массы его расходуют. При q = ΣN/N_КУО = 1 − β_т/β_к итог пары обращается в ноль — равновесие, дальше тела расталкиваются",
                2, 12, 0.1, v -> {
                    world.kuo.nKuo = Math.pow(10, v);
                    world.changed();
                }, () -> Math.log10(world.kuo.nKuo), () -> Theme.exp(world.kuo.nKuo, 1), false);
        kuoBox.add(kuoVal);
        push.add(kuoBox);
        kuoPushBox = push;
        beta.add(kuoPushBox);
        betaBox = beta;
        grav.add(betaBox);
        grav.add(section("Постоянная G"));
        grav.add(labeled("Быстро", "сила тяготения: «Честный» — настоящая G (движения не видно), «Средняя» — G = 1e-3 (оболочки сталкиваются полями), «1» — G = 1 (старт и перезагрузка)", seg(honestBtn, mediumBtn, oneBtn)));
        honestBtn.addActionListener(e -> world.setG(World.G_HONEST));
        mediumBtn.addActionListener(e -> world.setG(World.G_MEDIUM));
        oneBtn.addActionListener(e -> world.setG(World.G_DEFAULT));
        slider(grav, "Log₁₀ G", "сила тяготения: «Честный» — настоящая G (движения не видно); по умолчанию G = 1; верх ≈ "
                        + Theme.exp(Math.pow(10, World.G_LOG_MAX), 2),
                World.G_LOG_MIN, World.G_LOG_MAX, 0.01,
                v -> world.setG(Math.pow(10, v)), () -> Math.log10(world.G), () -> Theme.exp(world.G, 2), false);

        // ---------- «Стенка» — в теме «Вид» ----------
        JPanel wall = column();
        wall.add(labeled("Замкнутая область", CHAMBER_TIP, seg(chamberOff, chamberOn)));
        chamberOff.addActionListener(e -> ch.setOn(false));
        chamberOn.addActionListener(e -> ch.setOn(true));
        wall.add(labeled("Экстремальная", EXTREME_TIP, seg(extremeOff, extremeOn)));
        extremeOff.addActionListener(e -> {
            if (ch.isExtreme()) ch.setExtreme(false);
        });
        extremeOn.addActionListener(e -> {
            if (!ch.isExtreme()) ch.setExtreme(true);
        });
        wall.add(chOff);
        chamberSlider("R₀ — собственный размер", "размер оболочки без давлений; радиус покоя (пунктир) сдвигается от давления тел изнутри и внешнего давления снаружи",
                0.3, 10, 0.1, v -> ch.R0 = v, () -> ch.R0, () -> Theme.fixed(ch.R0, 1));
        chamberSlider("N стенки, log₁₀", "заряд стенки: чем больше, тем сильнее она давит внутрь; от 10⁵ давление на электрон сильнее притяжения ядра; выше 10¹⁰ расчёт не успевает за реальным временем",
                1, 10, 0.1, v -> ch.N = Math.pow(10, v), () -> Math.log10(ch.N), () -> Theme.exp(ch.N, 1));
        chamberSlider("Упругость κ, log₁₀", "жёсткость оболочки, 1/с²; от G не зависит: при любом G стенка за секунды приходит к радиусу покоя",
                -2, 3, 0.1, v -> ch.kappa = Math.pow(10, v), () -> Math.log10(ch.kappa), () -> Theme.exp(ch.kappa, 1));
        chamberSlider("Внешнее давление, log₁₀", "давление снаружи сжимает оболочку; крайнее левое положение — нет давления",
                -2, 3, 0.1, v -> ch.pExt = v <= -1.95 ? 0 : Math.pow(10, v), () -> ch.pExt > 0 ? Math.log10(ch.pExt) : -2, () -> ch.pExt > 0 ? Theme.exp(ch.pExt, 1) : "нет");
        chamberSlider("Натяжение τ, log₁₀", "связь соседних узлов: мало — стенка мнётся локально, много — только плавные формы",
                -2, 3, 0.1, v -> ch.tau = Math.pow(10, v), () -> Math.log10(ch.tau), () -> Theme.exp(ch.tau, 1));
        chBox.add(section("Вязкость"));
        chamberSlider("Вязкий слой h / R", "слой у стенки, где тело тормозится; доля радиуса",
                0.02, 0.5, 0.01, v -> ch.hFrac = v, () -> ch.hFrac, () -> Theme.fixed(ch.hFrac, 2));
        chamberSlider("Вязкость, log₁₀", "чем больше, тем сильнее торможение в слое",
                0, 3, 0.1, v -> ch.visc = Math.pow(10, v), () -> Math.log10(ch.visc), () -> Theme.fixed(ch.visc, 0));
        chamberSlider("Затухание ζ", "гасит колебания узлов; 0 — без затухания",
                0, 2, 0.05, v -> ch.zeta = v, () -> ch.zeta, () -> Theme.fixed(ch.zeta, 2));
        chBox.add(chVal);
        wall.add(chBox);

        // ---------- «Расчёт» ----------
        String speedTip = "честное ускорение: шаг расчёта тот же, просто за секунду считается больше шагов — траектория та же, что при ×1; если компьютер не успевает, фактическая скорость (в сводке) меньше заданной";
        double[] speeds = {0.125, 0.25, 0.5, 1, 4, 16, 64, 256, 1024};
        FlatButton[] sb = new FlatButton[speeds.length];
        for (int i = 0; i < speeds.length; i++) {
            final double v = speeds[i];
            sb[i] = new FlatButton("×" + App.speedText(v), speedTip);
            sb[i].addActionListener(e -> s.speed = v);
            speedBtns.put(v, sb[i]);
        }
        calc.add(labeled("Скорость времени", speedTip, seg(sb)));
        String devTip = "где считать силы. авто/CPU/GPU double — до бита; GPU float — точные пары во float; GPU float Barnes–Hut — O(N log N) приближённо (быстрее на больших N)";
        Map<Forces.Device, String> devTips = Map.of(
                Forces.Device.AUTO, "1 поток до " + Forces.PAR_MIN + " тел, все ядра процессора до " + Forces.GPU_MIN + " тел, дальше видеокарта в double. Результат до бита тот же, что на CPU",
                Forces.Device.CPU, "только процессор (до " + Forces.PAR_MIN + " тел — 1 поток, дальше все ядра)",
                Forces.Device.GPU, "видеокарта в double при любом числе тел; результат до бита тот же, что на CPU",
                Forces.Device.GPU_FLOAT, "видеокарта во float: точный перебор пар, ~7 знаков; энергия для ΔE/E в double",
                Forces.Device.GPU_FLOAT_BH, "Barnes–Hut во float (θ≈0.55): топология редко на хосте, COM мультиполей — refresh на GPU; грубее GPU float, зато O(N log N). Энергия — точно в double. Обычный GPU float не меняется");
        Forces.Device[] devs = Forces.Device.values();
        FlatButton[] db = new FlatButton[devs.length];
        for (int i = 0; i < devs.length; i++) {
            final Forces.Device d = devs[i];
            db[i] = new FlatButton(d.label, devTips.get(d));
            db[i].addActionListener(e -> world.forces.device = d);
            deviceBtns.put(d, db[i]);
        }
        // чипы с переносом (не Seg в один ряд) — «GPU float Barnes–Hut» не сжимается
        calc.add(labeled("Считать на", devTip, chips(db)));
        deviceVal.setFont(Theme.UI_SMALL);
        deviceVal.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        deviceVal.setAlignmentX(Component.LEFT_ALIGNMENT); // без stretch: высота растёт с текстом
        calc.add(deviceVal);
        calc.add(section("Рисование"));
        calc.add(chips(shaderBtn));
        shaderVal.setFont(Theme.UI_SMALL);
        shaderVal.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        shaderVal.setAlignmentX(Component.LEFT_ALIGNMENT);
        calc.add(shaderVal);
        shaderBtn.addActionListener(e -> s.shaders = !s.shaders);
        FlatButton[] dfb = new FlatButton[Settings.DeformMode.values().length];
        int di = 0;
        for (Settings.DeformMode m : Settings.DeformMode.values()) {
            FlatButton bt = new FlatButton(m.title, DEFORM_TIP);
            final Settings.DeformMode mode = m;
            bt.addActionListener(e -> s.deform = mode);
            deformBtns.put(m, bt);
            dfb[di++] = bt;
        }
        calc.add(labeled("Деформация", DEFORM_TIP, seg(dfb)));
        calc.add(chips(tidalEquatorBtn));
        tidalEquatorBtn.addActionListener(e -> {
            world.tidalEquator = !world.tidalEquator;
            if (world.tidalEquator) world.refreshTideOverlay();
            else {
                world.tideEquatorN.clear();
                world.tideDiskR.clear();
            }
        });
        calc.add(section("Отталкивание оболочек"));
        calc.add(chips(impenBtn));
        calc.add(note("Режим действует на все тела текущей сцены: контакт при R₁ + R₂, малое тело остаётся на поверхности большого. «Мелкие фракции» включают этот режим автоматически."));
        impenBtn.addActionListener(e -> {
            boolean enabled = !(world.impenetrable || world.softRepulsion);
            world.impenetrable = enabled;
            world.softRepulsion = false;
            world.changed();
            syncSurfaceContact();
        });
        syncSurfaceContact();
        calc.add(chips(atomERepBtn));
        atomERepBtn.addActionListener(e -> world.atomERepulsion = !world.atomERepulsion);
        slider(atomEKBox, "K", "во сколько раз сильнее отталкивание e–e разных атомов (0…10); 1 — как обычная пара",
                0, 10, 0.1, v -> world.atomEK = v, () -> world.atomEK, () -> Theme.fixed(world.atomEK, 1), true);
        calc.add(atomEKBox);
        calc.add(chips(overlapRepBtn));
        overlapRepBtn.addActionListener(e -> world.overlapRepulsion = !world.overlapRepulsion);
        slider(overlapKBox, "K (R2)", "во сколько раз сильнее отталкивание в зоне перекрытия шаров (0…3)",
                0, 3, 0.05, v -> world.overlapK = v, () -> world.overlapK, () -> Theme.fixed(world.overlapK, 2), true);
        calc.add(overlapKBox);

        // ---------- «Лаборатория» ----------
        JPanel lab = column();

        // ---------- «Вид» ----------
        JPanel view = column();
        view.add(section("Показывать"));
        view.add(chips(fieldBtn, trailBtn, orbitBtn, groundBtn, equatorBtn, forcesBtn, kuoViewBtn, polesSidesBtn, labelsBtn));
        view.add(labeled("Частицы", BODY_TIP, seg(ballBtn, cloudBtn)));
        ballBtn.addActionListener(e -> s.bodyField = false);
        cloudBtn.addActionListener(e -> s.bodyField = true);
        view.add(section("Стенка"));
        view.add(wall);        view.add(section("Инструменты"));
        view.add(chips(testBtn, helpBtn));
        fieldBtn.addActionListener(e -> s.showField = !s.showField);
        trailBtn.addActionListener(e -> s.showTrail = !s.showTrail);
        orbitBtn.addActionListener(e -> {
            if (s.orbitsReady) s.showOrbits = !s.showOrbits;
        });
        groundBtn.addActionListener(e -> s.showGround = !s.showGround);
        equatorBtn.addActionListener(e -> s.showEquator = !s.showEquator);
        forcesBtn.addActionListener(e -> s.showForces = !s.showForces);
        kuoViewBtn.addActionListener(e -> s.showKuo = !s.showKuo);
        polesSidesBtn.addActionListener(e -> s.showPolesSides = !s.showPolesSides);
        labelsBtn.addActionListener(e -> s.showLabels = !s.showLabels);
        testBtn.addActionListener(e -> s.testOpen = !s.testOpen);
        helpBtn.addActionListener(e -> {
            close();
            if (onHelp != null) onHelp.run();
        });
        view.add(section("Управление"));
        view.add(note("Мышь: зажать и вести — поворот взгляда, клик по телу — плавно к его центру, Alt+ЛКМ — взять тело, ПКМ по телу — стабилизировать / Удалить, колесо — масштаб · W — вперёд (по взгляду), Shift — ускорение, Q/E — вверх/вниз · Space — пауза · R — сброс · G — поле · T — след · Esc — закрыть панель · правый клик — прицел «создать здесь» или справочник · F1 — справочник · «Орбиты» — эллипсы связанных орбит (СС, Земля–Луна, атомы)"));

        // ---------- «Лаборатория»: опыты на живой сцене ----------
        lab.add(section("Сборка и испытание"));
        FlatButton moleculeLab = new FlatButton("Конструктор молекул", "шаблоны H₂/H₂O, свободная 3D-сборка, воспроизводимый опыт и графики");
        moleculeLab.addActionListener(ev -> {
            javax.swing.JDialog dialog = new javax.swing.JDialog(SwingUtilities.getWindowAncestor(this), "Лаборатория молекул");
            MoleculeLabPanel panel = new MoleculeLabPanel(world, cam, s);
            dialog.setContentPane(panel);
            dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) { panel.cancel(); }
            });
            dialog.setSize(1180, 900);
            dialog.setMinimumSize(new Dimension(1040, 820));
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
        lab.add(chips(moleculeLab));
        lab.add(section("Опыты"));
        FlatButton[] labExpBtns = new FlatButton[labExps.size()];
        for (int i = 0; i < labExps.size(); i++) {
            final int idx = i;
            Lab.Experiment e = labExps.get(i);
            FlatButton b = new FlatButton(e.title(), e.hint());
            b.addActionListener(ev -> {
                labIdx = idx;
                syncLab();
            });
            labExpBtns[i] = b;
        }
        lab.add(chips(labExpBtns));
        lab.add(section("Запуск"));
        lab.add(chips(labRunBtn));
        lab.add(labHint);
        lab.add(labVerdict);
        JScrollPane labScroll = new JScrollPane(labDetails);
        labScroll.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        labScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        labScroll.setPreferredSize(new Dimension(300, 92));
        lab.add(labScroll);
        lab.add(note("Опыт сбрасывает мир и задаёт одинаковые начальные условия для сцены и проверки. Изменения сцены после запуска не меняют проверяемую копию. «Перезагрузить» возвращает обычную сцену."));
        labRunBtn.addActionListener(ev -> runLab());
        syncLab();

        // ---------- полоса тем ----------
        setLayout(new BorderLayout());
        setBackground(Theme.UI_BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.UI_LINE));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 4));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        String[] names = {"Сценарий", "Вещество/частица", "Тяготение", "Расчёт", "Вид", "Лаборатория"};
        Icons[] icons = {Icons.WORLD, Icons.ATOM, Icons.FUNCTION, Icons.CPU, Icons.EYE, Icons.FUNCTION};
        JPanel[] contents = {scenario, parts, grav, calc, view, lab};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TabButton t = new TabButton(names[i], icons[i]);
            t.addActionListener(e -> toggle(idx));
            tabs.add(t);
            left.add(t);
            JScrollPane sp = popup(contents[i]);
            pages.add(sp);
            cards.add(new Card(sp));
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6));
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        pauseBtn.icon = Icons.PAUSE;
        reloadBtn.icon = Icons.RELOAD;
        resetBtn.icon = Icons.REFRESH;
        for (FlatButton b : new FlatButton[]{pauseBtn, reloadBtn, resetBtn, speedBtn}) {
            b.setFont(Theme.UI_SMALL);
            right.add(b);
        }
        pauseBtn.addActionListener(e -> s.paused = !s.paused);
        resetBtn.addActionListener(e -> world.reset());
        speedBtn.addActionListener(e -> stepSpeed(1));
        speedBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) stepSpeed(-1);
            }
        });
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    private void syncSurfaceContact() {
        boolean enabled = world.impenetrable || world.softRepulsion;
        impenBtn.setOn(enabled);
        impenBtn.setText("Контакт поверхностей: " + (enabled ? "вкл" : "выкл"));
    }

    private void syncLab() {
        labRevision++;
        if (labExps.isEmpty()) return;
        Lab.Experiment e = labExps.get(labIdx);
        labHint.setText("<html><body style='width:300px'>" + e.hint() + "</body></html>");
        labHint.setFont(Theme.UI_SMALL);
        labHint.setForeground(Theme.UI_HINT);
        labVerdict.setText(" ");
        labDetails.setText("");
        labDetails.setEditable(false);
        labDetails.setLineWrap(true);
        labDetails.setWrapStyleWord(true);
        labDetails.setBackground(Theme.UI_CARD);
        labDetails.setForeground(Theme.UI_TEXT);
        labDetails.setFont(Theme.UI_SMALL);
    }

    private void runLab() {
        if (labExps.isEmpty()) return;
        Lab.Experiment e = labExps.get(labIdx);
        final long revision = labRevision;
        e.setup(world);
        s.paused = false;
        s.speed = 1;
        selectedPreset = Scenarios.Preset.SANDBOX;
        cam.resetOffset();
        cam.camR = Math.max(6, 2.5 * Scenarios.packRadius(world.parts));
        if (onScenarioApply != null) onScenarioApply.run();
        world.changed();
        labVerdict.setText("идёт…");
        labVerdict.setForeground(Theme.UI_TEXT);
        labRunBtn.setEnabled(false);
        new javax.swing.SwingWorker<Lab.Report, Void>() {
            @Override
            protected Lab.Report doInBackground() {
                return e.run();
            }

            @Override
            protected void done() {
                try {
                    Lab.Report r = get();
                    if (revision != labRevision) return;
                    labVerdict.setForeground(r.ok() ? Theme.UI_SEL_TEXT : new java.awt.Color(0xDB9300));
                    labVerdict.setText((r.ok() ? "✔ " : "✘ ") + r.verdict());
                    labDetails.setText(r.details());
                } catch (Exception ex) {
                    if (revision != labRevision) return;
                    labVerdict.setForeground(new java.awt.Color(0xDB9300));
                    labVerdict.setText("ошибка: " + ex.getMessage());
                } finally {
                    labRunBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private static final double[] SPEEDS = {0.125, 0.25, 0.5, 1, 4, 16, 64, 256, 1024};

    /** Скорость времени на шаг вперёд (d = 1) или назад (d = −1), по кругу. */
    private void stepSpeed(int d) {
        int i = 0;
        while (i < SPEEDS.length - 1 && SPEEDS[i] < s.speed) i++;
        s.speed = SPEEDS[Math.floorMod(i + d, SPEEDS.length)];
    }

    // ---------- выпадающие панели ----------

    /** Слой окна, где показываются панели тем (поверх сцены); задаётся App. */
    private JLayeredPane layer;
    private int openIdx = -1;
    private Runnable onHelp;

    void attach(JLayeredPane layer, Runnable onHelp) {
        attach(layer, onHelp, null);
    }

    void attach(JLayeredPane layer, Runnable onHelp, Runnable onScenarioApply) {
        this.layer = layer;
        this.onHelp = onHelp;
        this.onScenarioApply = onScenarioApply;
    }

    /** Открыть тему i; повторный клик по открытой — закрыть. */
    void toggle(int i) {
        if (openIdx == i) close();
        else open(i);
    }

    void open(int i) {
        close();
        if (layer == null) return;
        openIdx = i;
        layer.add(cards.get(i), JLayeredPane.PALETTE_LAYER);
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).setSelected(k == i);
        place();
    }

    /** Закрыть открытую панель (повторный клик по теме, клик по сцене, Esc). */
    void close() {
        if (openIdx < 0) return;
        JComponent p = cards.get(openIdx);
        java.awt.Rectangle r = p.getBounds();
        layer.remove(p);
        layer.repaint(r);
        tabs.get(openIdx).setSelected(false);
        openIdx = -1;
    }

    /** Панель — под своей темой, по высоте содержимого, но не ниже окна (тогда прокрутка). */
    private void place() {
        if (openIdx < 0 || layer == null) return;
        JScrollPane sp = pages.get(openIdx);
        Card p = cards.get(openIdx);
        TabButton t = tabs.get(openIdx);
        Point at = SwingUtilities.convertPoint(t, 0, getHeight(), layer);
        at.y = SwingUtilities.convertPoint(this, 0, getHeight(), layer).y - 1; // поверх нижней линии полосы
        int w = openIdx == 3 ? Theme.PANEL_W_CALC : Theme.PANEL_W; // 3 = «Расчёт» — шире под device chips
        int need = sp.getViewport().getView().getPreferredSize().height + 2;
        int h = Math.max(80, Math.min(need, layer.getHeight() - at.y - 12));
        int x = Math.max(0, Math.min(at.x, layer.getWidth() - w - 8));
        java.awt.Rectangle r = new java.awt.Rectangle(x, at.y, w, h);
        if (!r.equals(p.getBounds())) {
            java.awt.Rectangle old = p.getBounds();
            p.setBounds(r);
            p.revalidate();
            layer.repaint(old);
            p.repaint();
        }
    }


    /** Обновить счётчики, подсветку, ползунки и их значения по состоянию модели (раз в кадр). */
    void sync(World.Counts c) {
        Map<String, Integer> total = Map.of("p", c.p(), "n", c.n(), "e", c.e(), "a", c.atoms(), "mol", c.molecules(), "d", c.dipoles(), "m", c.custom());
        Map<String, Integer> free = Map.of("p", c.pFree(), "e", c.eFree());
        Map<String, Boolean> canDel = Map.of("p", c.pFree() > 0, "n", c.n() > 0, "e", c.eFree() > 0, "a", c.atoms() > 0, "mol", c.molecules() > 0, "d", c.dipoles() > 0, "m", c.custom() > 0);
        List<String> visible = ROWS.get(world.scene);
        boolean layout = false;
        for (Row r : rows.values()) {
            boolean v = visible.contains(r.key);
            if (r.isVisible() != v) {
                r.setVisible(v);
                layout = true;
            }
            int f = free.getOrDefault(r.key, 0), t = total.get(r.key);
            r.count.setText(f > 0 && f < t ? "<html>" + t + " <font color='#898781'>св " + f + "</font></html>" : String.valueOf(t));
            r.minus.setEnabled(canDel.get(r.key));
        }
        rows.get("n").plus.setEnabled(!(world.scene == World.Scene.ATOMS && c.atoms() == 0));
        if (customField != null && !customField.isFocusOwner()) customField.setText(formatCustom());
        double cr = uif.Formula.radOf(world.customMass);
        customVal.setText("радиус " + Theme.exp(cr, 2) + " a₀ — " + Theme.fixed(cr / uif.Formula.RAD_E, 1) + " × электрона, "
                + Theme.fixed(cr / uif.Formula.RAD_P, 2) + " × протона");
        boolean atomsScene = world.scene == World.Scene.ATOMS;
        layout |= show(atomRowBox, true); // список атомов виден всегда
        layout |= show(moleculeRowBox, true); // список молекул — отдельно, рядом с атомами
        if (atomBox.getSelectedItem() != world.atomType) atomBox.setSelectedItem(world.atomType);
        if (moleculeBox.getSelectedItem() != world.moleculeType) moleculeBox.setSelectedItem(world.moleculeType);

        honestBtn.setOn(world.G == World.G_HONEST);
        mediumBtn.setOn(world.G == World.G_MEDIUM);
        oneBtn.setOn(world.G == World.G_DEFAULT || world.G == 1);
        pauseBtn.icon = s.paused ? Icons.PLAY : Icons.PAUSE;
        pauseBtn.setToolTipText(s.paused ? "пуск (Space)" : "пауза (Space)");
        pauseBtn.repaint();
        speedBtn.setText("×" + App.speedText(s.speed));
        for (Map.Entry<Double, FlatButton> e : speedBtns.entrySet()) e.getValue().setOn(e.getKey() == s.speed);

        if (world.mode != World.Mode.KUO) world.setMode(World.Mode.KUO); // UI: путь через Kuo; вкл/выкл — kuo.on
        boolean kuoOn = world.kuo.on;
        kuoOnBtn.setOn(kuoOn);
        kuoModeVal.setText(kuoOn
                ? "комплексное: −β_т·W + приталкивание от КУО; при связанных шкалах итог = W = Q·N"
                : "КУО выкл: только −β_т·W — тела расталкиваются (без голубого push)");
        layout |= show(betaTRow, true);
        layout |= show(kuoPushBox, kuoOn);
        linkBtn.setOn(world.kuo.linked);
        double gain = world.kuo.gain();
        gainVal.setText(world.kuo.linked ? "итог пары: −β_т·W + (1 + β_т)·W = W = Q·N"
                : "итог пары: (β_к − β_т)·W = " + (gain < 0 ? "−" : "") + Theme.beta(Math.abs(gain)) + "·W"
                + (gain == 1 ? "" : gain > 0 ? " — не W = Q·N" : " — тела расталкиваются"));
        kuoInfBtn.setOn(!world.kuo.closed);
        kuoClosedBtn.setOn(world.kuo.closed);
        layout |= show(kuoBox, kuoOn && world.kuo.closed);
        if (kuoOn && world.kuo.closed) {
            double ng = world.kuo.netGain();
            kuoVal.setText("q = " + Theme.fixed(world.kuo.q(), 3) + " · итог пары " + (ng < 0 ? "−" : "") + Theme.beta(Math.abs(ng)) + "·W"
                    + (ng == 0 ? " — равновесие" : ng < 0 ? " — тела расталкиваются" : ""));
        }
        for (Map.Entry<World.Spawn, FlatButton> e : spawnBtns.entrySet()) e.getValue().setOn(e.getKey() == world.spawn);
        layout |= show(throwBox, world.spawn == World.Spawn.THROW);
        for (Map.Entry<Integer, FlatButton> e : batchBtns.entrySet()) e.getValue().setOn(e.getKey() == batch);
        for (Map.Entry<Forces.Device, FlatButton> e : deviceBtns.entrySet()) e.getValue().setOn(e.getKey() == world.forces.device);
        String dv = "сейчас: " + world.forces.lastPath + " · видеокарта: "
                + (world.forces.gpuChecked() ? world.forces.gpuStatus() : "проверится при первом расчёте на GPU");
        if (!dv.equals(deviceVal.getToolTipText())) {
            deviceVal.setText(wrap(dv)); // ширина задана — иначе длинная строка растянет панель
            deviceVal.setToolTipText(dv);
        }

        Chamber ch = world.chamber;
        chamberOn.setOn(ch.on);
        chamberOff.setOn(!ch.on);
        extremeOn.setOn(ch.isExtreme());
        extremeOff.setOn(!ch.isExtreme());
        syncSurfaceContact();
        for (Map.Entry<Scenarios.Preset, FlatButton> e : presetBtns.entrySet()) e.getValue().setOn(e.getKey() == selectedPreset);
        for (Map.Entry<Scenarios.MoonsBeltsMode, FlatButton> e : moonsBeltsBtns.entrySet())
            e.getValue().setOn(e.getKey() == world.moonsBeltsMode);
        layout |= show(moonShareRow, selectedPreset == Scenarios.Preset.EARTH_MOON || selectedPreset == Scenarios.Preset.SOLAR);
        layout |= show(fragmentCountRow, selectedPreset == Scenarios.Preset.FRAGMENTS);
        layout |= show(applyRow, selectedPreset == Scenarios.Preset.FRAGMENTS);
        for (Map.Entry<Integer, FlatButton> e : fragmentCountBtns.entrySet()) e.getValue().setOn(e.getKey() == world.fragmentCount);
        if (moonShareField != null && !moonShareField.isFocusOwner()) moonShareField.setText(formatMoonShare());
        refreshScenarioDetails();
        layout |= show(chBox, ch.on);
        layout |= show(chOff, !ch.on);
        if (ch.on) {
            chVal.setText(wrap("радиус сейчас " + Theme.fixed(ch.R(), 3) + " · радиус покоя " + Theme.fixed(ch.restRadius(), 3)
                    + " · толщина вязкого слоя " + Theme.fixed(ch.hFrac * ch.R(), 2)));
        }

        fieldBtn.setOn(s.showField);
        trailBtn.setOn(s.showTrail);
        orbitBtn.setEnabled(s.orbitsReady);
        orbitBtn.setOn(s.showOrbits && s.orbitsReady);
        orbitBtn.setToolTipText(s.orbitsReady
                ? "эллипсы связанных орбит (планеты/луны/астероиды/частицы/электроны) вокруг более тяжёлого централа"
                : "нужны хотя бы два тела");
        groundBtn.setOn(s.showGround);
        equatorBtn.setOn(s.showEquator);
        forcesBtn.setOn(s.showForces);
        kuoViewBtn.setOn(s.showKuo);
        polesSidesBtn.setOn(s.showPolesSides);
        tidalEquatorBtn.setOn(world.tidalEquator);
        atomERepBtn.setOn(world.atomERepulsion);
        overlapRepBtn.setOn(world.overlapRepulsion);
        layout |= show(atomEKBox, world.atomERepulsion);
        layout |= show(overlapKBox, world.overlapRepulsion);
        labelsBtn.setOn(s.showLabels);
        ballBtn.setOn(!s.bodyField);
        cloudBtn.setOn(s.bodyField);
        testBtn.setOn(s.testOpen);
        String gl = GlScene.status();
        shaderBtn.setOn(s.shaders && !GlScene.failed());
        String tip = GlScene.failed() ? "шейдеры недоступны — рисует Java2D: " + gl
                : "тела (свечение и объём у всех) и следы рисует видеокарта" + (gl != null ? ": " + gl : "") + "; выкл — Java2D";
        String st = GlScene.failed() ? "недоступны — рисует Java2D: " + gl
                : !s.shaders ? "выключены — рисует Java2D" : gl != null ? "видеокарта: " + gl : "видеокарта откроется при первом кадре";
        if (!tip.equals(shaderBtn.getToolTipText()) || !st.equals(shaderVal.getToolTipText())) {
            shaderBtn.setToolTipText(tip);
            shaderVal.setToolTipText(st);
            shaderVal.setText(wrap(st));
            shaderVal.setForeground(Theme.UI_MUTED);
        }
        for (Map.Entry<Settings.DeformMode, FlatButton> e : deformBtns.entrySet())
            e.getValue().setOn(e.getKey() == s.deform);

        for (Runnable r : syncers) r.run();
        if (layout) for (JScrollPane p : pages) p.revalidate();
        place();
    }

    private void repeat(Runnable r) {
        for (int i = 0; i < batch; i++) r.run();
    }

    private static boolean show(JComponent c, boolean v) {
        if (c.isVisible() == v) return false;
        c.setVisible(v);
        return true;
    }

    // ---------- строители ----------

    private void addRow(JPanel to, String key, String name, String sym, String tip, Runnable remove, Runnable addOne) {
        Row r = new Row(key, name, sym, tip, () -> repeat(remove), () -> repeat(addOne));
        rows.put(key, r);
        to.add(r);
    }

    /** Масса своей частицы в поле: 1, 1836, 1.0e+6 — коротко. */
    private String formatCustom() {
        double m = world.customMass;
        return m >= 1000 || m < 0.01 ? Theme.exp(m, 2) : Theme.fixed(m, m < 10 ? 2 : 0);
    }

    private String formatMoonShare() {
        double s = world.moonShare;
        // удобный вид для default 1/81.3
        if (Math.abs(s - Scenarios.MOON_SHARE_DEFAULT) < 1e-9) return "1/81.3";
        return s >= 1000 || s < 0.01 ? Theme.exp(s, 3) : Theme.fixed(s, s < 0.1 ? 5 : 4);
    }

    private void applyMoonShare() {
        applyMoonShareParseOnly();
        refreshScenarioDetails(); // доля — для следующего append; сцену не трогаем
    }

    private void selectScenario(Scenarios.Preset p) {
        selectedPreset = p;
        if (Scenarios.isBase(p)) loadBaseScenario(p, true);
        else refreshScenarioDetails();
    }

    /** Песочница — очистить; Земля–Луна / СС — добавить поверх + подогнать камеру. */
    private void loadBaseScenario(Scenarios.Preset p, boolean readMoonField) {
        if (readMoonField && (p == Scenarios.Preset.EARTH_MOON || p == Scenarios.Preset.SOLAR)
                && moonShareField != null && !moonShareField.isFocusOwner()) {
            applyMoonShareParseOnly();
        }
        boolean clear = Scenarios.clearsScene(p);
        world.applyPreset(p);
        if (Scenarios.enablesKuoAndGpuBh(p)) s.showKuo = true; // устройство — в World.appendPreset
        if (clear) {
            cam.resetOffset();
            cam.camR = Math.max(Camera.MIN_R, Math.min(Camera.MAX_R, Scenarios.camR(world)));
        } else {
            double need = Math.max(Scenarios.camR(world), 1.25 * Scenarios.packRadius(world.parts));
            cam.camR = Math.max(cam.camR, Math.max(Camera.MIN_R, Math.min(Camera.MAX_R, need)));
        }
        if (onScenarioApply != null) onScenarioApply.run();
        refreshScenarioDetails();
    }

    private void applyMoonShareParseOnly() {
        String t = moonShareField.getText().trim().replace(',', '.').replace('\u00A0', ' ').replace(" ", "");
        try {
            double v;
            int slash = t.indexOf('/');
            if (slash > 0) {
                double a = Double.parseDouble(t.substring(0, slash));
                double b = Double.parseDouble(t.substring(slash + 1));
                if (!(b > 0) || !Double.isFinite(a) || !Double.isFinite(b)) throw new NumberFormatException();
                v = a / b;
            } else {
                v = Double.parseDouble(t);
            }
            if (Double.isFinite(v) && v > 0 && v < 1e6) world.moonShare = v;
        } catch (NumberFormatException ignored) {
            // оставляем прежнюю долю
        }
        moonShareField.setText(formatMoonShare());
    }

    /** «Мелкие фракции»: только append поверх текущего базового пресета. */
    private void applyFragments() {
        world.addFragments();
        double fragCam = Math.max(8, 2.2 * Math.cbrt(Math.max(1, world.fragmentCount)));
        cam.camR = Math.max(cam.camR, Math.max(Camera.MIN_R, Math.min(Camera.MAX_R, fragCam)));
        if (onScenarioApply != null) onScenarioApply.run();
        refreshScenarioDetails();
    }

    private void refreshScenarioDetails() {
        String moonN = Theme.exp(Scenarios.moonN(world.moonShare), 2);
        String earthN = Theme.exp(Scenarios.EARTH_N, 2);
        String modeTag = world.moonsBeltsMode.title;
        String details = switch (selectedPreset) {
            case EARTH_MOON -> "2 тела · N⊕≈" + earthN + " · N☽≈" + moonN + " · астро-масштаб ("
                    + Theme.fixed(Scenarios.MOON_A_RE, 1) + " R⊕) · клик добавляет поверх";
            case SOLAR -> "режим " + modeTag + " · Солнце + планеты + спутники + пояс×"
                    + Scenarios.mainBeltCount(world) + " (N "
                    + Theme.exp(Scenarios.BELT_N_MIN, 0) + "…" + Theme.exp(Scenarios.BELT_N_MAX, 0)
                    + " m_e) · N⊕≈" + earthN + " · клик добавляет поверх";
            case RANDOM_SOLAR -> "режим " + modeTag + " · случайная звезда + 4…12 планет / спутники / пояса · "
                    + "массы и радиусы варьируются · клик добавляет поверх";
            case BIG_STAR -> "1 тело · STAR · ~" + Theme.fixed(Scenarios.BIG_STAR_MSUN, 0)
                    + " M☉ · R≈" + Theme.fixed(Scenarios.BIG_STAR_RE, 0)
                    + " R⊕ (≫ Солнца по N и радиусу) · клик добавляет поверх";
            case PLANET_MOONS -> "режим " + modeTag + " · 1 случайная планета + спутники (честный N-body) · "
                    + "без звезды и поясов · клик добавляет поверх";
            case SOLAR_GROUP -> "режим " + modeTag + " · " + Scenarios.SOLAR_GROUP_MIN + "…"
                    + Scenarios.SOLAR_GROUP_MAX + " случайных СС, разнесённых в пространстве · "
                    + "при apply: отображение КУО + GPU float Barnes–Hut · клик добавляет поверх";
            case FRAGMENTS -> world.fragmentCount + " тел · массы астероидов "
                    + Theme.exp(Scenarios.ASTEROID_N_MIN, 0) + "…" + Theme.exp(Scenarios.ASTEROID_N_MAX, 0)
                    + " m_e · добавить поверх · непроницаемые → вкл";
            case SANDBOX -> "0 тел · пустая песочница · клик очищает сцену";
        };
        scenarioDetails.setText(wrap(details));
        int n = world.parts.size();
        if (selectedPreset == Scenarios.Preset.FRAGMENTS) {
            replaceNote.setText(wrap(n == 0
                    ? "сцена пуста — «применить» добавит " + world.fragmentCount + " тел"
                    : "сейчас " + n + " тел — «применить» добавит ещё " + world.fragmentCount));
        } else if (Scenarios.clearsScene(selectedPreset)) {
            replaceNote.setText(wrap("Песочница очищает сцену"
                    + (n > 0 ? " (сейчас " + n + " тел)" : "")));
        } else {
            replaceNote.setText(wrap(n == 0
                    ? "сцена пуста — клик загрузит пресет"
                    : "сейчас " + n + " тел — клик добавит ещё один такой пресет рядом"));
        }
        layoutShowScenarioExtras();
    }

    private void layoutShowScenarioExtras() {
        boolean moon = selectedPreset == Scenarios.Preset.EARTH_MOON || selectedPreset == Scenarios.Preset.SOLAR;
        boolean frag = selectedPreset == Scenarios.Preset.FRAGMENTS;
        boolean changed = false;
        if (moonShareRow.isVisible() != moon) {
            moonShareRow.setVisible(moon);
            changed = true;
        }
        if (fragmentCountRow.isVisible() != frag) {
            fragmentCountRow.setVisible(frag);
            changed = true;
        }
        if (applyRow.isVisible() != frag) {
            applyRow.setVisible(frag);
            changed = true;
        }
        if (changed) {
            for (JScrollPane p : pages) p.revalidate();
            place();
        }
    }

    /** Прочитать массу из поля (Enter или уход фокуса); мусор — вернуть прежнее значение. */
    private void applyCustom() {
        String t = customField.getText().trim().replace(',', '.').replace('\u00A0', ' ');
        try {
            double v = Double.parseDouble(t);
            if (Double.isFinite(v) && v > 0) {
                world.customMass = v;
                world.changed();
            }
        } catch (NumberFormatException ignored) {
            // оставляем прежнюю массу
        }
        customField.setText(formatCustom());
    }

    private void chamberSlider(String title, String tip, double min, double max, double step, DoubleConsumer set,
                               java.util.function.DoubleSupplier get, Supplier<String> value) {
        slider(chBox, title, tip, min, max, step, set, get, value, true);
    }

    /** Подпись слева, значение справа, ползунок под ними; changed — параметр меняет энергию (ΔE/E заново). */
    private void slider(JPanel to, String title, String tip, double min, double max, double step, DoubleConsumer set,
                        java.util.function.DoubleSupplier get, Supplier<String> value, boolean changed) {
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel t = label(title, Theme.UI_TEXT);
        t.setToolTipText(tip);
        JLabel v = label("", Theme.UI_ACCENT);
        v.setFont(Theme.UI_MONO);
        v.setHorizontalAlignment(SwingConstants.RIGHT);
        head.add(t, BorderLayout.WEST);
        head.add(v, BorderLayout.EAST);
        head.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        to.add(stretch(head));
        Slider sl = new Slider(min, max, step, x -> {
            set.accept(x);
            if (changed) world.changed();
        });
        sl.setToolTipText(tip);
        to.add(stretch(sl));
        syncers.add(() -> {
            sl.sync(get.getAsDouble());
            v.setText(value.get());
        });
    }

    /** Логарифмический ползунок β: крайнее левое — 0, иначе 10^v, не больше BETA_MAX. */
    private static double logBeta(double v) {
        return v <= -2.95 ? 0 : Math.min(Kuo.BETA_MAX, Math.pow(10, v));
    }

    private static double sliderLog(double b) {
        return b > 0 ? Math.log10(b) : -3;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Выпадающая панель темы: белая, при нехватке высоты — прокрутка; первая группа — без линии сверху. */
    private static JScrollPane popup(JPanel content) {
        if (content.getComponentCount() > 0 && content.getComponent(0) instanceof JLabel h && h.getBorder() instanceof Head hd) hd.first = true;
        JPanel wrap = new Fit();
        wrap.setBackground(Theme.UI_BG);
        wrap.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        wrap.add(content, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.UI_BG);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        return sp;
    }

    /** Карточка панели: верх прилегает к полосе, низ скруглён; тонкая рамка без верхней стороны. */
    private static final class Card extends JPanel {
        private static final int R = 8;

        Card(JScrollPane sp) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 1, 1, 1));
            add(sp);
        }

        private java.awt.Shape shape(double inset) {
            int w = getWidth(), h = getHeight();
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
            p.moveTo(inset, 0);
            p.lineTo(inset, h - R / 2.0);
            p.quadTo(inset, h - inset, R / 2.0, h - inset);
            p.lineTo(w - R / 2.0, h - inset);
            p.quadTo(w - inset, h - inset, w - inset, h - R / 2.0);
            p.lineTo(w - inset, 0);
            return p;
        }

        @Override
        public void paint(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            java.awt.geom.Path2D clip = new java.awt.geom.Path2D.Double(shape(0));
            clip.closePath();
            g.clip(clip);
            super.paint(g);
            g.dispose();
            g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.UI_BORDER);
            g.draw(shape(0.5));
            g.dispose();
        }
    }

    /** Содержимое панели по ширине панели (ряды кнопок сжимаются), по высоте — сколько нужно. */
    private static final class Fit extends JPanel implements javax.swing.Scrollable {
        Fit() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return r.height - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Заголовок группы: с заглавной буквы, серые, над ним — тонкая линия (у первой в панели — нет). */
    private static JLabel section(String text) {
        String shown = text.isEmpty() ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        JLabel l = new JLabel(shown);
        l.setFont(Theme.UI_HEAD);
        l.setForeground(Theme.UI_HINT);
        l.setBorder(new Head());
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); // линия — во всю ширину панели
        return l;
    }

    /** Отступы заголовка группы и линия над ним. */
    private static final class Head extends javax.swing.border.AbstractBorder {
        boolean first;

        @Override
        public Insets getBorderInsets(Component c) {
            return getBorderInsets(c, new Insets(0, 0, 0, 0));
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(first ? 0 : 19, 0, 4, 0);
            return i;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (first) return;
            g.setColor(Theme.UI_LINE);
            g.fillRect(x, y + 10, w, 1);
        }
    }

    /** Текст с переносом по ширине панели (иначе высота посчитана на одну строку). */
    private static String wrap(String body) {
        return body.isEmpty() ? "" : "<html><div style='width:" + (Theme.PANEL_W - 110) + "px'>" + body + "</div></html>";
    }

    /** Пояснение мелким шрифтом, вторичным цветом. */
    private static JLabel note(String text) {
        JLabel l = new JLabel(wrap(text.replace("<html>", "").replace("</html>", "")));
        l.setFont(Theme.UI_SMALL);
        l.setForeground(Theme.UI_MUTED);
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel label(String text, java.awt.Color c) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.UI_BODY);
        l.setForeground(c);
        return l;
    }

    /** Подпись строки над элементом (сегментом), как «способ» в макете. */
    private static JComponent labeled(String title, String tip, JComponent c) {
        JPanel p = column();
        JLabel t = label(title, Theme.UI_TEXT);
        t.setToolTipText(tip);
        t.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        p.add(stretch(t));
        p.add(c);
        return p;
    }

    /** Сегмент: взаимоисключающие кнопки в одной рамке, разделённые тонкими линиями. */
    private static JComponent seg(FlatButton... buttons) {
        Seg p = new Seg(buttons.length);
        for (FlatButton b : buttons) {
            b.cell = true;
            b.setFont(Theme.UI_SMALL);
            p.add(b);
        }
        return stretch(p);
    }

    private static final class Seg extends JPanel {
        private final int n;

        Seg(int n) {
            super(new GridLayout(1, n, 0, 0));
            this.n = n;
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 26);
        }

        @Override
        public void paint(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.UI_CARD);
            g.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);
            g.clip(new java.awt.geom.RoundRectangle2D.Double(0, 0, w - 1, h - 1, 10, 10));
            paintChildren(g);
            g.setClip(null);
            g.setColor(Theme.UI_LINE);
            for (int i = 1; i < n; i++) {
                Component c = getComponent(i);
                g.fillRect(c.getX(), 1, 1, h - 2);
            }
            g.setColor(Theme.UI_BORDER);
            g.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
            g.dispose();
        }
    }

    /**
     * Переключатели-«фишки»: отдельные кнопки по ширине текста.
     * {@link ChipRow} считает высоту с переносом строк под ширину панели — иначе при
     * {@code stretch()} + BoxLayout вторая строка (например «подписи») обрезается.
     */
    private static JComponent chips(FlatButton... buttons) {
        ChipRow p = new ChipRow();
        for (FlatButton b : buttons) {
            b.setFont(Theme.UI_SMALL);
            p.add(b);
        }
        return p;
    }

    /**
     * FlowLayout, безопасный для вертикального BoxLayout: preferred/maximum height —
     * по фактическому переносу при ширине панели ({@link Theme#PANEL_W}), а не «одна строка
     * на бесконечной ширине», которую раньше замораживал {@link #stretch}.
     */
    private static final class ChipRow extends JPanel {
        ChipRow() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 4));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(2, -6, 2, 0)); // FlowLayout даёт отступ слева — убираем
            ((FlowLayout) getLayout()).setAlignOnBaseline(false);
        }

        private int availWidth() {
            if (getWidth() > 0) return getWidth();
            for (Container p = getParent(); p != null; p = p.getParent()) {
                if (p.getWidth() > 0) return Math.max(80, p.getWidth());
            }
            return Theme.PANEL_W - 24; // поля popup(10+12)*≈
        }

        @Override
        public Dimension getPreferredSize() {
            int avail = availWidth();
            Insets in = getInsets();
            FlowLayout lay = (FlowLayout) getLayout();
            int hgap = lay.getHgap(), vgap = lay.getVgap();
            int x = in.left, y = in.top, rowH = 0, maxW = in.left + in.right;
            boolean empty = true;
            for (Component c : getComponents()) {
                if (!c.isVisible()) continue;
                empty = false;
                Dimension d = c.getPreferredSize();
                if (x > in.left && x + d.width + in.right > avail) {
                    y += rowH + vgap;
                    x = in.left;
                    rowH = 0;
                }
                rowH = Math.max(rowH, d.height);
                x += d.width + hgap;
                maxW = Math.max(maxW, x - hgap + in.right);
            }
            if (empty) return new Dimension(avail, in.top + in.bottom + 26);
            return new Dimension(Math.max(maxW, avail), y + rowH + in.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private static JComponent stretch(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** Тонкая скруглённая рамка (список атомов). */
    private static final class Round extends javax.swing.border.AbstractBorder {
        private final java.awt.Color c;

        Round(java.awt.Color c) {
            this.c = c;
        }

        @Override
        public Insets getBorderInsets(Component comp) {
            return getBorderInsets(comp, new Insets(0, 0, 0, 0));
        }

        @Override
        public Insets getBorderInsets(Component comp, Insets i) {
            i.set(3, 8, 3, 4);
            return i;
        }

        @Override
        public void paintBorder(Component comp, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(c);
            g.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
            g.dispose();
        }
    }

    /** Список без объёмной кнопки: белое поле и стрелка-шеврон. */
    private static final class FlatCombo extends javax.swing.plaf.basic.BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Icons.CHEVRON.paint((Graphics2D) g, (getWidth() - 14) / 2.0, (getHeight() - 14) / 2.0, 14, Theme.UI_MUTED);
                }
            };
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusable(false);
            b.setOpaque(false);
            return b;
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, java.awt.Rectangle r, boolean focus) {
            g.setColor(Theme.UI_CARD);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    /** Тема полосы: иконка и название; у открытой — голубая подложка и синий цвет. */
    private static final class TabButton extends JButton {
        private final Icons icon;

        TabButton(String text, Icons icon) {
            super(text);
            this.icon = icon;
            setFont(Theme.UI_BODY);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 14 + 5 + 16, 26);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean hover = getModel().isRollover();
            if (isSelected() || hover) {
                g.setColor(isSelected() ? Theme.UI_SEL_BG : Theme.UI_HOVER);
                g.fillRoundRect(0, 0, w, h, 6, 6);
            }
            java.awt.Color fg = isSelected() ? Theme.UI_SEL_TEXT : hover ? Theme.UI_TEXT : Theme.UI_MUTED;
            icon.paint(g, 8, (h - 14) / 2.0, 14, fg);
            g.setFont(getFont());
            g.setColor(fg);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), 8 + 14 + 5, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Строка «название символ — − счётчик +». */
    private static final class Row extends JPanel {
        final String key;
        final JLabel count = new JLabel("0", SwingConstants.CENTER);
        final FlatButton minus = new FlatButton("−"), plus = new FlatButton("+");

        Row(String key, String name, String sym, String tip, Runnable remove, Runnable addOne) {
            this.key = key;
            setOpaque(false);
            setLayout(new GridBagLayout());
            setToolTipText(tip);
            JLabel label = new JLabel("<html>" + name + " <font color='#898781'>" + sym + "</font></html>");
            label.setFont(Theme.UI_BODY);
            label.setForeground(Theme.UI_TEXT);
            label.setToolTipText(tip);
            count.setFont(Theme.UI_BODY);
            count.setForeground(Theme.UI_TEXT);
            minus.addActionListener(e -> remove.run());
            plus.addActionListener(e -> addOne.run());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(3, 0, 3, 4);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            add(label, gc);
            gc.weightx = 0;
            add(fixed(minus, 22), gc);
            add(fixed(count, 40), gc);
            gc.insets = new Insets(3, 0, 3, 0);
            add(fixed(plus, 22), gc);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        }

        private static JComponent fixed(JComponent c, int w) {
            Dimension d = new Dimension(w, 24);
            c.setPreferredSize(d);
            c.setMinimumSize(d);
            return c;
        }
    }
}
