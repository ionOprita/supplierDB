package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfitCalculatorSettingsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GENERAL = """
            {"vendorGroup":" K ","section":"general","expectedRevision":0,"values":{
              "geniusEasyboxRate":"0.35","monthlyCapitalRate":"0","monthlyInflationRate":"-0.005",
              "averageDaysPerMonth":"30.416666666666668","vatRate":"0.21","incomeTaxRate":null,
              "profitTaxRate":"0.16","dividendTaxRate":"0.16","unaccountedStornoRate":"0"}}
            """;

    @Test
    void acceptsExactDecimalsSignedInflationAndBlankAmountForNamedEntry() {
        var change = ProfitCalculatorSettings.parse(MAPPER, GENERAL).change();
        assertEquals("K", change.vendorGroup());
        assertEquals(0, change.expectedRevision());
        assertEquals(new BigDecimal("30.416666666666668"), change.values().get("averageDaysPerMonth"));
        assertEquals(new BigDecimal("-0.005"), change.values().get("monthlyInflationRate"));
        assertEquals(BigDecimal.ZERO, change.values().get("monthlyCapitalRate"));
        assertNull(change.values().get("incomeTaxRate"));

        var items = ProfitCalculatorSettings.parse(MAPPER, """
                {"vendorGroup":"S","section":"employees","expectedRevision":1,
                 "items":[{"label":"Open position","amountRon":null},{"label":"Allocation","amountRon":"0.00"}]}
                """).change().items();
        assertNull(items.getFirst().amountRon());
        assertEquals("0.00", items.getLast().amountRon());
    }

    @Test
    void rejectsComputedFieldsWrongTypesAndInvalidRanges() {
        for (var body : new String[]{GENERAL + "{}", GENERAL.replace("general", "interest"),
                GENERAL.replace("\"vatRate\":\"0.21\"", "\"vatRate\":0.21"),
                GENERAL.replace("\"vatRate\":\"0.21\"", "\"vatRate\":\"1.01\""),
                GENERAL.replace("\"vatRate\":\"0.21\"", "\"previousMonthUnits\":\"100\""),
                GENERAL.replace("\"averageDaysPerMonth\":\"30.416666666666668\"", "\"averageDaysPerMonth\":\"0\""),
                GENERAL.replace("\"monthlyInflationRate\":\"-0.005\"", "\"monthlyInflationRate\":\"-1\""),
                GENERAL.replace("\"expectedRevision\":0", "\"expectedRevision\":-1"),
                """
                {"vendorGroup":"S","section":"miscellaneous","expectedRevision":1,
                 "items":[{"label":"Rent","amountRon":"-1"}]}
                """}) {
            assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorSettings.parse(MAPPER, body), body);
        }
    }
}
