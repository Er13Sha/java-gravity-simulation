package uif.ui;

import uif.MoleculeLab;
import uif.World;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Geometry editor and isolated, cancellable model experiment. */
final class MoleculeLabPanel extends JPanel {
    private final JComboBox<World.MoleculeType> type = new JComboBox<>(new World.MoleculeType[]{World.MoleculeType.H2, World.MoleculeType.H2O});
    private final JSpinner distance = new JSpinner(new SpinnerNumberModel(3.0, 1.0, 50.0, 0.1));
    private final JSpinner angle = new JSpinner(new SpinnerNumberModel(104.5, 10.0, 180.0, 0.5));
    private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(5.0, 0.1, 60.0, 0.5));
    private final JTextField seed = new JTextField("7", 10);
    private final JTabbedPane modes = new JTabbedPane();
    private final AssemblyEditor editor = new AssemblyEditor();
    private final AssemblyEditor templatePreview = new AssemblyEditor();
    private final JButton transfer = new JButton("Перенести в свободную сборку");
    private final JButton prepare = new JButton("Показать в сцене"), run = new JButton("Испытать / повторить"), stop = new JButton("Остановить");
    private final JTextArea report = new JTextArea(6, 50);
    private final Plot distances = new Plot(false), angles = new Plot(true);
    private final JLabel status = new JLabel("Настройте конструкцию и запустите испытание");
    private final List<Component> inputs = new ArrayList<>();
    private SwingWorker<MoleculeLab.Result, MoleculeLab.Sample> worker;

    MoleculeLabPanel(World world, Camera camera, Settings settings) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new GridLayout(0, 2, 8, 6));
        addField(top, "Молекула", type); addField(top, "Расстояние H–H / O–H (a₀)", distance);
        addField(top, "Угол H–O–H (°)", angle);
        JPanel template = new JPanel(new BorderLayout(6, 6));
        template.add(top, BorderLayout.NORTH); template.add(transfer, BorderLayout.SOUTH);
        template.add(templatePreview.view); templatePreview.setEditable(false);
        modes.addTab("Шаблоны H₂ / H₂O", template); modes.addTab("Свободная сборка", editor);
        JPanel common = new JPanel(new GridLayout(1, 4, 8, 6));
        addField(common, "Длительность модели (с)", seconds); addField(common, "Seed (целое число)", seed);
        angle.setEnabled(false);
        type.addActionListener(e -> {
            boolean water = type.getSelectedItem() == World.MoleculeType.H2O;
            angle.setEnabled(water); distance.setValue(water ? 7.2 : 3.0);
            refreshTemplatePreview();
        });
        distance.addChangeListener(e -> refreshTemplatePreview()); angle.addChangeListener(e -> refreshTemplatePreview());
        refreshTemplatePreview();
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(prepare); actions.add(run); actions.add(stop); stop.setEnabled(false);
        JPanel north = new JPanel(new BorderLayout()); north.add(common); north.add(actions, BorderLayout.SOUTH); add(north, BorderLayout.NORTH);
        JPanel plots = new JPanel(new GridLayout(2, 1, 0, 8)); plots.add(distances); plots.add(angles);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, modes, plots);
        split.setResizeWeight(0.55); split.setDividerLocation(570); add(split);
        modes.setMinimumSize(new Dimension(450, 380)); plots.setMinimumSize(new Dimension(390, 300));
        report.setEditable(false); report.setLineWrap(true); report.setWrapStyleWord(true);
        JPanel south = new JPanel(new BorderLayout(4, 4)); south.add(status, BorderLayout.NORTH); south.add(new JScrollPane(report));
        south.add(new JLabel("W = Q·N; без химических связей. Показ в сцене заменяет текущую сцену и включает паузу."), BorderLayout.SOUTH); add(south, BorderLayout.SOUTH);
        prepare.addActionListener(e -> {
            try {
                MoleculeLab.Setup c = config();
                world.prepareMoleculeLab(c); settings.paused = true;
                camera.resetOffset();
                double[] center = world.centerOfMass();
                double radius = world.parts.stream().mapToDouble(b -> World.dist(b.p, center) + b.rad).max().orElse(4);
                camera.camR = Math.max(12, 3 * radius);
                status.setText("Конструкция в сцене на паузе. Испытание использует эти же начальные условия.");
            } catch (RuntimeException ex) { status.setText("Ошибка: " + ex.getMessage()); }
        });
        run.addActionListener(e -> start());
        stop.addActionListener(e -> cancel());
        transfer.addActionListener(e -> {
            try {
                editor.load(templateConfig().toAssembly()); modes.setSelectedIndex(1);
                status.setText("Геометрия перенесена. Поступательные скорости в свободной сборке — из таблицы (сейчас 0).");
            } catch (RuntimeException ex) { status.setText("Ошибка: " + ex.getMessage()); }
        });
    }

    private void refreshTemplatePreview() {
        try {
            MoleculeLab.Config c = new MoleculeLab.Config((World.MoleculeType)type.getSelectedItem(),
                    ((Number)distance.getValue()).doubleValue(), ((Number)angle.getValue()).doubleValue(), 5, 7);
            templatePreview.load(c.toAssembly()); templatePreview.table.clearSelection();
        } catch (IllegalArgumentException ex) { status.setText("Проверьте геометрию: " + ex.getMessage()); }
    }

    private void addField(JPanel panel, String label, JComponent input) {
        panel.add(new JLabel(label)); panel.add(input); inputs.add(input);
    }

    private long readSeed() {
        try { seconds.commitEdit(); } catch (java.text.ParseException ex) { throw new IllegalArgumentException("Проверьте длительность"); }
        try { return Long.parseLong(seed.getText().trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Seed должен быть целым числом"); }
    }

    private MoleculeLab.Config templateConfig() {
        try { distance.commitEdit(); angle.commitEdit(); seconds.commitEdit(); }
        catch (java.text.ParseException ex) { throw new IllegalArgumentException("Проверьте числовые поля"); }
        long value = readSeed();
        return new MoleculeLab.Config((World.MoleculeType) type.getSelectedItem(),
                ((Number) distance.getValue()).doubleValue(), ((Number) angle.getValue()).doubleValue(),
                ((Number) seconds.getValue()).doubleValue(), value);
    }

    private MoleculeLab.Setup config() {
        long value = readSeed();
        return modes.getSelectedIndex() == 0 ? templateConfig() : editor.snapshot(((Number)seconds.getValue()).doubleValue(), value);
    }

    private void start() {
        final MoleculeLab.Setup c;
        try { c = config(); } catch (RuntimeException ex) { status.setText("Ошибка: " + ex.getMessage()); return; }
        distances.samples.clear(); angles.samples.clear(); distances.repaint(); angles.repaint(); report.setText("");
        MoleculeLab.Measurement measurement = c.measurement();
        angles.caption = measurement == null ? "Угол не выбран" : String.format("Угол атомов %d–%d–%d (°)", measurement.first() + 1, measurement.vertex() + 1, measurement.last() + 1);
        angles.applicable = measurement != null;
        distances.applicable = !(c instanceof MoleculeLab.Assembly a) || a.atoms().size() > 1;
        setBusy(true); status.setText("Расчёт…");
        worker = new SwingWorker<>() {
            protected MoleculeLab.Result doInBackground() { return MoleculeLab.run(c, this::isCancelled, s -> publish(s)); }
            protected void process(List<MoleculeLab.Sample> samples) {
                if (isCancelled()) return;
                distances.samples.addAll(samples); angles.samples.addAll(samples);
                distances.repaint(); angles.repaint();
                status.setText(String.format(java.util.Locale.ROOT, "Время модели: %.2f / %.2f с", samples.getLast().time(), c.seconds()));
            }
            protected void done() {
                try {
                    MoleculeLab.Result r = get();
                    distances.samples.clear(); distances.samples.addAll(r.samples());
                    angles.samples.clear(); angles.samples.addAll(r.samples());
                    status.setText(r.verdict()); report.setText(r.details());
                } catch (CancellationException ex) { status.setText("Остановлено. Графики показывают незавершённый опыт."); }
                catch (Exception ex) { status.setText("Ошибка расчёта"); report.setText(String.valueOf(ex.getCause() == null ? ex : ex.getCause())); }
                finally { setBusy(false); distances.repaint(); angles.repaint(); }
            }
        };
        worker.execute();
    }

    void cancel() { if (worker != null && !worker.isDone()) worker.cancel(true); }

    private void setBusy(boolean busy) {
        inputs.forEach(c -> c.setEnabled(!busy));
        angle.setEnabled(!busy && type.getSelectedItem() == World.MoleculeType.H2O);
        modes.setEnabled(!busy); transfer.setEnabled(!busy); editor.setEditable(!busy);
        prepare.setEnabled(!busy); run.setEnabled(!busy); stop.setEnabled(busy);
    }

    private static final class Plot extends JPanel {
        final boolean angle;
        boolean applicable = true;
        String caption;
        final List<MoleculeLab.Sample> samples = new ArrayList<>();
        Plot(boolean angle) { this.angle = angle; caption = angle ? "Угол (°)" : "Расстояния d/d₀: min (синий), max (красный)"; setPreferredSize(new Dimension(440, 150)); setBackground(Color.WHITE); }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.DARK_GRAY); g.drawString(caption, 10, 18);
                int left = 60, top = 32, width = getWidth() - 80, height = getHeight() - 62;
                if (width <= 0 || height <= 0) return;
                double max = angle ? 180 : Math.max(1.5, samples.stream().mapToDouble(MoleculeLab.Sample::maxRatio).max().orElse(1) * 1.05);
                double end = samples.isEmpty() ? 1 : Math.max(0.01, samples.getLast().time());
                g.setColor(Color.LIGHT_GRAY); g.drawRect(left, top, width, height);
                g.setColor(Color.GRAY); g.drawString(String.format(java.util.Locale.ROOT, "%.2f", max), 8, top + 5);
                g.drawString("0", 35, top + height); g.drawString("0", left, top + height + 20);
                g.drawString(String.format(java.util.Locale.ROOT, "%.2f с", end), left + width - 50, top + height + 20);
                if (!applicable || (angle && (samples.isEmpty() || Double.isNaN(samples.getFirst().angle())))) {
                    g.drawString(angle ? "Для угла выберите 3 атома в сборке" : "Для расстояний нужны хотя бы 2 атома", left + 12, top + 30); return;
                }
                for (int series = 0; series < (angle ? 1 : 2); series++) {
                    g.setColor(series == 0 ? new Color(0x246AC4) : new Color(0xCA403C));
                    for (int i = 1; i < samples.size(); i++) {
                        MoleculeLab.Sample a = samples.get(i - 1), b = samples.get(i);
                        double av = angle ? a.angle() : series == 0 ? a.minRatio() : a.maxRatio();
                        double bv = angle ? b.angle() : series == 0 ? b.minRatio() : b.maxRatio();
                        g.drawLine(left + (int)(width * a.time() / end), top + height - (int)(height * av / max),
                                left + (int)(width * b.time() / end), top + height - (int)(height * bv / max));
                    }
                }
            } finally { g.dispose(); }
        }
    }
}
