package uif;

import java.util.*;

public final class LabRegressionTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("legacy")) legacy();
        if (args.length == 0 || args[0].equals("geometry")) geometry();
        System.out.println("LabRegressionTest passed.");
    }

    private static void legacy() {
        for (Lab.Experiment ex : Lab.all()) {
            World dirty = new World(new Random(321));
            dirty.parts.add(dirty.makeBody(Species.P, -1, new double[]{1000,0,0}, new double[3]));
            dirty.atomERepulsion = true; dirty.kuo.beta = 0.9; dirty.time = 42;
            ex.setup(dirty);
            World fresh = new World(new Random(99)); ex.setup(fresh);
            check(dirty.time == 0 && !dirty.atomERepulsion && dirty.kuo.beta == fresh.kuo.beta, "legacy experiment must reset time and inherited forces");
            check(dirty.parts.size() == fresh.parts.size(), "same particles");
            for (int i=0;i<dirty.parts.size();i++) {
                check(Arrays.equals(dirty.parts.get(i).p, fresh.parts.get(i).p), "same seeded coordinates");
                check(Arrays.equals(dirty.parts.get(i).v, fresh.parts.get(i).v), "same seeded velocities");
            }
            check(Arrays.equals(dirty.chamber.c, fresh.chamber.c), "same chamber center");
        }
        Lab.MoleculeInChamber nitrogen = new Lab.MoleculeInChamber(); nitrogen.type = World.MoleculeType.N2;
        World world = new World(); nitrogen.setup(world);
        check(Math.abs(world.chamber.R() - world.chamber.R0) < 1e-10, "actual initial chamber radius must equal configured R0");
        while (world.time < 0.005 - 1e-10) Lab.stepToward(world, 0.005);
        check(Math.abs(world.time - 0.005) < 1e-10 && world.lag() == 0, "specified model duration must finish without uncomputed lag");
        check(new Lab.ElectronOrbit().run().details().contains("5.000"), "orbit report must state its actual model duration");
    }

    private static void geometry() {
        for (long seed : new long[]{7,99}) {
            World world = new World();
            MoleculeLab.Config config = new MoleculeLab.Config(World.MoleculeType.H2O, 7.2, 104.5, 0.1, seed);
            world.prepareMoleculeLab(config);
            List<List<Body>> groups = new ArrayList<>(Lab.groups(world.parts).values());
            double[][] initial = config.offsets();
            for (int i=0;i<groups.size();i++) {
                double[] center = World.lump(groups.get(i).stream().filter(b -> b.kind != Species.E).toList()).c();
                check(World.dist(center, initial[i]) < 1e-10, "template nucleus must match requested coordinates, atom " + i);
            }
        }
    }
}
