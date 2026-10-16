package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.sellfluence.db.GMV;
import ro.sellfluence.db.POInfo;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class APIGMVPeriodTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate END = LocalDate.of(2026, 2, 15);

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "-12.34, -12.34",
            "-12.345, -12.34",
            "-12.355, -12.36",
            "9007199254740993.125, 9007199254740993.12",
            "9007199254740993.135, 9007199254740993.14",
            "1E+3, 1000.00"
    })
    void serializesExactTwoDecimalAmountsAsStrings(String value, String expected) {
        var response = API.GMVPeriodResponse.create("product-code", START, END, new BigDecimal(value));
        var mapper = new ObjectMapper();
        var jsonText = mapper.writeValueAsString(response);
        var json = mapper.readTree(jsonText);

        assertEquals(5, json.size());
        assertEquals("product-code", json.get("productCode").asString());
        assertEquals("2026-01-15", json.get("startDate").asString());
        assertEquals("2026-02-15", json.get("endDate").asString());
        assertEquals(expected, json.get("gmv").asString());
        assertTrue(json.get("contributions").isArray());
        assertEquals(0, json.get("contributions").size());
        assertTrue(jsonText.contains("\"gmv\":\"" + expected + "\""), "GMV must be a JSON string");
    }

    @Test
    void preservesExactProductCodesAndSingleDayPeriodsInTheResponse() {
        var code = " product/code with spaces?&é ";
        var day = LocalDate.of(2024, 2, 29);

        assertEquals(new API.GMVPeriodResponse(code, "2024-02-29", "2024-02-29", "0.00"),
                API.GMVPeriodResponse.create(code, day, day, BigDecimal.ZERO));
    }

    @Test
    void serializesCompactContributionsWithSignedQuantitiesAndExactAmounts() {
        var finalized = snapshot("original", 4, "123.45");
        var storno = snapshot("returned", 5, "1000.00");
        var zero = snapshot("fully-returned", 5, "0.29");
        var breakdown = new GMV.Breakdown(new BigDecimal("-1629.65"), List.of(
                new GMV.Contribution(finalized, 3, new BigDecimal("370.35")),
                new GMV.Contribution(storno, -2, new BigDecimal("-2000.00")),
                new GMV.Contribution(zero, 0, new BigDecimal("0.00"))
        ));

        var response = API.GMVPeriodResponse.create("exact-code", START, END, breakdown);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        var rows = json.get("contributions");

        assertEquals("-1629.65", json.get("gmv").asString());
        assertEquals(3, rows.size());
        assertEquals("original", rows.get(0).get("orderId").asString());
        assertEquals("2026-01-15", rows.get(0).get("orderDate").asString());
        assertEquals("Finalized", rows.get(0).get("status").asString());
        assertEquals(3, rows.get(0).get("quantity").asInt());
        assertTrue(rows.get(0).get("unitPrice").isString());
        assertEquals("123.45", rows.get(0).get("unitPrice").asString());
        assertEquals("370.35", rows.get(0).get("gmv").asString());
        assertEquals("Storno deduction", rows.get(1).get("status").asString());
        assertEquals(-2, rows.get(1).get("quantity").asInt());
        assertEquals("-2000.00", rows.get(1).get("gmv").asString());
        assertEquals("Storno", rows.get(2).get("status").asString());
        assertEquals(0, rows.get(2).get("quantity").asInt());
        assertEquals("0.00", rows.get(2).get("gmv").asString());
        assertEquals(6, rows.get(0).size());
        assertEquals("Remaining after storno", API.GMVContributionResponse.create(
                new GMV.Contribution(zero, 2, new BigDecimal("0.58"))).status());
    }

    @Test
    void keepsLargeContributionPricesAndAmountsAsPlainDecimalStrings() {
        var source = snapshot("large", 4, "9007199254740993.12");
        var row = API.GMVContributionResponse.create(new GMV.Contribution(
                source, 1, new BigDecimal("9007199254740993.12")));
        var json = new ObjectMapper().writeValueAsString(row);

        assertTrue(json.contains("\"unitPrice\":\"9007199254740993.12\""));
        assertTrue(json.contains("\"gmv\":\"9007199254740993.12\""));
    }

    @Test
    void missingProductCodesDoNotAccessTheDatabase() throws Exception {
        var api = new API(null);

        for (var productCode : new String[]{null, "", " \t\r\n"}) {
            assertTrue(api.getGMVForPeriod(productCode, START, END).isEmpty());
        }
    }

    private static POInfo snapshot(String orderId, int status, String price) {
        return new POInfo(orderId, status, START, status, 10, "Product", 3, 5, 2, new BigDecimal(price));
    }
}
