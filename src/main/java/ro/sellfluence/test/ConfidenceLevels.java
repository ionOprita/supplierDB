package ro.sellfluence.test;

import org.apache.commons.statistics.distribution.NormalDistribution;

import java.util.stream.Stream;

public class ConfidenceLevels {

    public record Estimate(
            double rate,
            double lowerBound,
            double upperBound
    ) {
        public double ratePercent() {
            return rate * 100.0;
        }

        public double lowerPercent() {
            return lowerBound * 100.0;
        }

        public double upperPercent() {
            return upperBound * 100.0;
        }
    }

    /**
     * Computes the observed return rate and a Wilson confidence interval.
     *
     * @param shipped total shipped products (p), must be > 0
     * @param returned total returned products (r), must satisfy 0 <= returned <= shipped
     * @param confidenceLevel e.g. 0.95 for a 95% confidence interval
     * @return estimate containing observed rate and confidence interval bounds
     */
    public static Estimate estimateReturnRate(long shipped, long returned, double confidenceLevel) {
        validateInputs(shipped, returned, confidenceLevel);

        double n = shipped;
        double pHat = (double) returned / n;

        double z = zValue(confidenceLevel);
        double z2 = z * z;

        double denominator = 1.0 + z2 / n;
        double center = (pHat + z2 / (2.0 * n)) / denominator;
        double margin = (z / denominator) *
                Math.sqrt((pHat * (1.0 - pHat) / n) + (z2 / (4.0 * n * n)));

        double lower = Math.max(0.0, center - margin);
        double upper = Math.min(1.0, center + margin);

        return new Estimate(pHat, lower, upper);
    }

    /**
     * Converts a two-sided confidence level into the corresponding z critical value.
     *
     * Example:
     * confidenceLevel = 0.95
     * alpha = 0.05
     * quantile = 0.975
     * z = Phi^{-1}(0.975) ~= 1.96
     */
    private static double zValue(double confidenceLevel) {
        double alpha = 1.0 - confidenceLevel;
        double quantile = 1.0 - alpha / 2.0;

        return NormalDistribution.of(0, 1)
                .inverseCumulativeProbability(quantile);
    }

    private static void validateInputs(long shipped, long returned, double confidenceLevel) {
        if (shipped <= 0) {
            throw new IllegalArgumentException("shipped must be > 0");
        }
        if (returned < 0 || returned > shipped) {
            throw new IllegalArgumentException("returned must satisfy 0 <= returned <= shipped");
        }
        if (confidenceLevel <= 0.0 || confidenceLevel >= 1.0) {
            throw new IllegalArgumentException("confidenceLevel must be between 0 and 1");
        }
    }

    public static void main(String[] args) {
        Stream.of(10,20,50,100,200,500,1000,2000,5000,10000).forEach(i -> {
            Estimate estimate = estimateReturnRate(i, Math.round(i-i/2.0), 0.70);

            var deltaLow = (estimate.lowerBound() / estimate.rate()) - 1.0;
            var deltaHigh = (estimate.upperBound() / estimate.rate()) - 1.0;
            System.out.println(i);
            System.out.printf("rate   = %.6f (%.2f%%)%n", estimate.rate(), estimate.ratePercent());
            System.out.printf("lower  = %.6f (%.2f%%) delta %.2f%%%n", estimate.lowerBound(), estimate.lowerPercent(), deltaLow*100);
            System.out.printf("upper  = %.6f (%.2f%%) delta %.2f%%%n", estimate.upperBound(), estimate.upperPercent(), deltaHigh*100);
        });
    }
}
