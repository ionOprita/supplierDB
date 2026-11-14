package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.Overrides;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfitCalculatorOverridesTest {
    @Test
    void appliesOnlyChangedFieldsAndDistinguishesClearingFromZero() {
        var original = new Overrides(new BigDecimal("149.99"), new BigDecimal("0.14"),
                new BigDecimal("10.25"), new BigDecimal("0.5"), new BigDecimal("4.5555"), "eMAG");
        var changes = new LinkedHashMap<String, String>();
        changes.put("salePriceRon", null);
        changes.put("commissionRate", "0");
        changes.put("transportPriceUsd", " ");

        var updated = original.applyChanges(changes);

        assertNull(updated.salePriceRon());
        assertEquals(BigDecimal.ZERO, updated.commissionRate());
        assertNull(updated.transportPriceUsd());
        assertEquals(original.purchasePriceUsd(), updated.purchasePriceUsd());
        assertEquals(original.usdRonRate(), updated.usdRonRate());
        assertEquals("eMAG", updated.fulfillmentSource());
        assertEquals(new BigDecimal("149.99"), original.salePriceRon());
    }

    @Test
    void acceptsBoundaryValuesAndTrimsFulfillmentChoices() {
        var values = Overrides.empty().applyChanges(Map.of("salePriceRon", "0", "commissionRate", "1",
                "purchasePriceUsd", ".25", "transportPriceUsd", "0.00", "usdRonRate", "4.123456789",
                "fulfillmentSource", " Minulescu "));

        assertEquals(BigDecimal.ONE, values.commissionRate());
        assertEquals(new BigDecimal("0.25"), values.purchasePriceUsd());
        assertEquals(new BigDecimal("4.123456789"), values.usdRonRate());
        assertEquals("Minulescu", values.fulfillmentSource());
        assertNull(values.applyChanges(Map.of("fulfillmentSource", "")).fulfillmentSource());
    }

    @Test
    void rejectsInvalidAmountsAndUnknownFieldsWithoutInterpretingThemAsDefaults() {
        for (var field : new String[]{"salePriceRon", "purchasePriceUsd", "transportPriceUsd", "commissionRate"}) {
            assertThrows(IllegalArgumentException.class, () -> Overrides.empty().applyChanges(Map.of(field, "-0.01")));
            for (var invalid : new String[]{"NaN", "Infinity", "1e999999999", "1,20", "0".repeat(65), "wrong"}) {
                assertThrows(IllegalArgumentException.class, () -> Overrides.empty().applyChanges(Map.of(field, invalid)));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> Overrides.empty().applyChanges(Map.of("commissionRate", "1.01")));
        assertThrows(IllegalArgumentException.class,
                () -> Overrides.empty().applyChanges(Map.of("usdRonRate", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> Overrides.empty().applyChanges(Map.of("usdRonRate", "-1")));
        assertThrows(IllegalArgumentException.class,
                () -> Overrides.empty().applyChanges(Map.of("fulfillmentSource", "Other")));
        assertThrows(IllegalArgumentException.class,
                () -> Overrides.empty().applyChanges(Map.of("emagSalePriceRon", "20")));
    }
}
