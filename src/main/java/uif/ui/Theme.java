package uif.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

/**
 * Цвета и шрифты — тёмная тема макета 4ж (Claude design): панели «surface-2» #1a1a19, сцена
 * «surface-1» #151515, текст #f0efec / #c3c2b7 / #898781, рамки — 10–20 % белого, выбранное —
 * синяя подложка #032042 с текстом #6da7ec, ползунки и их значения — янтарные #db9300.
 */
final class Theme {
    private Theme() {}

    // ---------- сцена ----------
    static final Color BG = new Color(0x151515);               // surface-1
    static final Color PANEL = new Color(0x1A1A19);            // surface-2
    static final Color LINE = new Color(255, 255, 255, 26);    // сетка пола: 10 % белого
    static final Color TEXT = new Color(0xF0EFEC);             // сводка: text-primary
    static final Color MUTED = new Color(0xC3C2B7);            // text-secondary
    static final Color LABEL = new Color(0xC3C2B7);            // подписи частиц
    static final Color BTN = new Color(0x20201F);              // surface-3
    static final Color BTN_HOVER = new Color(0x2A2A29);
    static final Color CARD = new Color(0x1A1A19, true);
    static final Color ACCENT = new Color(0x6DA7EC);           // accent-300

    static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    static final Font BOLD = FONT.deriveFont(Font.BOLD);
    /** Ширина выпадающей панели темы; «Расчёт» — шире под чипы «считать на» (Barnes–Hut). */
    static final int PANEL_W = 330;
    static final int PANEL_W_CALC = 460;

    // ---------- интерфейс ----------
    static final Color UI_BG = new Color(0x1A1A19);            // фон полосы тем и панелей (surface-2)
    static final Color UI_CARD = new Color(0x1A1A19);
    static final Color UI_TEXT = new Color(0xF0EFEC);          // основной текст (gray-50)
    static final Color UI_MUTED = new Color(0xC3C2B7);         // вторичный текст (gray-200)
    static final Color UI_HINT = new Color(0x898781);          // заголовки групп (gray-400)
    static final Color UI_LINE = new Color(255, 255, 255, 26); // разделители: 10 % белого
    static final Color UI_BORDER = new Color(255, 255, 255, 51); // рамки кнопок и панели: 20 % белого
    static final Color UI_HOVER = new Color(255, 255, 255, 13);  // под мышью: 5 % белого
    static final Color UI_SEL_BG = new Color(0x032042);        // выбранное: accent-800
    static final Color UI_SEL_TEXT = new Color(0x6DA7EC);      // accent-300
    static final Color UI_SEL_BORDER = new Color(0x0D366B);    // accent-700
    static final Color UI_AMBER = new Color(0xDB9300);         // бегунок ползунка (warning-300)
    static final Color UI_ACCENT = new Color(0xDB9300);        // значения ползунков
    static final Font UI_FONT = new Font("Segoe UI", Font.PLAIN, 13);  // надписи на сцене
    static final Font UI_BODY = UI_FONT.deriveFont(12f);       // строки, кнопки, темы
    static final Font UI_SMALL = UI_FONT.deriveFont(11f);      // пояснения и подсказки
    static final Font UI_HEAD = UI_FONT.deriveFont(11f);       // заголовки групп: строчные, серые
    static final Font UI_MONO = new Font("Consolas", Font.PLAIN, 11);  // значения ползунков

    static Color rgba(int rgb, double a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
    }

    /** β и 1 + β: 0, 0.50, 12.6, 3.2e+8 — коротко при любой величине. */
    static String beta(double b) {
        return b == 0 ? "0" : b < 0.01 || b >= 1000 ? exp(b, 1) : fixed(b, b < 10 ? 2 : 1);
    }

    /** Как toFixed в JS. */
    static String fixed(double x, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", x);
    }

    /** Как toExponential в JS: 1.2345e+2, а не 1.2345e+02. */
    static String exp(double x, int digits) {
        if (!Double.isFinite(x)) return String.valueOf(x);
        String s = String.format(Locale.ROOT, "%." + digits + "e", x);
        int i = s.indexOf('e');
        String mant = s.substring(0, i);
        char sign = s.charAt(i + 1);
        String digitsPart = s.substring(i + 2).replaceFirst("^0+(?=\\d)", "");
        return mant + "e" + sign + digitsPart;
    }
}
