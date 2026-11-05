package uif;

import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class MoleculeLabTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        MoleculeLab.Config h2 = new MoleculeLab.Config(World.MoleculeType.H2, 3, 104.5, 0.1, 7);
        MoleculeLab.Config water = new MoleculeLab.Config(World.MoleculeType.H2O, 7.2, 110, 0.1, 99);
        double[][] offsets = water.offsets();
        check(Math.abs(World.dist(offsets[0], offsets[1]) - 7.2) < 1e-12, "O-H distance");
        check(Math.abs(World.dist(offsets[1], offsets[2]) - 14.4 * Math.sin(Math.toRadians(55))) < 1e-12, "water angle");
        World preview = new World(new Random(123));
        preview.setG(1e-5); preview.atomERepulsion = true;
        preview.prepareMoleculeLab(water);
        check(Math.abs(preview.chamber.R() - preview.chamber.R0) < 1e-10, "camera starts at its configured radius");
        double[][] positions = preview.parts.stream().map(b -> b.p.clone()).toArray(double[][]::new);
        preview.prepareMoleculeLab(water);
        for (int i = 0; i < positions.length; i++) check(World.dist(positions[i], preview.parts.get(i).p) == 0, "seeded preparation");
        check(!preview.atomERepulsion && preview.counts().molecules() == 1, "isolated defaults and molecule bookkeeping");
        for (MoleculeLab.Config config : new MoleculeLab.Config[]{h2, water}) {
            MoleculeLab.Result first = MoleculeLab.run(config, () -> false, sample -> {});
            MoleculeLab.Result second = MoleculeLab.run(config, () -> false, sample -> {});
            check(first.equals(second), "deterministic samples and verdict");
            check(Math.abs(first.samples().getLast().time() - config.seconds()) < 1e-10, "completed model duration");
            check(first.samples().getFirst().time() == 0 && first.samples().size() >= 4, "initial and subsequent samples");
            System.out.println(config.type() + ": " + first.verdict() + "; samples=" + first.samples().size());
        }
        AtomicInteger polls = new AtomicInteger();
        try {
            MoleculeLab.run(h2, () -> polls.incrementAndGet() > 3, sample -> {});
            throw new AssertionError("cancellation ignored");
        } catch (CancellationException expected) { check(polls.get() == 4, "cancel during calculation"); }
        try {
            new MoleculeLab.Config(World.MoleculeType.H2, Double.NaN, 90, 5, 7);
            throw new AssertionError("invalid geometry accepted");
        } catch (IllegalArgumentException expected) { }
        customAssembly();
        System.out.println("MoleculeLabTest passed: geometry, repeatability, model time, isolation, cancellation, validation.");
    }

    private static void customAssembly() {
        java.util.List<MoleculeLab.Atom> input = new java.util.ArrayList<>(java.util.List.of(
                new MoleculeLab.Atom(World.AtomType.H1, -3, 0, 1, 0.1, -0.2, 0.3),
                new MoleculeLab.Atom(World.AtomType.HE4, 3, 0, -2, -0.1, 0.4, -0.3),
                new MoleculeLab.Atom(World.AtomType.H1, 0, 4, 2, 0, 0, 0),
                new MoleculeLab.Atom(World.AtomType.H1, 1, -2, 4, 0, 0, 0)));
        MoleculeLab.Assembly assembly = new MoleculeLab.Assembly(input, 0.1, 37, new MoleculeLab.Measurement(1, 0, 3));
        input.clear(); check(assembly.atoms().size() == 4, "construction snapshot is immutable");
        World world = new World(); world.prepareMoleculeLab(assembly);
        java.util.List<java.util.List<Body>> groups = new java.util.ArrayList<>(Lab.groups(world.parts).values());
        for (int i = 0; i < groups.size(); i++) {
            java.util.List<Body> nuclei = groups.get(i).stream().filter(b -> b.kind != Species.E).toList();
            check(World.dist(World.lump(nuclei).c(), assembly.atoms().get(i).position()) < 1e-10, "exact custom nucleus center");
            check(World.dist(World.lump(groups.get(i)).cv(), assembly.atoms().get(i).velocity()) < 1e-10, "exact custom COM velocity");
        }
        check(world.counts().atoms() == 4 && world.counts().molecules() == 1, "custom construction bookkeeping");
        MoleculeLab.Result first = MoleculeLab.run(assembly, () -> false, s -> {});
        check(first.equals(MoleculeLab.run(assembly, () -> false, s -> {})), "repeatable custom construction");
        check(Math.abs(first.samples().getFirst().angle() - MoleculeLab.angle(assembly.atoms().get(1), assembly.atoms().get(0), assembly.atoms().get(3))) < 1e-9,
                "chosen angle works with more than three atoms");
        MoleculeLab.Assembly single = new MoleculeLab.Assembly(java.util.List.of(assembly.atoms().getFirst()), 0.1, 7, null);
        MoleculeLab.Result singleResult = MoleculeLab.run(single, () -> false, s -> {});
        check(singleResult.samples().getLast().time() >= 0.1 - 1e-10 && Double.isNaN(singleResult.samples().getLast().angle()), "single atom experiment");
        try {
            new MoleculeLab.Assembly(java.util.List.of(assembly.atoms().getFirst(), assembly.atoms().getFirst()), 0.1, 7, null);
            throw new AssertionError("coincident atoms accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new MoleculeLab.Assembly(java.util.List.of(), 0.1, 7, null);
            throw new AssertionError("empty assembly accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new MoleculeLab.Assembly(java.util.List.of(assembly.atoms().getFirst()), 0.1, 7, new MoleculeLab.Measurement(0, 1, 2));
            throw new AssertionError("invalid angle accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new MoleculeLab.Assembly(java.util.List.of(new MoleculeLab.Atom(World.AtomType.OG294, 0, 0, 0, 0, 0, 0),
                    new MoleculeLab.Atom(World.AtomType.OG294, 5, 0, 0, 0, 0, 0)), 0.1, 7, null);
            throw new AssertionError("particle limit ignored");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Custom assembly passed: nucleus coordinates, velocities, selected angle, single atom, repeatability, invalid inputs.");
    }
}
