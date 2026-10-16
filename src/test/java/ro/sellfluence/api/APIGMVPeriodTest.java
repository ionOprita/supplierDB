package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

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

        assertEquals(4, json.size());
        assertEquals("product-code", json.get("productCode").asString());
        assertEquals("2026-01-15", json.get("startDate").asString());
        assertEquals("2026-02-15", json.get("endDate").asString());
        assertEquals(expected, json.get("gmv").asString());
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
    void missingProductCodesDoNotAccessTheDatabase() throws Exception {
        var api = new API(null);

        for (var productCode : new String[]{null, "", " \t\r\n"}) {
            assertTrue(api.getGMVForPeriod(productCode, START, END).isEmpty());
        }
    }
}
