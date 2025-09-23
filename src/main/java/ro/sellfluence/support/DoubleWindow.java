package ro.sellfluence.support;

public class DoubleWindow {
    private double sum = 0.0;
    private final double[] window;
    private boolean full = false;
    private int pointer = 0;

    public DoubleWindow(int windowSize) {
        window = new double[windowSize];
    }

    public void add(double value) {
        if (full) {
            sum -= window[pointer];
        }
        window[pointer] = value;
        pointer++;
        if (pointer == window.length) {
            pointer = 0;
            full = true;
        }
        sum += value;
    }

    public double getSum() {
        return sum;
    }

    public boolean isFull() {
        return full;
    }
}