package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorOverridesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesExactProductCodesDecimalStringsAndExplicitNullClears() {
        var request = parse("""
                {"vendorGroup":" S ","category":" Trimmers ","changes":[
                  {"productCode":" exact code ","expectedRevision":12,
                   "values":{"salePriceRon":"0.00","commissionRate":"0.14","usdRonRate":"4.567891","fulfillmentSource":null}}
                ]}
                """);
        assertEquals("S", request.vendorGroup());
        assertEquals("Trimmers", request.category());
        var change = request.changes().getFirst();
        assertEquals(" exact code ", change.productCode());
        assertEquals(12, change.expectedRevision());
        assertEquals("0.00", change.values().get("salePriceRon"));
        assertEquals("0.14", change.values().get("commissionRate"));
        assertTrue(change.values().containsKey("fulfillmentSource"));
        assertNull(change.values().get("fulfillmentSource"));
    }

    @Test
    void rejectsMalformedUnknownAndUnboundedRequests() {
        for (var json : new String[]{"", "null", "[]", "{}", "{} {}", "{", """
                {"vendorGroup":"S","category":"Trimmers","changes":null}
                """, """
                {"vendorGroup":"S","category":"Trimmers","changes":[],"unexpected":true}
                """, """
                {"vendorGroup":"S","category":"Trimmers","changes":[
                  {"productCode":"code","expectedRevision":1.5,"values":{"salePriceRon":"1"}}]}
                """}) {
            assertThrows(IllegalArgumentException.class, () -> parse(json));
        }
        assertThrows(IllegalArgumentException.class, () -> parse(" ".repeat(1_000_001)));
    }

    @Test
    void rejectsDuplicateProductsAndInvalidOverrideValues() {
        for (var values : new String[]{"{}", "{\"salePriceRon\":1}", "{\"salePriceRon\":\"-1\"}",
                "{\"commissionRate\":\"14\"}", "{\"usdRonRate\":\"0\"}",
                "{\"fulfillmentSource\":\"Unknown\"}", "{\"otherField\":\"1\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> parse("""
                    {"vendorGroup":"S","category":"Trimmers","changes":[
                      {"productCode":"code","expectedRevision":0,"values":%s}]}
                    """.formatted(values)));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {"vendorGroup":"S","category":"Trimmers","changes":[
                  {"productCode":"code","expectedRevision":0,"values":{"salePriceRon":"1"}},
                  {"productCode":"code","expectedRevision":0,"values":{"salePriceRon":"2"}}]}
                """));
    }

    private static ProfitCalculatorOverrides.Request parse(String json) {
        return ProfitCalculatorOverrides.parse(MAPPER, json);
    }
}
