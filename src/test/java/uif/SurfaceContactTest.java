package uif;

import java.util.*;

public final class SurfaceContactTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static World falling(boolean r1, boolean impermeable, double distance) {
        World w = new World(new Random(7)); w.restoreDefaults(); w.setG(1);
        w.softRepulsion=r1; w.impenetrable=impermeable;
        w.parts=new ArrayList<>(List.of(new Body(Species.CUSTOM,1000,10,new double[3],new double[3]),
                new Body(Species.CUSTOM,1,0.1,new double[]{distance,0,0},new double[3])));
        return w;
    }
    public static void main(String[] args) {
        for (int mode=0;mode<3;mode++) {
            World w=falling(mode!=1,mode!=0,11);
            double acceleration=w.accelerate()[1][0];
            check(acceleration<0,"small body must fall inward above actual surface, mode="+mode+", a="+acceleration);
            double energy=w.energy().E();
            World free=falling(false,false,11);
            check(Math.abs(energy-free.energy().E())<1e-9,"no shell potential above physical surface");
            w=falling(mode!=1,mode!=0,12);
            Body big=w.parts.get(0),small=w.parts.get(1);
            double surface=big.rad+small.rad;
            double[] initialCenter=w.centerOfMass();
            boolean touched=false;
            for(int i=0;i<600;i++) {
                w.step(1.0/120);
                double d=World.dist(big.p,small.p);
                check(d>=surface-1e-9,"body must not penetrate large body");
                if(d<=surface+1e-7) touched=true;
                if(touched) check(Math.abs(d-surface)<1e-7,"body must remain on the actual surface");
            }
            check(touched,"small body must reach surface instead of floating above it");
            check(World.dist(w.centerOfMass(),initialCenter)<1e-9,"contact must preserve center of mass");
            check(w.energy().momentum()<1e-8,"contact must preserve momentum");
            check(Math.abs(small.v[0]-big.v[0])<1e-8,"impact must remove normal closing velocity");
        }
        World off=falling(false,false,10.1); off.step(0.001);
        check(World.dist(off.parts.get(0).p,off.parts.get(1).p)<10.1,"without contact switches baseline bodies can penetrate");
        World slide=falling(true,false,10.09); slide.setG(0);
        Body big=slide.parts.get(0),small=slide.parts.get(1);
        small.v[0]=-2; small.v[1]=1;
        double initialMomentum=slide.energy().momentum(),initialEnergy=slide.energy().E();
        slide.step(1e-5);
        check(small.v[1]>0.99,"contact must preserve tangential motion rather than glue bodies together");
        check(Math.abs(slide.energy().momentum()-initialMomentum)<1e-9,"oblique impact preserves pair momentum");
        check(slide.energy().E()<initialEnergy,"normal impact energy is dissipated");
        System.out.println("SurfaceContactTest passed: physical contact, landing, no hovering, no penetration, momentum, opt-in.");
    }
}
