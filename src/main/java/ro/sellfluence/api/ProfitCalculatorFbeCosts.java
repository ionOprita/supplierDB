package ro.sellfluence.api;

import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

/** Literal worksheet FBE rules, kept separate so the classifier can change after author confirmation. */
final class ProfitCalculatorFbeCosts {
    private ProfitCalculatorFbeCosts() {
    }

    record Costs(BigDecimal processingCostRon, BigDecimal returnCostRon,
                 BigDecimal withdrawalCostRon, BigDecimal disposalCostRon) {
        static Costs unknown() {
            return new Costs(null, null, null, null);
        }
    }

    static Costs calculate(BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm,
                           BigDecimal weightG, List<Tariff> tariffs) {
        if (lengthMm == null || widthMm == null || heightMm == null || weightG == null) {
            return Costs.unknown();
        }
        var tariff = selectTariff(lengthMm, widthMm, heightMm, tariffs);
        if (tariff == null) return Costs.unknown();
        var difference = weightG.movePointLeft(3).subtract(tariff.weightBandKg());
        var excessKg = difference.signum() > 0 ? difference.setScale(0, RoundingMode.CEILING) : BigDecimal.ZERO;
        return new Costs(fee(tariff.orderProcessingFeeRon(), tariff.extraOrderFeeRon(), excessKg),
                fee(tariff.returnProcessingFeeRon(), tariff.extraReturnFeeRon(), excessKg),
                fee(tariff.removalFeeRon(), tariff.extraRemovalFeeRon(), excessKg),
                fee(tariff.disposalFeeRon(), tariff.extraDisposalFeeRon(), excessKg));
    }

    static Tariff selectTariff(BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm,
                               List<Tariff> tariffs) {
        if (lengthMm == null || widthMm == null || heightMm == null) return null;
        // The source picks each dimension's nearest upper threshold, then the maximum row ID.
        // Ties and unmatched axes deliberately retain the worksheet's questionable assignments.
        int id = Math.max(axisId(lengthMm, tariffs, Tariff::maxLengthMm),
                Math.max(axisId(widthMm, tariffs, Tariff::maxWidthMm),
                        axisId(heightMm, tariffs, Tariff::maxHeightMm)));
        return id == 0 ? null : tariffs.stream().filter(tariff -> tariff.id() == id).findFirst().orElse(null);
    }

    private static int axisId(BigDecimal measurement, List<Tariff> tariffs,
                              Function<Tariff, BigDecimal> threshold) {
        BigDecimal nearest = null;
        int id = 0;
        for (var tariff : tariffs) {
            var limit = threshold.apply(tariff);
            if (limit.compareTo(measurement) < 0) continue;
            int comparison = nearest == null ? -1 : limit.compareTo(nearest);
            if (comparison < 0) {
                nearest = limit;
                id = tariff.id();
            } else if (comparison == 0) {
                id = Math.max(id, tariff.id());
            }
        }
        return id;
    }

    private static BigDecimal fee(BigDecimal base, BigDecimal surcharge, BigDecimal excessKg) {
        if (excessKg.signum() == 0) return base;
        return surcharge == null ? null : base.add(excessKg.multiply(surcharge));
    }
}
