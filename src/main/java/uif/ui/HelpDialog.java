package uif.ui;

import uif.World;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Timer;

/** Справочник: слева оглавление, справа схема (живая, по модели) и текст; открывается из меню или по F1. */
final class HelpDialog extends JDialog {
    private final World world;
    private final JList<HelpTopics.Topic> list = new JList<>(HelpTopics.ALL.toArray(HelpTopics.Topic[]::new));
    private final FigurePanel figure = new FigurePanel();
    private final JTextArea text = new JTextArea();

    HelpDialog(JFrame owner, World world) {
        super(owner, "Справочник — W = Q·N", false);
        this.world = world;
        getContentPane().setBackground(Theme.UI_BG);
        setLayout(new BorderLayout());

        list.setBackground(Theme.UI_CARD);
        list.setForeground(Theme.UI_TEXT);
        list.setFont(Theme.UI_FONT);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(30);
        list.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, ((HelpTopics.Topic) v).title(), i, sel, false);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
                setBackground(sel ? Theme.UI_SEL_BG : Theme.UI_CARD);
                setForeground(sel ? Theme.UI_SEL_TEXT : Theme.UI_TEXT);
                return this;
            }
        });
        list.addListSelectionListener(e -> show(list.getSelectedValue()));
        JScrollPane left = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        left.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.UI_LINE));
        left.setPreferredSize(new Dimension(260, 10));
        add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setBackground(Theme.UI_BG);
        right.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        figure.setPreferredSize(new Dimension(10, 230));
        right.add(figure, BorderLayout.NORTH);
        text.setEditable(false);
        text.setFocusable(true); // выделение мышью и Ctrl+C / Ctrl+A
        text.getCaret().setVisible(false);
        text.setSelectionColor(Theme.UI_SEL_BG);
        text.setSelectedTextColor(Theme.UI_TEXT);
        JPopupMenu copyMenu = new JPopupMenu();
        copyMenu.setBackground(Theme.UI_CARD);
        copyMenu.setBorder(BorderFactory.createLineBorder(Theme.UI_BORDER));
        copyMenu.add(item("Копировать", text::copy));
        copyMenu.add(item("Выделить всё", () -> {
            text.requestFocusInWindow();
            text.selectAll();
        }));
        text.setComponentPopupMenu(copyMenu);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(Theme.UI_FONT);
        text.setForeground(Theme.UI_TEXT);
        text.setMargin(new java.awt.Insets(0, 0, 0, 0));
        JScrollPane ts = new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        ts.setBorder(null);
        ts.getViewport().setBackground(Theme.UI_BG);
        ts.setOpaque(false);
        right.add(ts, BorderLayout.CENTER);
        add(right, BorderLayout.CENTER);

        setSize(940, 580);
        setLocationRelativeTo(owner);
        list.setSelectedIndex(0);
        new Timer(200, e -> { if (isVisible()) figure.repaint(); }).start(); // схемы живые: β, режим, состав сцены
    }

    /** topic &lt; 0 — оставить текущий раздел. */
    void open(int topic) {
        if (topic >= 0) list.setSelectedIndex(Math.min(HelpTopics.ALL.size() - 1, topic));
        setVisible(true);
        toFront();
    }

    private void show(HelpTopics.Topic t) {
        if (t == null) return;
        text.setText(t.text().replace("\n", "\n\n"));
        text.setCaretPosition(0);
        figure.topic = t;
        figure.repaint();
    }

    /** Контекстное меню сцены: разделы справочника. */
    JPopupMenu menu() {
        JPopupMenu m = new JPopupMenu();
        m.setBackground(Theme.UI_CARD);
        m.setBorder(BorderFactory.createLineBorder(Theme.UI_BORDER));
        for (int i = 0; i < HelpTopics.ALL.size(); i++) {
            final int idx = i;
            m.add(item(HelpTopics.ALL.get(i).title(), () -> open(idx)));
        }
        m.addSeparator();
        m.add(item("Открыть справочник (F1)", () -> open(list.getSelectedIndex())));
        return m;
    }

    private static JMenuItem item(String title, Runnable r) {
        JMenuItem it = new JMenuItem(title);
        it.setFont(Theme.UI_FONT);
        it.setBackground(Theme.UI_CARD);
        it.setForeground(Theme.UI_TEXT);
        it.setOpaque(true);
        it.addActionListener(e -> r.run());
        return it;
    }

    private final class FigurePanel extends JPanel {
        HelpTopics.Topic topic;

        FigurePanel() {
            setOpaque(true);
            setBackground(Theme.BG);
            setBorder(BorderFactory.createLineBorder(Theme.LINE));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (topic == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            topic.figure().draw(g, getWidth(), getHeight(), world);
            g.dispose();
        }
    }
}
