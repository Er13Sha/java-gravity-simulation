package uif.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.*;

public final class LabUiRegressionTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Object field(Object obj, String name) throws Exception {
        Field f=obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj);
    }
    private static void invoke(Object obj, String name) throws Exception {
        Method m=obj.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(obj);
    }
    private static final class Manager extends DefaultKeyboardFocusManager {
        void send(Component source, int id, int code) {
            KeyEvent e=new KeyEvent(source,id,System.currentTimeMillis(),0,code,(char)code);
            for(KeyEventDispatcher d:getKeyEventDispatchers()) d.dispatchKeyEvent(e);
        }
    }
    public static void main(String[] args) throws Exception {
        ControlPanel[] controls={null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                Manager manager=new Manager(); KeyboardFocusManager.setCurrentKeyboardFocusManager(manager);
                App app=new App(); invoke(app,"installKeys"); app.world().addProton();
                JButton contact=(JButton)field(app.controls(),"impenBtn");
                Object bodies=app.world().parts;
                uif.Body body=app.world().parts.get(0);
                double[] position=body.p.clone(),velocity=body.v.clone();
                app.world().time=12.5; app.settings().paused=true; app.settings().speed=4;
                contact.doClick();
                check(app.world().impenetrable || app.world().softRepulsion,"global surface contact must turn on");
                check(app.world().parts==bodies && app.world().parts.size()==1 && app.world().parts.get(0)==body,"toggle must preserve existing bodies");
                check(java.util.Arrays.equals(position,body.p) && java.util.Arrays.equals(velocity,body.v),"toggle must not reposition or relaunch bodies");
                check(app.world().time==12.5 && app.settings().paused && app.settings().speed==4,"toggle must preserve model time, pause and speed");
                app.world().softRepulsion=true;
                contact.doClick();
                check(!app.world().impenetrable && !app.world().softRepulsion,"global surface contact must fully turn off");
                check(app.world().parts==bodies && app.world().time==12.5,"turning off must not reset the scene");
                app.settings().paused=false; app.settings().speed=1;
                for(Component input:new Component[]{new JTextField("7"),new JTable(1,1),new JComboBox<>(new String[]{"H"}),new JButton("run"),new JList<>(new String[]{"H"}),new JSlider()}) {
                    manager.send(input,KeyEvent.KEY_PRESSED,KeyEvent.VK_R);
                    manager.send(input,KeyEvent.KEY_RELEASED,KeyEvent.VK_R);
                    manager.send(input,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE);
                    manager.send(input,KeyEvent.KEY_RELEASED,KeyEvent.VK_SPACE);
                    check(app.world().parts.size()==1 && !app.settings().paused,"input must not reset/pause the scene");
                }
                manager.send(app.scene(),KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE);
                manager.send(app.scene(),KeyEvent.KEY_RELEASED,KeyEvent.VK_SPACE);
                check(app.settings().paused,"scene Space must still work");
                check(app.scene().isFocusable(),"scene must accept focus after leaving an input");
                manager.send(app.scene(),KeyEvent.KEY_PRESSED,KeyEvent.VK_W);
                manager.send(new JTextField(),KeyEvent.KEY_RELEASED,KeyEvent.VK_W);
                check(((java.util.Set<?>)field(app,"keys")).isEmpty(),"release outside scene clears flight state");
                controls[0]=app.controls();
                Field index=ControlPanel.class.getDeclaredField("labIdx"); index.setAccessible(true); index.setInt(controls[0],2);
                invoke(controls[0],"runLab");
                check(!app.settings().paused && app.settings().speed==1,"running experiment must leave pause at normal speed");
                check(app.camera().camR>30,"camera must show the full surface-landing experiment");
                index.setInt(controls[0],0); invoke(controls[0],"syncLab");
            } catch(Exception ex){throw new RuntimeException(ex);}
        });
        long end=System.nanoTime()+30_000_000_000L; boolean[] ready={false};
        while(!ready[0] && System.nanoTime()<end) {
            Thread.sleep(30);
            SwingUtilities.invokeAndWait(() -> {try{ready[0]=((JButton)field(controls[0],"labRunBtn")).isEnabled();}catch(Exception ex){throw new RuntimeException(ex);}});
        }
        check(ready[0],"worker must finish and enable Run");
        SwingUtilities.invokeAndWait(() -> {
            try {
                check(((JLabel)field(controls[0],"labVerdict")).getText().isBlank(),"obsolete verdict must not reach new selection");
                check(((JTextArea)field(controls[0],"labDetails")).getText().isEmpty(),"obsolete details must not reach new selection");
            }catch(Exception ex){throw new RuntimeException(ex);}
        });
        System.out.println("LabUiRegressionTest passed: inputs isolated, scene shortcuts preserved, release handled, stale result discarded.");
    }
}
