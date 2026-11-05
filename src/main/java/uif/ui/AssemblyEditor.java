package uif.ui;

import uif.MoleculeLab;
import uif.World;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Editable atom coordinates with an orthographic, rotatable 3D construction view. */
final class AssemblyEditor extends JPanel {
    private final List<MoleculeLab.Atom> atoms = new ArrayList<>();
    private final AtomModel model = new AtomModel();
    final JTable table = new JTable(model);
    final View view = new View();
    private final JComboBox<World.AtomType> element = new JComboBox<>(World.AtomType.values());
    private final JButton add = new JButton("+ атом"), remove = new JButton("Удалить"), clear = new JButton("Очистить");
    private final JButton fit = new JButton("Вписать");
    private final JComboBox<Integer> vertex = new JComboBox<>();
    private final JLabel measured = new JLabel("Выберите 2 атома для расстояния или 3 для угла");
    private final JLabel message = new JLabel("Координаты — центр ядра; скорости — центр масс атома.");
    private boolean editable = true, refreshingVertex;
    private String validationError;

    AssemblyEditor() {
        super(new BorderLayout(6, 6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        element.setPreferredSize(new Dimension(190, 26));
        buttons.add(element); buttons.add(add); buttons.add(remove); buttons.add(clear); buttons.add(fit);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(24); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(World.AtomType.values())));
        table.getColumnModel().getColumn(0).setPreferredWidth(32);
        table.getColumnModel().getColumn(1).setPreferredWidth(145);
        for (int i = 2; i < 8; i++) table.getColumnModel().getColumn(i).setPreferredWidth(55);
        table.putClientProperty("terminateEditOnFocusLost", true);
        JScrollPane rows = new JScrollPane(table); rows.setPreferredSize(new Dimension(470, 155));
        rows.setColumnHeaderView(table.getTableHeader());
        JPanel north = new JPanel(new BorderLayout(4, 4)); north.add(buttons, BorderLayout.NORTH); north.add(rows); add(north, BorderLayout.NORTH);
        add(view);
        JPanel info = new JPanel(new GridLayout(0, 1, 2, 2));
        JPanel angleChoice = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        angleChoice.add(new JLabel("Вершина угла — атом №")); angleChoice.add(vertex);
        info.add(angleChoice); info.add(measured); info.add(message);
        info.add(new JLabel("ЛКМ по атому — выбор; Ctrl — несколько; перетаскивание — сдвиг."));
        info.add(new JLabel("Пустое место — вращение; колесо — масштаб. Скорости: a₀/с."));
        info.add(new JLabel("Поступательные скорости заданы таблицей; между атомами автоскоростей нет."));
        add(info, BorderLayout.SOUTH);
        add.addActionListener(e -> addAtom());
        remove.addActionListener(e -> removeSelected());
        clear.addActionListener(e -> { if (commit()) { atoms.clear(); model.fireTableDataChanged(); changed(); } });
        fit.addActionListener(e -> { view.fit(); view.repaint(); });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updateMeasurement(); });
        vertex.addActionListener(e -> { if (!refreshingVertex) { measured.setText(measurementText()); view.repaint(); } });
        updateMeasurement();
    }

    boolean commit() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            message.setText("Исправьте значение в редактируемой ячейке"); return false;
        }
        if (validationError != null) { message.setText(validationError); return false; }
        return true;
    }

    MoleculeLab.Assembly snapshot(double seconds, long seed) {
        if (!commit()) throw new IllegalArgumentException("Не удалось применить значение ячейки");
        return new MoleculeLab.Assembly(atoms, seconds, seed, measurement());
    }

    void load(MoleculeLab.Assembly construction) {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        validationError = null;
        atoms.clear(); atoms.addAll(construction.atoms()); model.fireTableDataChanged();
        if (construction.measurement() != null) {
            MoleculeLab.Measurement m = construction.measurement();
            table.addRowSelectionInterval(m.first(), m.first());
            table.addRowSelectionInterval(m.vertex(), m.vertex());
            table.addRowSelectionInterval(m.last(), m.last());
            vertex.setSelectedItem(m.vertex() + 1);
        } else if (!atoms.isEmpty()) table.setRowSelectionInterval(0, 0);
        view.fit(); changed();
    }

    private void addAtom() {
        if (!editable || !commit()) return;
        if (atoms.size() >= 32) { message.setText("Максимум 32 атома в одной сборке"); return; }
        double x = atoms.stream().mapToDouble(MoleculeLab.Atom::x).max().orElse(-3) + 3;
        if (x > 10000) { message.setText("Нет места справа: измените координату крайнего атома"); return; }
        int row = atoms.size();
        atoms.add(new MoleculeLab.Atom((World.AtomType)element.getSelectedItem(), x, 0, 0, 0, 0, 0));
        model.fireTableRowsInserted(row, row); table.setRowSelectionInterval(row, row); view.fit(); changed();
    }

    private void removeSelected() {
        if (!editable || !commit()) return;
        int[] rows = table.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) atoms.remove(rows[i]);
        model.fireTableDataChanged(); changed();
    }

    void setEditable(boolean enabled) {
        editable = enabled;
        table.setEnabled(enabled); element.setEnabled(enabled); add.setEnabled(enabled);
        remove.setEnabled(enabled); clear.setEnabled(enabled); vertex.setEnabled(enabled && table.getSelectedRowCount() == 3);
    }

    private MoleculeLab.Measurement measurement() {
        int[] selected = table.getSelectedRows();
        if (selected.length != 3 || vertex.getSelectedItem() == null) return null;
        int center = (Integer)vertex.getSelectedItem() - 1;
        int[] arms = java.util.Arrays.stream(selected).filter(i -> i != center).toArray();
        if (arms.length != 2) return null;
        return new MoleculeLab.Measurement(arms[0], center, arms[1]);
    }

    private void changed() { updateMeasurement(); view.repaint(); }

    private void updateMeasurement() {
        Object previous = vertex.getSelectedItem();
        refreshingVertex = true; vertex.removeAllItems();
        int[] selected = table.getSelectedRows();
        if (selected.length == 3) {
            for (int i : selected) vertex.addItem(i + 1);
            for (int i : selected) if (Integer.valueOf(i + 1).equals(previous)) vertex.setSelectedItem(previous);
        }
        refreshingVertex = false;
        vertex.setEnabled(editable && selected.length == 3);
        measured.setText(measurementText()); view.repaint();
    }

    private String measurementText() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 2 && selected[1] < atoms.size())
            return String.format(Locale.ROOT, "Расстояние %d–%d: %.4f a₀", selected[0] + 1, selected[1] + 1,
                    World.dist(atoms.get(selected[0]).position(), atoms.get(selected[1]).position()));
        MoleculeLab.Measurement m = measurement();
        if (m != null && m.last() < atoms.size() && m.vertex() < atoms.size()) {
            double angle = MoleculeLab.angle(atoms.get(m.first()), atoms.get(m.vertex()), atoms.get(m.last()));
            return Double.isFinite(angle) ? String.format(Locale.ROOT, "Угол %d–%d–%d: %.3f°", m.first() + 1, m.vertex() + 1, m.last() + 1, angle)
                    : "Угол не определён: проверьте координаты";
        }
        return "Выберите 2 атома для расстояния или 3 для угла";
    }

    private final class AtomModel extends AbstractTableModel {
        public int getRowCount() { return atoms.size(); }
        public int getColumnCount() { return 8; }
        public String getColumnName(int col) { return new String[]{"№", "Атом", "x", "y", "z", "vx", "vy", "vz"}[col]; }
        public Class<?> getColumnClass(int col) { return col == 0 ? Integer.class : col == 1 ? World.AtomType.class : Double.class; }
        public boolean isCellEditable(int row, int col) { return editable && col > 0; }
        public Object getValueAt(int row, int col) {
            MoleculeLab.Atom a = atoms.get(row);
            return col == 0 ? row + 1 : col == 1 ? a.type() : new double[]{a.x(), a.y(), a.z(), a.vx(), a.vy(), a.vz()}[col - 2];
        }
        public void setValueAt(Object value, int row, int col) {
            if (!editable || col == 0) return;
            MoleculeLab.Atom a = atoms.get(row);
            try {
                double[] values = {a.x(), a.y(), a.z(), a.vx(), a.vy(), a.vz()};
                if (col > 1) values[col - 2] = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().replace(',', '.'));
                atoms.set(row, new MoleculeLab.Atom(col == 1 ? (World.AtomType)value : a.type(),
                        values[0], values[1], values[2], values[3], values[4], values[5]));
                validationError = null;
                message.setText("Изменения применены к сборке"); fireTableRowsUpdated(row, row); changed();
            } catch (RuntimeException ex) {
                validationError = "Исправьте ячейку: " + ex.getMessage();
                message.setText(validationError); fireTableCellUpdated(row, col);
            }
        }
    }

    final class View extends JPanel {
        private double yaw = 0.4, pitch = 0.3, zoom = 1, scale = 20;
        private double[] center = new double[3];
        private Point last;
        private int dragged = -1;
        View() {
            setPreferredSize(new Dimension(470, 240)); setBackground(new Color(0xF5F8FC));
            MouseAdapter mouse = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    if (!commit()) return;
                    last = e.getPoint(); dragged = -1;
                    int hit = hit(e.getX(), e.getY());
                    if (editable && SwingUtilities.isLeftMouseButton(e) && hit >= 0) {
                        if (e.isControlDown()) {
                            if (table.isRowSelected(hit)) table.removeRowSelectionInterval(hit, hit); else table.addRowSelectionInterval(hit, hit);
                        } else {
                            if (!table.isRowSelected(hit)) table.setRowSelectionInterval(hit, hit);
                            dragged = hit;
                        }
                    }
                }
                public void mouseDragged(MouseEvent e) {
                    if (last == null) return;
                    int dx = e.getX() - last.x, dy = e.getY() - last.y;
                    if (editable && dragged >= 0 && dragged < atoms.size()) {
                        MoleculeLab.Atom a = atoms.get(dragged);
                        double x = dx / scale, y = -dy / scale;
                        try {
                            atoms.set(dragged, new MoleculeLab.Atom(a.type(),
                                    a.x() + x * Math.cos(yaw) - y * Math.sin(pitch) * Math.sin(yaw),
                                    a.y() + y * Math.cos(pitch),
                                    a.z() - x * Math.sin(yaw) - y * Math.sin(pitch) * Math.cos(yaw), a.vx(), a.vy(), a.vz()));
                            model.fireTableRowsUpdated(dragged, dragged); changed();
                        } catch (IllegalArgumentException ex) { message.setText(ex.getMessage()); }
                    } else { yaw += dx * 0.01; pitch = Math.max(-1.5, Math.min(1.5, pitch + dy * 0.01)); }
                    last = e.getPoint(); repaint();
                }
                public void mouseReleased(MouseEvent e) { last = null; dragged = -1; }
                public void mouseWheelMoved(MouseWheelEvent e) { zoom = Math.max(0.1, Math.min(30, zoom * Math.pow(1.12, -e.getWheelRotation()))); repaint(); }
            };
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
        }
        void fit() {
            zoom = 1;
            center = new double[3];
            for (MoleculeLab.Atom a : atoms) { center[0] += a.x(); center[1] += a.y(); center[2] += a.z(); }
            if (!atoms.isEmpty()) for (int k = 0; k < 3; k++) center[k] /= atoms.size();
        }
        private double[] turn(double x, double y, double z) {
            return new double[]{Math.cos(yaw) * x - Math.sin(yaw) * z,
                    -Math.sin(pitch) * Math.sin(yaw) * x + Math.cos(pitch) * y - Math.sin(pitch) * Math.cos(yaw) * z,
                    Math.cos(pitch) * Math.sin(yaw) * x + Math.sin(pitch) * y + Math.cos(pitch) * Math.cos(yaw) * z};
        }
        double[] project(int row) {
            MoleculeLab.Atom a = atoms.get(row);
            double[] p = turn(a.x() - center[0], a.y() - center[1], a.z() - center[2]);
            return new double[]{getWidth() / 2.0 + p[0] * scale, getHeight() / 2.0 - p[1] * scale, p[2]};
        }
        private int hit(int x, int y) {
            int best = -1; double depth = -Double.MAX_VALUE;
            for (int i = 0; i < atoms.size(); i++) {
                double[] p = project(i);
                if (Math.hypot(x - p[0], y - p[1]) <= 14 && p[2] > depth) { best = i; depth = p[2]; }
            }
            return best;
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double radius = 3;
                for (MoleculeLab.Atom a : atoms) radius = Math.max(radius, World.dist(a.position(), center));
                if (last == null) scale = Math.max(0.0001, Math.min(getWidth(), getHeight()) * 0.38 * zoom / radius);
                g.setColor(Color.DARK_GRAY); g.drawString("3D: центры ядер (размер кружков условный)", 10, 18);
                Color[] colors = {new Color(0xC54343), new Color(0x318849), new Color(0x3868BC)};
                for (int i = 0; i < 3; i++) {
                    double[] axis = new double[3]; axis[i] = 30;
                    double[] p = turn(axis[0], axis[1], axis[2]); g.setColor(colors[i]);
                    g.drawLine(42, getHeight() - 40, 42 + (int)p[0], getHeight() - 40 - (int)p[1]);
                    g.drawString(new String[]{"X", "Y", "Z"}[i], 42 + (int)(p[0] * 1.3), getHeight() - 40 - (int)(p[1] * 1.3));
                }
                List<Integer> order = new ArrayList<>(); for (int i = 0; i < atoms.size(); i++) order.add(i);
                order.sort(Comparator.comparingDouble(i -> project(i)[2]));
                for (int i : order) {
                    double[] p = project(i); int x = (int)p[0], y = (int)p[1];
                    g.setColor(table.isRowSelected(i) ? new Color(0xE58B20) : colors[atoms.get(i).type().protons % 3]);
                    g.fillOval(x - 10, y - 10, 20, 20); g.setColor(Color.DARK_GRAY); g.drawOval(x - 10, y - 10, 20, 20);
                    g.drawString((i + 1) + ": " + atoms.get(i).type().name(), x + 13, y + 4);
                }
                if (atoms.isEmpty()) { g.setColor(Color.GRAY); g.drawString("Добавьте атом или перенесите шаблон", 40, getHeight() / 2); }
            } finally { g.dispose(); }
        }
    }
}
