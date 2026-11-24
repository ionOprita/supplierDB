package ro.sellfluence.app;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportProfitCalculatorReplacementRatesTest {
    @Test
    void takesNumericColumnJByExactPnkAndPreservesZero() {
        var rows = List.of(
                sourceRow("ABC", new BigDecimal("0.012345678901234567")),
                sourceRow("abc", 0),
                List.<Object>of("MISSING", "category"),
                sourceRow("EMPTY", ""),
                List.<Object>of());

        assertEquals(Map.of("ABC", new BigDecimal("0.012345678901234567"), "abc", BigDecimal.ZERO),
                ImportProfitCalculatorReplacementRates.parse(rows));
    }

    @Test
    void rejectsAmbiguousOrUnreadableSnapshotsBeforeAnyReplacement() {
        var valid = sourceRow("ABC", 0.01);
        for (var invalid : List.of(sourceRow("ABC", ""), sourceRow("", 0.02), sourceRow(42, 0.02),
                sourceRow("#REF!", ""), sourceRow("XYZ", "0.02"), sourceRow("XYZ", false),
                sourceRow("XYZ", "#N/A"), sourceRow("XYZ", -0.01), sourceRow("XYZ", 1.01),
                sourceRow("XYZ", Double.NaN))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ImportProfitCalculatorReplacementRates.parse(List.of(valid, invalid)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ImportProfitCalculatorReplacementRates.parse(List.of(List.<Object>of("ABC"))));
    }

    private static List<Object> sourceRow(Object pnk, Object rate) {
        return List.of(pnk, "category", false, 0.01, 0.02, 0.03, rate);
    }
}
