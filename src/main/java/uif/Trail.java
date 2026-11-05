package uif;

/** След тела: кольцевой буфер последних положений, i = 0 — самая старая точка. */
public final class Trail {
    private final int capacity;
    private double[] x, y, z; // память — только когда след впервые пишется
    private int head, count;

    Trail(int capacity) {
        this.capacity = capacity;
    }

    void add(double[] p) {
        if (x == null) {
            x = new double[capacity];
            y = new double[capacity];
            z = new double[capacity];
        }
        x[head] = p[0];
        y[head] = p[1];
        z[head] = p[2];
        head = (head + 1) % x.length;
        if (count < x.length) count++;
    }

    public int size() {
        return count;
    }

    /** Сбросить след (тело больше не на круговой орбите / не отображается). */
    void clear() {
        head = 0;
        count = 0;
    }

    public double x(int i) { return x[index(i)]; }
    public double y(int i) { return y[index(i)]; }
    public double z(int i) { return z[index(i)]; }

    private int index(int i) {
        return (head - count + i + x.length) % x.length;
    }
}
