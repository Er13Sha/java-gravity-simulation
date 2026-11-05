package uif.ui;

import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.function.DoubleConsumer;

/** Ползунок с дробным шагом: значение = min + step·int; тонкая серая дорожка и янтарный бегунок, как в макете. */
final class Slider extends JSlider {
    private final double min, step;
    private boolean syncing;

    Slider(double min, double max, double step, DoubleConsumer onInput) {
        super(0, (int) Math.round((max - min) / step), 0);
        this.min = min;
        this.step = step;
        setOpaque(false);
        setFocusable(false);
        setUI(new FlatUI(this));
        addChangeListener(e -> {
            if (!syncing) onInput.accept(value());
        });
    }

    double value() {
        return min + getValue() * step;
    }

    /** Показать значение модели, не вызывая обработчик; пока ползунок тянут — не трогать. */
    void sync(double v) {
        if (getValueIsAdjusting()) return;
        int i = (int) Math.round((v - min) / step);
        if (i == getValue()) return;
        syncing = true;
        setValue(i);
        syncing = false;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, 18);
    }

    private static final class FlatUI extends BasicSliderUI {
        FlatUI(JSlider s) {
            super(s);
        }

        @Override
        public void paint(Graphics g0, JComponent c) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            super.paint(g, c);
        }

        @Override
        public void paintTrack(Graphics g) {
            Rectangle t = trackRect;
            int y = t.y + t.height / 2 - 1;
            g.setColor(Theme.UI_BORDER);
            g.fillRoundRect(t.x, y, t.width, 3, 3, 3);
        }

        @Override
        public void paintThumb(Graphics g) {
            Rectangle r = thumbRect;
            int d = 11;
            g.setColor(Theme.UI_AMBER);
            g.fillOval(r.x + r.width / 2 - d / 2, r.y + r.height / 2 - d / 2, d, d);
        }

        @Override
        public void paintFocus(Graphics g) {}

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(11, 11);
        }
    }
}
