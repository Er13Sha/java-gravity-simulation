package uif;

import javax.swing.SwingUtilities;

/** Точка входа: окно модели W = Q·N в 3D. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(uif.ui.App::start);
    }
}
