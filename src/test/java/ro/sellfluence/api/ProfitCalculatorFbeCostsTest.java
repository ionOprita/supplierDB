package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfitCalculatorFbeCostsTest {
    @ParameterizedTest
    @CsvSource({
            "100,100,10,1",
            "200,150,10,1",
            "200.001,150,10,3",
            "330,230,25,3",
            "1860,750,630,12",
            "1000,700,700,12",
            "2000,150,10,1",
            "2000,2000,2000,0",
            "0,0,0,1",
            "150,200,10,3"
    })
    void preservesTheWorksheetClassifierIncludingTiesNonfittingTiersAndUnmatchedAxes(
            String length, String width, String height, int expectedId) {
        var grid = sourceDimensions();
        Collections.reverse(grid); // Row ID, not list position, controls tied thresholds.
        var selected = ProfitCalculatorFbeCosts.selectTariff(bd(length), bd(width), bd(height), grid);
        if (expectedId == 0) assertNull(selected);
        else assertEquals(expectedId, selected.id());
    }

    @ParameterizedTest
    @CsvSource({
            "0,3.5,1.8,2,2",
            "2000,3.5,1.8,2,2",
            "2000.1,4.0,2.1,4,4",
            "3000,4.0,2.1,4,4",
            "3000.1,4.5,2.4,6,6",
            "4500,5.0,2.7,8,8"
    })
    void convertsGramsAndChargesEveryStartedExcessKilogram(
            String weightG, String processing, String returns, String withdrawal, String disposal) {
        var costs = ProfitCalculatorFbeCosts.calculate(bd("100"), bd("100"), bd("10"), bd(weightG),
                List.of(tariff(1, 200, 150, 10, "2")));
        assertEquals(bd(processing), costs.processingCostRon());
        assertEquals(bd(returns), costs.returnCostRon());
        assertEquals(bd(withdrawal), costs.withdrawalCostRon());
        assertEquals(bd(disposal), costs.disposalCostRon());
    }

    @Test
    void preservesPrecisionAndOnlyBlanksTheFeeWhoseRequiredSurchargeIsUnknown() {
        var tariff = new Tariff(1, "Test", bd("200"), bd("150"), bd("10"), bd("520"), bd("2"),
                bd("3.123456789"), bd("5"), BigDecimal.ZERO, bd("7"), null,
                bd("0.0000000001"), null, BigDecimal.ZERO, bd("0.123456789"), 1);
        var costs = ProfitCalculatorFbeCosts.calculate(bd("100"), bd("100"), bd("10"), bd("3000.001"),
                List.of(tariff));
        assertEquals(bd("3.1234567892"), costs.processingCostRon());
        assertNull(costs.returnCostRon());
        assertEquals(BigDecimal.ZERO, costs.withdrawalCostRon());
        assertEquals(bd("7.246913578"), costs.disposalCostRon());
    }

    @Test
    void baseFeesDoNotNeedSurchargesAndNeitherGirthNorWeightRangeConstrainsSelection() {
        var tariff = new Tariff(1, "Test", bd("200"), bd("150"), bd("10"), bd("1"), bd("2"),
                bd("3.5"), bd("1.8"), bd("2"), bd("2"), bd("1"), null, null, null, null, 1);
        var costs = ProfitCalculatorFbeCosts.calculate(bd("200"), bd("150"), bd("10"), bd("2000"),
                List.of(tariff));
        assertEquals(bd("3.5"), costs.processingCostRon());
        assertEquals(bd("1.8"), costs.returnCostRon());
        assertEquals(bd("2"), costs.withdrawalCostRon());
        assertEquals(bd("2"), costs.disposalCostRon());
        assertUnknown(ProfitCalculatorFbeCosts.calculate(bd("200"), bd("150"), bd("10"), bd("2000.1"),
                List.of(tariff)));
    }

    @Test
    void missingMeasurementsOrNoSelectedTariffKeepEveryFeeUnknown() {
        var grid = List.of(tariff(1, 200, 150, 10, "2"));
        BigDecimal[][] measurements = {
                {null, bd("100"), bd("10"), bd("1000")},
                {bd("100"), null, bd("10"), bd("1000")},
                {bd("100"), bd("100"), null, bd("1000")},
                {bd("100"), bd("100"), bd("10"), null}
        };
        for (var values : measurements) {
            assertUnknown(ProfitCalculatorFbeCosts.calculate(values[0], values[1], values[2], values[3], grid));
        }
        assertUnknown(ProfitCalculatorFbeCosts.calculate(bd("100"), bd("100"), bd("10"), bd("1000"), List.of()));
        assertUnknown(ProfitCalculatorFbeCosts.calculate(bd("2000"), bd("2000"), bd("2000"), bd("1000"), grid));
    }

    private static List<Tariff> sourceDimensions() {
        // Source dimension thresholds and weight bands. Synthetic equal fees keep classification tests focused.
        int[][] dimensions = {
                {200, 150, 10, 2}, {330, 230, 25, 2}, {330, 230, 50, 2}, {340, 170, 120, 2},
                {450, 340, 260, 2}, {610, 460, 460, 2}, {1200, 600, 600, 2}, {1200, 800, 800, 5},
                {1860, 750, 630, 10}, {1860, 750, 630, 60}, {1860, 750, 630, 90}, {1860, 750, 630, 300}
        };
        var result = new ArrayList<Tariff>();
        for (int i = 0; i < dimensions.length; i++) {
            var shape = dimensions[i];
            result.add(tariff(i + 1, shape[0], shape[1], shape[2], Integer.toString(shape[3])));
        }
        return result;
    }

    private static Tariff tariff(int id, int length, int width, int height, String weightBand) {
        return new Tariff(id, "Tier " + id, BigDecimal.valueOf(length), BigDecimal.valueOf(width),
                BigDecimal.valueOf(height), bd("1"), bd(weightBand), bd("3.5"), bd("1.8"), bd("2"), bd("2"),
                bd("1"), bd("0.5"), bd("0.3"), bd("2"), bd("2"), 1);
    }

    private static void assertUnknown(ProfitCalculatorFbeCosts.Costs costs) {
        assertNull(costs.processingCostRon());
        assertNull(costs.returnCostRon());
        assertNull(costs.withdrawalCostRon());
        assertNull(costs.disposalCostRon());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
