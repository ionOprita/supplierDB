package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.db.OverviewTable.CompanySeries;
import ro.sellfluence.db.OverviewTable.OverviewData;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerOverviewTest {
    @Test
    void returnsFreshDailyProductQuantitiesAndUnassignedReturnUnits() throws Exception {
        var api = new StubAPI();
        var app = Javalin.create(config -> config.routes.get("/app/overview",
                ctx -> Server.serveOverview(ctx, api))).start(0);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                    + "/app/overview")).GET().build();
            for (int call = 1; call <= 2; call++) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                var json = JsonMapper.builder().build().readTree(response.body());
                assertEquals("2026-11", json.get("month").asString());
                assertEquals("2026-11-02", json.get("throughDate").asString());
                assertEquals("2026-11-01", json.get("days").get(0).asString());
                var company = json.get("companies").get(0);
                assertEquals("company:Company <one>", company.get("key").asString());
                assertEquals("Company <one>", company.get("name").asString());
                assertEquals(call, company.get("orders").get(0).asLong());
                assertEquals(0, company.get("orders").get(1).asLong());
                assertEquals(2, company.get("returns").get(1).asLong());
                assertEquals(3, json.get("unassignedReturns").asLong());
                assertEquals(call, api.calls);
                assertNull(api.month);
            }
            api.fail = true;
            var failure = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, failure.statusCode());
            assertEquals("no-store", failure.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("Database error", JsonMapper.builder().build().readTree(failure.body()).get("error").asString());
            assertFalse(failure.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    @Test
    void parsesOnlyAnOptionalFourDigitCalendarMonth() {
        assertNull(Server.parseOverviewMonth(null));
        assertEquals(YearMonth.of(2024, 2), Server.parseOverviewMonth("2024-02"));
        assertEquals(YearMonth.of(1, 1), Server.parseOverviewMonth("0001-01"));
        assertEquals(YearMonth.of(9999, 12), Server.parseOverviewMonth("9999-12"));
        for (var value : new String[]{"", " ", "2026-1", "2026-00", "2026-13", "0000-01", "10000-01",
                "+2026-01", "2026-01-01", " 2026-01", "2026-01 ", "bad"}) {
            assertThrows(IllegalArgumentException.class, () -> Server.parseOverviewMonth(value));
        }
    }

    @Test
    void forwardsHistoricalMonthAndRejectsInvalidInputBeforeAccessingTheApi() throws Exception {
        var api = new StubAPI();
        var app = Javalin.create(config -> config.routes.get("/app/overview",
                ctx -> Server.serveOverview(ctx, api))).start(0);
        try (var client = HttpClient.newHttpClient()) {
            var base = "http://127.0.0.1:" + app.port() + "/app/overview";
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "?month=2024-02")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(YearMonth.of(2024, 2), api.month);
            var json = JsonMapper.builder().build().readTree(response.body());
            assertEquals("2024-02", json.get("month").asString());
            assertEquals("2024-02-29", json.get("throughDate").asString());
            assertEquals(29, json.get("days").size());
            for (var value : new String[]{"", "bad", "2026-00", "2026-13", "2026-1", "0000-01"}) {
                var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "?month=" + value)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode(), value);
                assertEquals("no-store", invalid.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(JsonMapper.builder().build().readTree(invalid.body()).has("error"));
            }
            assertEquals(1, api.calls);
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsFutureMonthBeforeDatabaseAccess() throws Exception {
        var api = new API(null);
        var app = Javalin.create(config -> config.routes.get("/app/overview",
                ctx -> Server.serveOverview(ctx, api))).start(0);
        try (var client = HttpClient.newHttpClient()) {
            var future = YearMonth.now(ZoneId.of("Europe/Bucharest")).plusMonths(2);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                    + "/app/overview?month=" + future)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(JsonMapper.builder().build().readTree(response.body()).has("error"));
        } finally {
            app.stop();
        }
    }

    private static class StubAPI extends API {
        private int calls;
        private boolean fail;
        private YearMonth month;

        StubAPI() {
            super(null);
        }

        @Override
        public OverviewData getOverview() throws SQLException {
            if (fail) throw new SQLException("private database detail");
            return new OverviewData("2026-11", "2026-11-02", List.of("2026-11-01", "2026-11-02"),
                    List.of(new CompanySeries("company:Company <one>", "Company <one>", List.of((long) ++calls, 0L), List.of(1L, 2L))), 3);
        }

        @Override
        public OverviewData getOverview(YearMonth month) throws SQLException {
            if (fail) throw new SQLException("private database detail");
            calls++;
            this.month = month;
            var end = month.atEndOfMonth();
            var days = month.atDay(1).datesUntil(end.plusDays(1)).map(LocalDate::toString).toList();
            var zeros = days.stream().map(day -> 0L).toList();
            return new OverviewData(month.toString(), end.toString(), days,
                    List.of(new CompanySeries("company:Company <one>", "Company <one>", zeros, zeros)), 0);
        }
    }
}
