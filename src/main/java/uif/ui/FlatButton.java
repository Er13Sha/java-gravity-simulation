package uif.ui;

import javax.swing.JButton;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Плоская кнопка панели, как в макете: тонкая серая рамка, вторичный текст; «включённая» —
 * голубая подложка, синий текст и рамка. Внутри сегмента ({@link Seg}) — ячейка без своей рамки:
 * рамку и разделители рисует сегмент. Может нести иконку вместо текста или перед ним.
 */
final class FlatButton extends JButton {
    private boolean on;
    /** Ячейка сегмента: без рамки и скругления. */
    boolean cell;
    Icons icon;

    FlatButton(String text) {
        super(text);
        setFont(Theme.UI_BODY);
        setForeground(Theme.UI_MUTED);
        setFocusable(false); // чтобы Space не нажимал кнопку повторно
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
    }

    FlatButton(String text, String tip) {
        this(text);
        setToolTipText(tip);
    }

    void setOn(boolean on) {
        if (this.on != on) {
            this.on = on;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        String t = getText();
        int w = (t.isEmpty() ? 0 : fm.stringWidth(t)) + (icon != null ? 14 + (t.isEmpty() ? 0 : 5) : 0);
        return new Dimension(w + 16, 26);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (!isEnabled()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        int w = getWidth(), h = getHeight();
        boolean hover = isEnabled() && getModel().isRollover();
        java.awt.Color fill = on ? Theme.UI_SEL_BG : hover ? Theme.UI_HOVER : null;
        if (cell) {
            if (fill != null) {
                g.setColor(fill);
                g.fillRect(0, 0, w, h);
            }
        } else {
            g.setColor(fill != null ? fill : Theme.UI_CARD);
            g.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);
            g.setColor(on ? Theme.UI_SEL_BORDER : Theme.UI_BORDER);
            g.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
        }
        java.awt.Color fg = on ? Theme.UI_SEL_TEXT : hover ? Theme.UI_TEXT : Theme.UI_MUTED;
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        String t = getText();
        int tw = t.isEmpty() ? 0 : fm.stringWidth(t), iw = icon != null ? 14 : 0, gap = iw > 0 && tw > 0 ? 5 : 0;
        int x = (w - tw - iw - gap) / 2;
        if (icon != null) icon.paint(g, x, (h - 14) / 2.0, 14, fg);
        g.setColor(fg);
        if (tw > 0) g.drawString(t, x + iw + gap, (h - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
    }
}
