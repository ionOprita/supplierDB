package ro.sellfluence.db;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmagRomaniaVatTest {
    @Test
    void convertsVerifiedRomaniaIdentifiersInsteadOfTreatingThemAsPercentages() {
        assertGross("199.99", "165.28", 9);
        assertGross("111.00", "100", 10);
        assertGross("100.00", "100", 5);
        assertGross("0.00", "0", 9);
    }

    @Test
    void keepsStoredNetPrecisionUntilTheFinalHalfEvenRounding() {
        assertGross("0.02", "0.014", 9);
        assertGross("0.02", "0.025", 5);
        assertGross("0.04", "0.035", 5);
        assertGross("24.05", "19.87654321", 9);
    }

    @Test
    void leavesUnknownOrMissingVatAndMissingPricesUnavailable() {
        assertNull(EmagRomaniaVat.grossPrice(new BigDecimal("165.28"), null));
        assertNull(EmagRomaniaVat.grossPrice(new BigDecimal("165.28"), 1));
        assertNull(EmagRomaniaVat.grossPrice(new BigDecimal("165.28"), 21));
        assertNull(EmagRomaniaVat.grossPrice(null, 9));
    }

    private static void assertGross(String expected, String netPrice, int vatId) {
        assertEquals(new BigDecimal(expected), EmagRomaniaVat.grossPrice(new BigDecimal(netPrice), vatId));
    }
}
