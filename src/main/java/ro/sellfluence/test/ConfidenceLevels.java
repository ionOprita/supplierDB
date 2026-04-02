package ro.sellfluence.test;

import ro.sellfluence.support.Statistics.Estimate;

import java.util.stream.Stream;

import static ro.sellfluence.support.Statistics.estimateRate;

public class ConfidenceLevels {


    static void main() {
        Stream.of(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000).forEach(i -> {
            Estimate estimate = estimateRate(Math.round(i - i / 2.0), i, 0.70);

            var deltaLow = (estimate.lowerBound() / estimate.rate()) - 1.0;
            var deltaHigh = (estimate.upperBound() / estimate.rate()) - 1.0;
            System.out.println(i);
            System.out.printf("rate   = %.6f (%.2f%%)%n", estimate.rate(), estimate.ratePercent());
            System.out.printf("lower  = %.6f (%.2f%%) delta %.2f%%%n", estimate.lowerBound(), estimate.lowerPercent(), deltaLow * 100);
            System.out.printf("upper  = %.6f (%.2f%%) delta %.2f%%%n", estimate.upperBound(), estimate.upperPercent(), deltaHigh * 100);
        });
    }
}
