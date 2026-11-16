package ro.sellfluence.db;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Change;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorFbeTariffTableTest {
    @Test
    void mergesChangedFieldsWithoutLosingExactDecimalsAndKeepsZeroSeparateFromNull() {
        var original = tariff();
        var values = new LinkedHashMap<String, String>();
        values.put("sizeCategory", " \tChanged category\u2003");
        values.put("orderProcessingFeeRon", "9007199254740993.1234567890123456789");
        values.put("returnProcessingFeeRon", "0");
        values.put("weightRangeKg", " ");
        values.put("extraReturnFeeRon", null);
        var changed = original.applyChanges(values);

        assertEquals("Changed category", changed.sizeCategory());
        assertEquals(new BigDecimal("9007199254740993.1234567890123456789"), changed.orderProcessingFeeRon());
        assertEquals(BigDecimal.ZERO, changed.returnProcessingFeeRon());
        assertNull(changed.weightRangeKg());
        assertNull(changed.extraReturnFeeRon());
        assertEquals(original.maxGirthMm(), changed.maxGirthMm());
        assertEquals(original.extraOrderFeeRon(), changed.extraOrderFeeRon());
        assertEquals(original.id(), changed.id());
        assertEquals(original.revision(), changed.revision());
        assertEquals(new BigDecimal("3.5"), original.orderProcessingFeeRon());
    }

    @Test
    void serializesEveryEditableDecimalAsAStringWithNumericIdentityAndRevision() {
        var original = tariff();
        var values = new LinkedHashMap<String, String>();
        values.put("orderProcessingFeeRon", "9007199254740993.1234567890123456789");
        values.put("weightRangeKg", null);
        var changed = original.applyChanges(values);
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(changed));

        assertTrue(json.get("id").isIntegralNumber());
        assertTrue(json.get("revision").isIntegralNumber());
        assertEquals(17, json.size());
        assertEquals("9007199254740993.1234567890123456789", json.get("orderProcessingFeeRon").asString());
        assertTrue(json.get("weightRangeKg").isNull());
        for (var key : changed.jsonValues().keySet()) {
            if (!List.of("id", "revision", "weightRangeKg").contains(key)) {
                assertTrue(json.get(key).isTextual(), key);
            }
        }
    }

    @Test
    void rejectsUnknownIdentitiesFieldsDuplicatesAndInvalidRequiredOrOptionalValues() {
        for (var id : new int[]{0, 13}) {
            assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorFbeTariffTable.validateChanges(
                    List.of(new Change(id, 1, Map.of("sizeCategory", "Name")))));
        }
        assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorFbeTariffTable.validateChanges(
                List.of(new Change(1, 0, Map.of("sizeCategory", "Name")))));
        var same = new Change(1, 1, Map.of("sizeCategory", "Name"));
        assertThrows(IllegalArgumentException.class,
                () -> ProfitCalculatorFbeTariffTable.validateChanges(List.of(same, same)));
        for (var values : List.of(Map.of("unknown", "1"), Map.of("maxLengthMm", "0"),
                Map.of("maxHeightMm", ""), Map.of("weightBandKg", "-2"),
                Map.of("weightRangeKg", "0"), Map.of("orderProcessingFeeRon", "-1"),
                Map.of("extraDisposalFeeRon", "-0.1"), Map.of("extraReturnFeeRon", "1e3"),
                Map.of("returnProcessingFeeRon", "NaN"), Map.of("maxWidthMm", "1".repeat(65)),
                Map.of("extraOrderFeeRon", "." + "0".repeat(62) + "1"),
                Map.of("sizeCategory", " \t"), Map.of("sizeCategory", "A".repeat(201)))) {
            assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorFbeTariffTable.validateChanges(
                    List.of(new Change(1, 1, values))), values.toString());
        }
        var nulls = new LinkedHashMap<String, String>();
        nulls.put(null, "1");
        assertThrows(IllegalArgumentException.class, () -> tariff().applyChanges(nulls));
        nulls.clear();
        nulls.put("orderProcessingFeeRon", null);
        assertThrows(IllegalArgumentException.class, () -> tariff().applyChanges(nulls));
    }

    private static Tariff tariff() {
        return new Tariff(1, "Packable Small / Ambalabil mic", decimal("200"), decimal("150"),
                decimal("10"), decimal("520"), decimal("2"), decimal("3.5"), decimal("1.8"),
                decimal("2"), decimal("2"), decimal("5"), decimal("0.5"), decimal("0.3"),
                decimal("2"), decimal("2"), 1);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
