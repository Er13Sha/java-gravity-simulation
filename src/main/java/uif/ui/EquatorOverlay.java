package uif.ui;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Surface marking shared by Java2D and OpenGL; hidden arcs and eclipsed bodies stay hidden. */
final class EquatorOverlay {
    record Disk(double[] center, double radius, double[] deformation, double[] normal, double alpha) {}

    static void paint(Graphics2D graphics, Camera view, List<Disk> disks, int width, int height) {
        List<Disk> ordered = new ArrayList<>(disks);
        ordered.sort(Comparator.comparingDouble((Disk d) -> d.center()[2]).reversed());
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.15f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            for(Disk disk:ordered) {
                if(disk.normal()==null || disk.radius()<4) continue;
                Area visible=new Area(new Rectangle(0,0,width,height));
                for(Disk other:ordered) {
                    if(other.center()[2]>=disk.center()[2]) continue;
                    double reach=1.1*(disk.radius()+other.radius());
                    if(Math.abs(disk.center()[0]-other.center()[0])>reach || Math.abs(disk.center()[1]-other.center()[1])>reach) continue;
                    visible.subtract(new Area(outline(other)));
                }
                Shape clip=g.getClip(); g.clip(visible);
                g.setColor(new Color(233,206,140,(int)(255*Math.max(0,Math.min(1,disk.alpha())))));
                g.draw(frontArc(disk,view)); g.setClip(clip);
            }
        } finally { g.dispose(); }
    }

    private static Shape outline(Disk d) {
        double[] shape=d.deformation();
        AffineTransform t=new AffineTransform();
        t.translate(d.center()[0],d.center()[1]);
        t.concatenate(new AffineTransform(shape[0],shape[1],-shape[1],shape[0],0,0));
        t.scale(shape[2],shape[3]);
        return t.createTransformedShape(new Ellipse2D.Double(-d.radius(),-d.radius(),2*d.radius(),2*d.radius()));
    }

    private static Path2D frontArc(Disk disk, Camera view) {
        double[] n=disk.normal();
        double norm=Math.hypot(n[0],Math.hypot(n[1],n[2]));
        Path2D path=new Path2D.Double();
        if(!(norm>1e-12) || !Double.isFinite(norm)) return path;
        n=new double[]{n[0]/norm,n[1]/norm,n[2]/norm};
        double[] helper=Math.abs(n[1])<0.9 ? new double[]{0,1,0} : new double[]{1,0,0};
        double[] u=cross(n,helper); double length=Math.hypot(u[0],Math.hypot(u[1],u[2]));
        if(!(length>1e-12) || !Double.isFinite(length)) return path;
        for(int i=0;i<3;i++) u[i]/=length;
        double[] v=cross(n,u);
        u=view.turn(u[0],u[1],u[2]); v=view.turn(v[0],v[1],v[2]);
        boolean pen=false;
        for(int i=0;i<=128;i++) {
            double angle=2*Math.PI*i/128,cos=Math.cos(angle),sin=Math.sin(angle);
            double z=cos*u[2]+sin*v[2];
            if(z>1e-8) { pen=false; continue; }
            double x=disk.radius()*(cos*u[0]+sin*v[0]),y=-disk.radius()*(cos*u[1]+sin*v[1]);
            double[] d=disk.deformation();
            double along=x*d[0]+y*d[1],across=-x*d[1]+y*d[0];
            double px=disk.center()[0]+along*d[2]*d[0]-across*d[3]*d[1];
            double py=disk.center()[1]+along*d[2]*d[1]+across*d[3]*d[0];
            if(pen) path.lineTo(px,py); else { path.moveTo(px,py); pen=true; }
        }
        return path;
    }

    private static double[] cross(double[] a,double[] b) {
        return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
}
