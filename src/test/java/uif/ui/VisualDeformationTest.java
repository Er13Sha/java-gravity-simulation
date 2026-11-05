package uif.ui;

import uif.World;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class VisualDeformationTest {
    private static int checks;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static VisualDeformation settled(double x, double y, double z) {
        VisualDeformation d = new VisualDeformation();
        for (int i=0;i<240;i++) d.update(x,y,z,1,0.01);
        return d;
    }
    private static double aspect(double[] d) { return d[3]/d[2]; }

    public static void main(String[] args) throws Throwable {
        Camera side = new Camera(); side.yaw=0; side.pitch=0;
        double[] broadside = settled(1,0,0).project(side);
        check(aspect(broadside)<=1.08 && aspect(broadside)>1.079,"8% maximum aspect");
        check(Math.abs(broadside[2]*broadside[3]-1)<1e-12,"stable projected disk area");
        check(aspect(settled(0,0,1).project(side))==1,"face-on circle");
        double[] diagonal=settled(1,0,1).project(side);
        check(Math.abs(aspect(diagonal)-1-(aspect(broadside)-1)/2)<1e-10,"45-degree view attenuates flattening");
        VisualDeformation smooth = new VisualDeformation(); smooth.update(1,0,0,1,1.0/60);
        double initial = aspect(smooth.project(side));
        check(initial>1 && initial<1.02,"enable gradually instead of jumping to full flattening");
        double previous=initial;
        for(int i=0;i<120;i++) {
            smooth.update(1,0,0,1,1.0/60); double value=aspect(smooth.project(side));
            check(value>=previous && value<=1.08,"smooth bounded progression"); previous=value;
        }
        smooth.update(-1,0,0,1,1.0/60);
        check(smooth.project(side)[0]>0.99,"axis sign reversal must not rotate silhouette");
        smooth.update(0,1,0,1,1.0/60);
        check(smooth.project(side)[0]>0.9,"axis rotation is gradual");
        VisualDeformation one = new VisualDeformation(), many = new VisualDeformation();
        one.update(1,0,0,1,0.1);
        for(int i=0;i<10;i++) many.update(1,0,0,1,0.01);
        check(Math.abs(aspect(one.project(side))-aspect(many.project(side)))<1e-12,"response independent of frame partition");
        VisualDeformation shared=settled(1,0,0); double[] main=shared.project(side);
        Camera face=new Camera(); face.yaw=Math.PI/2; face.pitch=0;
        check(aspect(shared.project(face))==1,"independent PiP projection");
        check(Arrays.equals(main,shared.project(side)),"projection must not advance smoothing state");
        check(Math.abs(VisualDeformation.tidalStrength(0.016)-0.2)<1e-12,"previous tidal saturation now only 20% intensity");
        check(VisualDeformation.tidalStrength(0.16)>0.2 && VisualDeformation.tidalStrength(0.16)<1,"tidal response rises smoothly without early saturation");
        smooth.update(Double.NaN,0,0,1,0.01);
        for(double v:smooth.project(side)) check(Double.isFinite(v),"invalid inputs must not corrupt shape");
        World world = new World(); world.addProton(); world.addElectron();
        Settings settings = new Settings(); settings.shaders=false; settings.deform=Settings.DeformMode.TIDAL;
        ScenePanel scene = new ScenePanel(world,side,settings); scene.setSize(640,480);
        double[][] positions=world.parts.stream().map(b -> b.p.clone()).toArray(double[][]::new);
        BufferedImage physicalCheck=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=physicalCheck.createGraphics(); scene.paint(g); scene.paint(g); g.dispose();
        for(int i=0;i<positions.length;i++) check(Arrays.equals(positions[i],world.parts.get(i).p),"visual deformation must not move physical bodies");
        preview(broadside,diagonal);
        System.out.println("VisualDeformationTest: "+checks+" checks passed; preview build/deformation-preview.png");
    }

    private static void preview(double[] broadside, double[] diagonal) throws Throwable {
        int width=1040;
        BufferedImage result=new BufferedImage(width,390,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=result.createGraphics(); g.setColor(Theme.BG); g.fillRect(0,0,width,390);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif",Font.PLAIN,14)); g.setColor(Color.LIGHT_GRAY);
        String[] titles={"Было: до 30%", "Теперь: сбоку, до 8%", "Теперь: ракурс 45°", "Теперь: вдоль оси"};
        double[][] shapes={{1,0,1/Math.sqrt(1.3),Math.sqrt(1.3)},broadside,diagonal,VisualDeformation.IDENTITY};
        Method paint=ScenePanel.class.getDeclaredMethod("paintBodyDisk",Graphics2D.class,double.class,double.class,double.class,double.class,int.class,double.class,int.class,double[].class);
        paint.setAccessible(true);
        for(int i=0;i<4;i++) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(titles[i],i*260+30,25);
            paint.invoke(null,g,i*260+130.0,120.0,55.0,76.0,0x4D9FDF,0.3,0x3A85BE,shapes[i]);
        }
        g.setColor(Color.LIGHT_GRAY); g.drawString("Java2D",10,54);
        GlScene gl=GlScene.get();
        if(gl!=null) {
            BufferedImage gpu=new BufferedImage(width,170,BufferedImage.TYPE_INT_ARGB_PRE);
            int[] pixels=((DataBufferInt)gpu.getRaster().getDataBuffer()).getData();
            gl.beginBodies(4); gl.beginTrails(0,0);
            for(int i=0;i<4;i++) { double[] d=shapes[i]; gl.body(i*260+130,85,55,76,0x4D9FDF,0.3,0x3A85BE,true,d[0],d[1],d[2],d[3]); }
            gl.render(width,170,pixels,1,0,1,0,620,6,1e6,1,1f);
            g.drawImage(gpu,0,210,null); g.setColor(Color.LIGHT_GRAY); g.drawString("OpenGL",10,230);
        } else { g.drawString("OpenGL unavailable: "+GlScene.failure(),10,250); }
        g.dispose(); ImageIO.write(result,"png",new File("build/deformation-preview.png"));
    }
}
