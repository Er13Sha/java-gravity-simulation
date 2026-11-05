package uif.ui;

import uif.World;
import uif.MoleculeLab;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.awt.event.MouseEvent;

public final class MoleculeLabPanelTest {
    private static MoleculeLabPanel panel;
    private static Object field(String name) throws Exception {
        Field f = MoleculeLabPanel.class.getDeclaredField(name); f.setAccessible(true); return f.get(panel);
    }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container sub) layout(sub); }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                World world = new World(); Settings settings = new Settings();
                panel = new MoleculeLabPanel(world, new Camera(), settings);
                ((JComboBox<?>) field("type")).setSelectedItem(World.MoleculeType.H2O);
                ((JSpinner) field("seconds")).setValue(0.1);
                ((JButton) field("prepare")).doClick();
                if (!settings.paused || world.counts().atoms() != 3) throw new AssertionError("preview preparation");
                ((JButton) field("run")).doClick();
                if (((JButton) field("run")).isEnabled()) throw new AssertionError("run lock");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] done = {false};
        while (!done[0] && System.nanoTime() < deadline) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> { try { done[0] = ((JButton) field("run")).isEnabled(); } catch (Exception ex) { throw new RuntimeException(ex); } });
        }
        if (!done[0]) throw new AssertionError("UI worker timeout");
        SwingUtilities.invokeAndWait(() -> {
            try {
                String text = ((JTextArea) field("report")).getText();
                if (!text.contains("Время модели 0.100")) throw new AssertionError("missing result: " + text);
                panel.setSize(1160, 840); layout(panel);
                BufferedImage image = new BufferedImage(1160, 840, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
                ImageIO.write(image, "png", new File("build/molecule-lab.png"));
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        SwingUtilities.invokeAndWait(() -> {
            try {
                ((JButton)field("transfer")).doClick();
                AssemblyEditor editor = (AssemblyEditor)field("editor");
                if (editor.table.getRowCount() != 3) throw new AssertionError("template transfer");
                editor.load(new MoleculeLab.Assembly(java.util.List.of(
                        new MoleculeLab.Atom(World.AtomType.H1, -3, 0, 0, 0, 0, 0),
                        new MoleculeLab.Atom(World.AtomType.H1, 3, 0, 0, 0, 0, 0),
                        new MoleculeLab.Atom(World.AtomType.H1, 0, 4, 0, 0, 0, 0),
                        new MoleculeLab.Atom(World.AtomType.H1, 0, 0, 4, 0, 0, 0)), 0.1, 7, new MoleculeLab.Measurement(1, 0, 3)));
                if (!new MoleculeLab.Measurement(1, 0, 3).equals(editor.snapshot(0.1, 7).measurement())) throw new AssertionError("angle selection restored");
                editor.table.setValueAt(0.25, 3, 7);
                editor.table.setValueAt(Double.NaN, 0, 2);
                try { editor.snapshot(0.1, 7); throw new AssertionError("invalid cell accepted"); }
                catch (IllegalArgumentException expected) { }
                editor.table.setValueAt(-3.0, 0, 2);
                layout(panel);
                BufferedImage scratch = new BufferedImage(1160, 840, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scratch.createGraphics(); panel.paint(graphics); graphics.dispose();
                MoleculeLab.Atom before = editor.snapshot(0.1, 7).atoms().get(3);
                double[] p = editor.view.project(3);
                int x = (int)p[0], y = (int)p[1];
                editor.view.dispatchEvent(new MouseEvent(editor.view, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
                editor.view.dispatchEvent(new MouseEvent(editor.view, MouseEvent.MOUSE_DRAGGED, 1, MouseEvent.BUTTON1_DOWN_MASK, x + 25, y - 10, 0, false, MouseEvent.NOBUTTON));
                editor.view.dispatchEvent(new MouseEvent(editor.view, MouseEvent.MOUSE_RELEASED, 2, 0, x + 25, y - 10, 1, false, MouseEvent.BUTTON1));
                MoleculeLab.Atom after = editor.snapshot(0.1, 7).atoms().get(3);
                if (World.dist(before.position(), after.position()) < 0.01 || before.z() == after.z() || after.vz() != 0.25)
                    throw new AssertionError("3D drag must change position, preserve velocity");
                click(editor, "+ атом");
                if (editor.table.getRowCount() != 5) throw new AssertionError("add atom");
                click(editor, "Удалить");
                if (editor.table.getRowCount() != 4) throw new AssertionError("remove atom");
                editor.table.setRowSelectionInterval(0, 2);
                ((JButton)field("run")).doClick();
                if (editor.table.isEnabled()) throw new AssertionError("editor must lock during experiment");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        done[0] = false; deadline = System.nanoTime() + 30_000_000_000L;
        while (!done[0] && System.nanoTime() < deadline) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> { try { done[0] = ((JButton)field("run")).isEnabled(); } catch (Exception ex) { throw new RuntimeException(ex); } });
        }
        if (!done[0]) throw new AssertionError("custom UI worker timeout");
        SwingUtilities.invokeAndWait(() -> {
            try {
                String text = ((JTextArea)field("report")).getText();
                if (!text.contains("Свободная сборка (4 атомов)") || !text.contains("Время модели 0.100")) throw new AssertionError("custom report: " + text);
                layout(panel);
                BufferedImage image = new BufferedImage(1160, 840, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                ImageIO.write(image, "png", new File("build/molecule-lab-assembly.png"));
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("MoleculeLabPanelTest passed: preparation, worker completion, report, rendered panel.");
    }

    private static void click(Container parent, String text) {
        JButton button = find(parent, text);
        if (button == null) throw new AssertionError("Button missing: " + text);
        button.doClick();
    }
    private static JButton find(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container child) { JButton found = find(child, text); if (found != null) return found; }
        }
        return null;
    }
}
