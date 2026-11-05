package uif.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Контурные иконки полосы тем — нарисованы линиями в сетке 24×24, как иконки макета (Tabler):
 * штрих 2 единицы, скруглённые концы. Без файлов и шрифтов.
 */
enum Icons {
    WORLD, ATOM, ARROWS, FUNCTION, DASHED, CPU, EYE, PAUSE, PLAY, REFRESH, RELOAD, CHEVRON;

    /** Нарисовать иконку размером size с левым верхним углом в (x, y). */
    void paint(Graphics2D g0, double x, double y, double size, Color c) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        g.scale(size / 24, size / 24);
        g.setColor(c);
        g.setStroke(this == DASHED
                ? new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{3.2f, 3.9f}, 0f)
                : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Shape s : shapes()) g.draw(s);
        if (this == ATOM) g.fill(new Ellipse2D.Double(10.9, 10.9, 2.2, 2.2));
        g.dispose();
    }

    private List<Shape> shapes() {
        List<Shape> l = new ArrayList<>();
        switch (this) {
            case WORLD -> {
                l.add(new Ellipse2D.Double(3, 3, 18, 18));
                l.add(new Line2D.Double(3.6, 9, 20.4, 9));
                l.add(new Line2D.Double(3.6, 15, 20.4, 15));
                l.add(quad(11.5, 3, 5.5, 12, 11.5, 21));
                l.add(quad(12.5, 3, 18.5, 12, 12.5, 21));
            }
            case ATOM -> {
                Ellipse2D e = new Ellipse2D.Double(2.5, 8.9, 19, 6.2); // три орбиты, как в Tabler
                for (int k = 0; k < 3; k++) l.add(AffineTransform.getRotateInstance(k * Math.PI / 3, 12, 12).createTransformedShape(e));
            }
            case ARROWS -> {
                l.add(new Line2D.Double(21, 17, 3, 17));
                l.add(poly(6, 10, 3, 7, 6, 4));
                l.add(new Line2D.Double(3, 7, 21, 7));
                l.add(poly(18, 20, 21, 17, 18, 14));
            }
            case FUNCTION -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 19);
                p.quadTo(3, 21, 5, 21);
                p.curveTo(7, 21, 7, 17, 8, 12);
                p.curveTo(9, 7, 9, 3, 11, 3);
                p.quadTo(13, 3, 13, 5);
                l.add(p);
                l.add(new Line2D.Double(5, 12, 11, 12));
                l.add(new Line2D.Double(15, 12, 21, 18));
                l.add(new Line2D.Double(15, 18, 21, 12));
            }
            case DASHED -> l.add(new Ellipse2D.Double(3, 3, 18, 18));
            case CPU -> {
                l.add(new RoundRectangle2D.Double(5, 5, 14, 14, 2, 2));
                l.add(new RoundRectangle2D.Double(9, 9, 6, 6, 0.5, 0.5));
                double[][] pins = {{3, 10, 5, 10}, {3, 14, 5, 14}, {10, 3, 10, 5}, {14, 3, 14, 5},
                        {21, 10, 19, 10}, {21, 14, 19, 14}, {14, 21, 14, 19}, {10, 21, 10, 19}};
                for (double[] q : pins) l.add(new Line2D.Double(q[0], q[1], q[2], q[3]));
            }
            case EYE -> {
                l.add(new Ellipse2D.Double(10, 10, 4, 4));
                Path2D p = new Path2D.Double();
                p.moveTo(21, 12);
                p.curveTo(18.6, 16, 15.6, 18, 12, 18);
                p.curveTo(8.4, 18, 5.4, 16, 3, 12);
                p.curveTo(5.4, 8, 8.4, 6, 12, 6);
                p.curveTo(15.6, 6, 18.6, 8, 21, 12);
                l.add(p);
            }
            case PAUSE -> {
                l.add(new RoundRectangle2D.Double(6, 5, 4, 14, 2, 2));
                l.add(new RoundRectangle2D.Double(14, 5, 4, 14, 2, 2));
            }
            case PLAY -> {
                Path2D p = poly(7, 4, 7, 20, 20, 12);
                p.closePath();
                l.add(p);
            }
            case REFRESH -> {
                l.add(new Arc2D.Double(4, 4, 16, 16, 7, 151, Arc2D.OPEN));
                l.add(poly(4, 5, 4, 9, 8, 9));
                l.add(new Arc2D.Double(4, 4, 16, 16, 187, 151, Arc2D.OPEN));
                l.add(poly(20, 19, 20, 15, 16, 15));
            }
            case RELOAD -> {
                double r = 8, cx = 12, cy = 12;
                l.add(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 120, 300, Arc2D.OPEN));
                double end = Math.toRadians(60), ex = cx + r * Math.cos(end), ey = cy - r * Math.sin(end);
                double ang = Math.atan2(-Math.cos(end), -Math.sin(end)); // касательная в конце дуги
                for (int s = -1; s <= 1; s += 2) {
                    double a = ang + s * Math.toRadians(26);
                    l.add(new Line2D.Double(ex, ey, ex - 4.2 * Math.cos(a), ey - 4.2 * Math.sin(a)));
                }
            }            case CHEVRON -> l.add(poly(6, 9, 12, 15, 18, 9));
        }
        return l;
    }

    private static Path2D quad(double x0, double y0, double cx, double cy, double x1, double y1) {
        Path2D p = new Path2D.Double();
        p.moveTo(x0, y0);
        p.quadTo(cx, cy, x1, y1);
        return p;
    }

    private static Path2D poly(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }
}
