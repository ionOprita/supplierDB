package ro.sellfluence.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPerformanceMockDataTest {
    @Test
    void matchesTheDashboardColumnOrderAndGroupBoundaries() {
        var response = new API(null).getProductPerformance();
        assertTrue(response.mock());
        assertEquals(List.of("overview", "total", "auto", "broad", "exact", "product"),
                response.groups().stream().map(ProductPerformanceMockData.ColumnGroup::key).toList());
        assertEquals(List.of(10, 13, 15, 15, 15, 15),
                response.groups().stream().map(group -> group.columns().size()).toList());
        assertEquals(List.of("Week", "Stock", "GMV 30", "Clicks", "Sales Price", "Conv.",
                        "Perform.", "Average price / W.", "# rev.", "rating"),
                response.groups().getFirst().columns().stream()
                        .map(ProductPerformanceMockData.Column::label).toList());

        var commonAdsLabels = List.of("Imp.", "Clicks", "Spend", "Sales", "Total Units Sold",
                "CTR", "Conv.", "CPC", "ROAS", "ACOS", "TACOS");
        for (var group : response.groups().subList(1, 6)) {
            assertEquals(commonAdsLabels, group.columns().subList(0, 11).stream()
                    .map(ProductPerformanceMockData.Column::label).toList());
        }
        assertEquals(List.of("% Clicks Ads of Total", "% Sales Ads of Total"),
                response.groups().get(1).columns().subList(11, 13).stream()
                        .map(ProductPerformanceMockData.Column::label).toList());
        var shareNames = List.of("Auto", "Broad", "Exact", "Prod.");
        for (int groupIndex = 2; groupIndex < 6; groupIndex++) {
            var shareName = shareNames.get(groupIndex - 2);
            assertEquals(List.of("% Imp. " + shareName + " of Tot. Ads",
                            "% Clicks " + shareName + " of Tot. Ads",
                            "% Spend " + shareName + " of Tot. Ads",
                            "% Sales " + shareName + " of Tot. Ads"),
                    response.groups().get(groupIndex).columns().subList(11, 15).stream()
                            .map(ProductPerformanceMockData.Column::label).toList());
        }

        var keys = response.groups().stream().flatMap(group -> group.columns().stream())
                .map(ProductPerformanceMockData.Column::key).toList();
        assertEquals(83, keys.size());
        assertEquals(83, new HashSet<>(keys).size());
        response.rows().forEach(row -> assertEquals(new HashSet<>(keys), row.values().keySet()));
    }

    @Test
    void serializesTypedMockValuesAndWeeklyDatesWithoutADatabase() {
        var response = new API(null).getProductPerformance();
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.get("mock").asBoolean());
        assertEquals("SELL", json.get("product").get("label").asString());
        assertEquals("S. 53 - Trimmer 2.0", json.get("product").get("name").asString());
        assertEquals("DDHSVQMBM", json.get("product").get("pnk").asString());
        assertEquals("https://emag.ro/product_details/pd/DDHSVQMBM",
                json.get("product").get("url").asString());
        assertEquals(6, json.get("groups").size());
        assertEquals(19, json.get("rows").size());

        for (int rowIndex = 0; rowIndex < response.rows().size(); rowIndex++) {
            var values = json.get("rows").get(rowIndex).get("values");
            for (var group : response.groups()) {
                for (var column : group.columns()) {
                    var value = values.get(column.key());
                    switch (column.type()) {
                        case "date" -> assertEquals(LocalDate.of(2026, 5, 1).plusWeeks(rowIndex),
                                LocalDate.parse(value.asString()));
                        case "integer" -> assertTrue(value.isIntegralNumber(), column.key());
                        case "decimal" -> assertTrue(value.isNumber(), column.key());
                        case "percent" -> {
                            assertTrue(value.isNumber(), column.key());
                            assertTrue(value.asDouble() >= 0 && value.asDouble() <= 1, column.key());
                        }
                        case "text" -> assertTrue(value.isString(), column.key());
                        default -> throw new AssertionError("Unexpected column type: " + column.type());
                    }
                }
            }
            assertTrue(values.get("rating").asDouble() >= 0 && values.get("rating").asDouble() <= 5);
        }
        assertEquals("2026-09-04", response.rows().getLast().values().get("week"));
    }

    @Test
    void keepsSamplesStableAndAllowsUnavailableValuesInTheResponseModel() {
        var api = new API(null);
        var first = api.getProductPerformance();
        assertEquals(first, api.getProductPerformance());
        assertNotEquals(first.rows().getFirst().values().get("gmv30"),
                first.rows().getLast().values().get("gmv30"));

        var unavailableValues = new LinkedHashMap<String, Object>();
        unavailableValues.put("rating", null);
        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(new ProductPerformanceMockData.Row(unavailableValues)));
        assertTrue(json.get("values").get("rating").isNull());
    }
}
