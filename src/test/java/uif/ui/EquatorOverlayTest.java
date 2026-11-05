package uif.ui;

import uif.World;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.io.File;
import java.util.List;
import java.util.Arrays;

public final class EquatorOverlayTest {
    private static int checks;
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
    private static BufferedImage overlay(Camera view,EquatorOverlay.Disk... disks) {
        BufferedImage image=new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); EquatorOverlay.paint(g,view,List.of(disks),240,240); g.dispose(); return image;
    }
    private static boolean marked(BufferedImage image,int x,int y) { return (image.getRGB(x,y)&0xFFFFFF)!=0; }
    private static int count(BufferedImage image,int x0,int y0,int x1,int y1) {
        int count=0; for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) if(marked(image,x,y)) count++; return count;
    }
    public static void main(String[] args) throws Exception {
        Camera view=new Camera(); view.yaw=0; view.pitch=0;
        double[] center={120,120,10,1};
        EquatorOverlay.Disk edge=new EquatorOverlay.Disk(center,70,VisualDeformation.IDENTITY,new double[]{0,1,0},1);
        BufferedImage edgeImage=overlay(view,edge);
        check(count(edgeImage,40,118,201,123)>80,"edge-on equator crosses the disk");
        BufferedImage face=overlay(view,new EquatorOverlay.Disk(center,70,VisualDeformation.IDENTITY,new double[]{0,0,1},1));
        check(!marked(face,120,120) && count(face,187,117,194,124)>0,"face-on equator is a circle at the limb");
        BufferedImage tilted=overlay(view,new EquatorOverlay.Disk(center,70,VisualDeformation.IDENTITY,new double[]{0,1,1},1));
        check(count(tilted,40,40,201,119)>30,"visible front arc is drawn");
        check(count(tilted,40,123,201,201)==0,"back arc is hidden");
        EquatorOverlay.Disk occluder=new EquatorOverlay.Disk(new double[]{140,120,5,1},30,VisualDeformation.IDENTITY,null,1);
        BufferedImage occluded=overlay(view,edge,occluder);
        check(count(occluded,130,117,151,124)==0,"nearer body hides the farther equator");
        check(marked(occluded,80,120),"unoccluded arc remains visible");
        Settings settings=new Settings();
        check(!settings.showEquator,"equator off by default"); settings.showEquator=true; settings.defaults();
        check(!settings.showEquator,"reset switches the overlay off");
        SwingUtilities.invokeAndWait(() -> {
            try {
                World world=new World(); world.addProton(); world.time=12;
                Object bodies=world.parts; double[] p=world.parts.get(0).p.clone(),v=world.parts.get(0).v.clone();
                Camera camera=new Camera(); camera.camR=0.7; camera.pitch=0.4; camera.yaw=0.5;
                settings.shaders=false;
                ControlPanel controls=new ControlPanel(world,camera,settings);
                Field field=ControlPanel.class.getDeclaredField("equatorBtn"); field.setAccessible(true);
                JButton button=(JButton)field.get(controls);
                check(button.getText().equals("Экватор"),"requested button label");
                ScenePanel scene=new ScenePanel(world,camera,settings); scene.setSize(360,340);
                BufferedImage off=paint(scene);
                button.doClick(); check(settings.showEquator,"button enables overlay");
                BufferedImage on=paint(scene);
                int different=0; for(int y=0;y<340;y++) for(int x=0;x<360;x++) if(off.getRGB(x,y)!=on.getRGB(x,y)) different++;
                check(different>30,"overlay is visible in the actual scene renderer");
                check(world.parts==bodies && world.parts.size()==1 && world.time==12,"view toggle preserves the scene and model time");
                check(Arrays.equals(p,world.parts.get(0).p) && Arrays.equals(v,world.parts.get(0).v),"rendering preserves positions and velocities");
                check(!world.tidalEquator,"visual equator must not enable tidal forces");
                BufferedImage gpu=null;
                if(GlScene.get()!=null) { settings.shaders=true; gpu=paint(scene); }
                BufferedImage preview=new BufferedImage(1080,380,BufferedImage.TYPE_INT_RGB);
                Graphics2D g=preview.createGraphics(); g.setColor(Theme.BG); g.fillRect(0,0,1080,380);
                g.drawImage(off,0,40,null); g.drawImage(on,360,40,null); if(gpu!=null) g.drawImage(gpu,720,40,null);
                g.setFont(new Font("SansSerif",Font.PLAIN,14)); g.setColor(Color.LIGHT_GRAY);
                g.drawString("Java2D: выключено",20,25); g.drawString("Java2D: Экватор",380,25);
                g.drawString(gpu==null?"OpenGL недоступен":"OpenGL: Экватор",740,25); g.dispose();
                ImageIO.write(preview,"png",new File("build/equator-preview.png"));
                button.doClick(); check(!settings.showEquator,"button disables overlay");
            } catch(Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("EquatorOverlayTest: "+checks+" checks passed; build/equator-preview.png");
    }
    private static BufferedImage paint(ScenePanel scene) {
        BufferedImage image=new BufferedImage(360,340,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); scene.paint(g); g.dispose(); return image;
    }
}
