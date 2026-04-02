package ro.sellfluence.support;

import org.apache.commons.statistics.distribution.NormalDistribution;

public class Statistics {
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
     * Converts a two-sided confidence level into the corresponding z critical value.
     * <p>
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


    /**
     * Computes the observed return rate and a Wilson confidence interval.
     *
     * @param part            part (e.g. returned products), must satisfy 0 <= returned <= shipped
     * @param total           total amount (e.g. shipped products), must be > 0
     * @param confidenceLevel e.g. 0.95 for a 95% confidence interval
     * @return estimate containing observed rate and confidence interval bounds
     */
    public static Estimate estimateRate(long part, long total, double confidenceLevel) {
        validateInputs(total, part, confidenceLevel);

        double n = total;
        double pHat = (double) part / n;

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
}