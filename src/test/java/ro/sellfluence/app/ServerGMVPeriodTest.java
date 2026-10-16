package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGMVPeriodTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StubAPI api = new StubAPI();
    private Javalin app;

    @AfterEach
    void stopServer() {
        if (app != null) app.stop();
    }

    @Test
    void acceptsInclusiveDatesAndPreservesTheExactProductCode() {
        var code = " product/code with spaces? & + ";
        assertEquals(new Server.GMVPeriodRequest(code, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)),
                Server.parseGMVPeriodRequest(code, "2024-02-29", "2024-03-01"));
        assertEquals(new Server.GMVPeriodRequest(code, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)),
                Server.parseGMVPeriodRequest(code, "2024-02-29", "2024-02-29"));
    }

    @Test
    void rejectsMissingProductAndInvalidOrReversedDates() {
        for (var invalid : new String[]{null, "", " \t\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseGMVPeriodRequest(invalid, "2026-01-01", "2026-01-31"));
        }
        for (var invalid : new String[]{null, "", "2026-02-29", "2026-02-30", "2026-13-01", "2026-1-1",
                "2026-01-01T00:00:00", " 2026-01-01", "bad"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseGMVPeriodRequest("product", invalid, "2026-01-31"));
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseGMVPeriodRequest("product", "2026-01-01", invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseGMVPeriodRequest("product", "2026-02-01", "2026-01-31"));
    }

    @Test
    void defaultsToThirtyInclusiveDaysAcrossLeapDay() {
        var clock = Clock.fixed(Instant.parse("2024-03-01T06:00:00Z"), ZoneOffset.UTC);
        assertEquals(Map.of("defaultStartDate", "2024-02-01", "defaultEndDate", "2024-03-01"),
                Server.defaultGMVPeriodDates(clock));
    }

    @Test
    void defaultDatesUseTheServerCalendarDateWithoutUtcShift() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T23:30:00Z"), ZoneId.of("Europe/Zurich"));
        assertEquals(Map.of("defaultStartDate", "2025-12-04", "defaultEndDate", "2026-01-02"),
                Server.defaultGMVPeriodDates(clock));
    }

    @Test
    void returnsExactAmountStringsAndTheSubmittedContextWithoutCaching() throws Exception {
        var code = " product/code with spaces? & + ";
        api.amount = "9007199254740993.12";

        var response = get(query(code, "2026-01-01", "2026-01-31"));

        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        var json = JSON.readTree(response.body());
        assertEquals(code, json.get("productCode").asString());
        assertEquals("2026-01-01", json.get("startDate").asString());
        assertEquals("2026-01-31", json.get("endDate").asString());
        assertTrue(json.get("gmv").isString());
        assertEquals(api.amount, json.get("gmv").asString());
        assertTrue(json.get("contributions").isArray());
        assertEquals(0, json.get("contributions").size());
        assertEquals(new Server.GMVPeriodRequest(code, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                api.request);
    }

    @Test
    void returnsValidZeroAndNegativeAmountsAsSuccessfulCalculations() throws Exception {
        for (var amount : new String[]{"0.00", "-12.34"}) {
            api.amount = amount;
            var response = get(query("product", "2026-01-01", "2026-01-01"));
            assertEquals(200, response.statusCode());
            assertEquals(amount, JSON.readTree(response.body()).get("gmv").asString());
        }
    }

    @Test
    void includesTheMatchingOrderBreakdownInTheCalculationResponse() throws Exception {
        api.amount = "-12.34";
        api.contributions = List.of(
                new API.GMVContributionResponse("original", "2026-01-01", "Finalized", 1, "10.00", "10.00"),
                new API.GMVContributionResponse("returned", "2026-01-02", "Storno deduction", -2, "11.17", "-22.34")
        );

        var response = get(query("product", "2026-01-01", "2026-01-31"));
        var json = JSON.readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("-12.34", json.get("gmv").asString());
        assertEquals(2, json.get("contributions").size());
        assertEquals("original", json.get("contributions").get(0).get("orderId").asString());
        assertEquals(-2, json.get("contributions").get(1).get("quantity").asInt());
        assertTrue(json.get("contributions").get(1).get("gmv").isString());
        assertEquals("-22.34", json.get("contributions").get(1).get("gmv").asString());
        assertEquals(1, api.calls);
    }

    @Test
    void returnsBadRequestBeforeCallingTheCalculation() throws Exception {
        for (var query : new String[]{"", "?productCode=product", query("", "2026-01-01", "2026-01-31"),
                query("product", "bad", "2026-01-31"), query("product", "2026-02-01", "2026-01-31")}) {
            var response = get(query);
            assertEquals(400, response.statusCode());
            assertTrue(JSON.readTree(response.body()).has("error"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertEquals(0, api.calls);
    }

    @Test
    void distinguishesAnUnknownProductFromAValidZero() throws Exception {
        api.unknown = true;
        var response = get(query("unknown", "2026-01-01", "2026-01-31"));
        assertEquals(404, response.statusCode());
        assertEquals("Product not found", JSON.readTree(response.body()).get("error").asString());
    }

    @Test
    void reportsDatabaseFailureWithoutExposingInternalDetails() throws Exception {
        api.failure = true;
        var response = get(query("product", "2026-01-01", "2026-01-31"));
        assertEquals(500, response.statusCode());
        assertEquals("Database error", JSON.readTree(response.body()).get("error").asString());
        assertFalse(response.body().contains("private database detail"));
    }

    private HttpResponse<String> get(String query) throws Exception {
        if (app == null) {
            app = Javalin.create(config -> config.routes.get("/app/gmvForPeriod",
                    ctx -> Server.computeGMVForPeriod(ctx, api))).start(0);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                    + "/app/gmvForPeriod" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String query(String code, String start, String end) {
        return "?productCode=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&startDate=" + start + "&endDate=" + end;
    }

    private static final class StubAPI extends API {
        private int calls;
        private String amount = "0.00";
        private boolean unknown;
        private boolean failure;
        private List<GMVContributionResponse> contributions = List.of();
        private Server.GMVPeriodRequest request;

        private StubAPI() {
            super(null);
        }

        @Override
        public Optional<GMVPeriodResponse> getGMVForPeriod(String productCode, LocalDate startDate, LocalDate endDate)
                throws SQLException {
            calls++;
            request = new Server.GMVPeriodRequest(productCode, startDate, endDate);
            if (failure) throw new SQLException("private database detail");
            return unknown ? Optional.empty() : Optional.of(new GMVPeriodResponse(
                    productCode, startDate.toString(), endDate.toString(), amount, contributions));
        }
    }
}
