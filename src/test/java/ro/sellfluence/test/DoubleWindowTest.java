package ro.sellfluence.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ro.sellfluence.support.DoubleWindow;

import java.util.Arrays;
import java.util.random.RandomGenerator;

public class DoubleWindowTest {
    private RandomGenerator random = RandomGenerator.getDefault();

    @Test
    public void testWindow() {
        for (int n = 0; n < 100_000; n++) {
            var windowSize = random.nextInt(1, 1000);
            var dataSize = random.nextInt(1, 1000);
            var testData = new double[dataSize];
            for (int i = 0; i < dataSize; i++) {
                testData[i] = random.nextDouble(100.0);
            }
            var window = new DoubleWindow(windowSize);
            for (var value : testData) {
                window.add(value);
            }
            double expected;
            if (dataSize > windowSize) {
                expected = Arrays.stream(testData).skip(dataSize - windowSize).sum();
            } else {
                expected = Arrays.stream(testData).sum();
            }
            Assertions.assertEquals(expected, window.getSum(), 1e-6);
        }
    }
}