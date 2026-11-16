package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorFbeTariffsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesChangedFieldsExactDecimalsZeroAndOptionalClears() {
        var exact = "9007199254740993.1234567890123456789";
        var request = parse("""
                {"changes":[{"id":12,"expectedRevision":3,"values":{
                  "sizeCategory":"Oversize Extra-Large / Supradimensionat extra-mare",
                  "orderProcessingFeeRon":"%s","returnProcessingFeeRon":"0.00",
                  "weightRangeKg":null,"extraOrderFeeRon":""}}]}
                """.formatted(exact));

        var change = request.changes().getFirst();
        assertEquals(12, change.id());
        assertEquals(3, change.expectedRevision());
        assertEquals(exact, change.values().get("orderProcessingFeeRon"));
        assertEquals("0.00", change.values().get("returnProcessingFeeRon"));
        assertTrue(change.values().containsKey("weightRangeKg"));
        assertNull(change.values().get("weightRangeKg"));
        assertEquals("", change.values().get("extraOrderFeeRon"));
        assertThrows(UnsupportedOperationException.class, () -> request.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> change.values().put("maxLengthMm", "1"));
    }

    @Test
    void serializesEditableDecimalsAsPlainStringsWithoutChangingNulls() {
        var exact = new BigDecimal("9007199254740993.1234567890123456789");
        var source = new Tariff(12, "Oversize", new BigDecimal("1860"), new BigDecimal("750"),
                new BigDecimal("630"), new BigDecimal("4620"), new BigDecimal("300"),
                exact, BigDecimal.ZERO, new BigDecimal("110"), new BigDecimal("110"),
                null, null, null, null, null, 5);
        var json = MAPPER.readTree(MAPPER.writeValueAsString(new ProfitCalculatorFbeTariffs.Response(List.of(source))));
        var tariff = json.get("tariffs").get(0);
        assertEquals(12, tariff.get("id").asInt());
        assertEquals(5, tariff.get("revision").asLong());
        assertTrue(tariff.get("orderProcessingFeeRon").isTextual());
        assertEquals(exact.toPlainString(), tariff.get("orderProcessingFeeRon").asString());
        assertEquals("0", tariff.get("returnProcessingFeeRon").asString());
        assertEquals("1860", tariff.get("maxLengthMm").asString());
        for (var field : List.of("weightRangeKg", "extraOrderFeeRon", "extraReturnFeeRon",
                "extraRemovalFeeRon", "extraDisposalFeeRon")) {
            assertTrue(tariff.get(field).isNull(), field);
        }
    }

    @Test
    void rejectsMalformedUnknownAndUnboundedPayloads() {
        for (var body : new String[]{"", "null", "[]", "{}", "{} {}", "{", "{\"changes\":null}",
                "{\"changes\":[],\"vendorGroup\":\"S\"}",
                "{\"changes\":[{\"id\":1,\"expectedRevision\":1,\"values\":{},\"extra\":true}]}",
                "{\"changes\":[{\"id\":1,\"expectedRevision\":1,\"values\":{}}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(body), body);
        }
        assertThrows(IllegalArgumentException.class, () -> parse(null));
        assertThrows(IllegalArgumentException.class, () -> parse(" ".repeat(1_000_001)));
        var change = "{\"id\":1,\"expectedRevision\":1,\"values\":{\"orderProcessingFeeRon\":\"1\"}}";
        assertThrows(IllegalArgumentException.class, () -> parse("{\"changes\":[" +
                String.join(",", java.util.Collections.nCopies(13, change)) + "]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"changes\":[" + change + "," + change + "]}"));
        assertTrue(parse("{\"changes\":[]}").changes().isEmpty());
    }

    @Test
    void rejectsUnknownIdsInvalidRevisionsFieldsTypesAndValues() {
        for (var id : new String[]{"0", "13", "1.5", "1.0", "\"1\"", "null", "9999999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(payload(id, "1", "{\"orderProcessingFeeRon\":\"1\"}")), id);
        }
        for (var revision : new String[]{"0", "-1", "1.5", "\"1\"", "null", "9999999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(payload("1", revision, "{\"orderProcessingFeeRon\":\"1\"}")), revision);
        }
        for (var values : new String[]{"[]", "null", "{\"revision\":\"2\"}",
                "{\"orderProcessingFeeRon\":2}", "{\"sizeCategory\":true}",
                "{\"sizeCategory\":null}", "{\"sizeCategory\":\" \"}",
                "{\"maxLengthMm\":null}", "{\"maxWidthMm\":\"\"}", "{\"maxHeightMm\":\"0\"}",
                "{\"maxGirthMm\":\"-1\"}", "{\"weightBandKg\":\"0\"}", "{\"weightRangeKg\":\"0\"}",
                "{\"orderProcessingFeeRon\":null}", "{\"returnProcessingFeeRon\":\"\"}",
                "{\"removalFeeRon\":\"-0.1\"}", "{\"disposalFeeRon\":\"1e3\"}",
                "{\"extraOrderFeeRon\":\"-1\"}", "{\"extraReturnFeeRon\":\"NaN\"}",
                "{\"extraRemovalFeeRon\":\"Infinity\"}", "{\"extraDisposalFeeRon\":\"1,5\"}",
                "{\"orderProcessingFeeRon\":\"" + "1".repeat(65) + "\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(payload("1", "1", values)), values);
        }
    }

    private static String payload(String id, String revision, String values) {
        return "{\"changes\":[{\"id\":" + id + ",\"expectedRevision\":" + revision + ",\"values\":" + values + "}]}";
    }

    private static ProfitCalculatorFbeTariffs.Request parse(String body) {
        return ProfitCalculatorFbeTariffs.parse(MAPPER, body);
    }
}
