package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.db.GMVDashboardTable.CompanySeries;
import ro.sellfluence.db.GMVDashboardTable.GMVDashboardData;
import ro.sellfluence.db.GMVDashboardTable.UnsupportedCurrencyException;
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

import static org.junit.jupiter.api.Assertions.*;

class ServerGMVDashboardTest {
    @Test
    void returnsFreshExactMoneyAndDoesNotCacheCalculations() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            for (int call = 1; call <= 2; call++) {
                var response = get(client, app, "");
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                var json = JsonMapper.builder().build().readTree(response.body());
                assertEquals("2026-11", json.get("month").asString());
                assertEquals("2026-11-02", json.get("throughDate").asString());
                assertEquals("RON", json.get("currency").asString());
                assertEquals("9007199254740993.11", json.get("total").asString());
                var company = json.get("companies").get(0);
                assertEquals("company:Company <one>", company.get("key").asString());
                assertTrue(company.get("gmv").get(0).isString());
                assertEquals("9007199254740993.12", company.get("gmv").get(0).asString());
                assertEquals("-0.01", company.get("gmv").get(1).asString());
                assertEquals(call, api.calls);
                assertNull(api.month);
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void forwardsHistoricalMonthsAndRejectsMalformedMonthsBeforeCallingTheApi() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, app, "?month=2024-02");
            assertEquals(200, response.statusCode());
            assertEquals(YearMonth.of(2024, 2), api.month);
            var json = JsonMapper.builder().build().readTree(response.body());
            assertEquals("2024-02-29", json.get("throughDate").asString());
            assertEquals(29, json.get("days").size());
            for (var value : new String[]{"", "bad", "2026-00", "2026-13", "2026-1", "0000-01", "10000-01"}) {
                var invalid = get(client, app, "?month=" + value);
                assertEquals(400, invalid.statusCode(), value);
                assertEquals("no-store", invalid.headers().firstValue("Cache-Control").orElseThrow());
            }
            assertEquals(1, api.calls);
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsFutureMonthsBeforeAnyDatabaseRead() throws Exception {
        var app = app(new API(null));
        try (var client = HttpClient.newHttpClient()) {
            var future = YearMonth.now(ZoneId.of("Europe/Bucharest")).plusMonths(2);
            var response = get(client, app, "?month=" + future);
            assertEquals(400, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        } finally {
            app.stop();
        }
    }

    @Test
    void reportsUnsupportedCurrencyWithoutLeakingDatabaseFailures() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            api.currencyFailure = true;
            var unsupported = get(client, app, "");
            assertEquals(422, unsupported.statusCode());
            assertTrue(unsupported.body().contains("RON"));
            assertTrue(unsupported.body().contains("EUR"));
            assertEquals("no-store", unsupported.headers().firstValue("Cache-Control").orElseThrow());
            api.currencyFailure = false;
            api.databaseFailure = true;
            var failed = get(client, app, "");
            assertEquals(500, failed.statusCode());
            assertEquals("Database error", JsonMapper.builder().build().readTree(failed.body()).get("error").asString());
            assertFalse(failed.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    @Test
    void givesTheSalesAndGmvDashboardsTheirExactPageTitles() {
        assertEquals("Sales Dashboard", Server.toPageTitle("overview"));
        assertEquals("GMV Dashboard", Server.toPageTitle("gmv-dashboard"));
        assertEquals("Product Performance", Server.toPageTitle("product-performance"));
    }

    private static Javalin app(API api) {
        return Javalin.create(config -> config.routes.get("/app/gmvDashboard",
                ctx -> Server.serveGMVDashboard(ctx, api))).start(0);
    }

    private static HttpResponse<String> get(HttpClient client, Javalin app, String query) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                + "/app/gmvDashboard" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class StubAPI extends API {
        private int calls;
        private boolean databaseFailure;
        private boolean currencyFailure;
        private YearMonth month;

        StubAPI() { super(null); }

        @Override
        public GMVDashboardData getGMVDashboard() throws SQLException {
            failIfRequested();
            calls++;
            return new GMVDashboardData("2026-11", "2026-11-02", List.of("2026-11-01", "2026-11-02"),
                    "RON", "9007199254740993.11", List.of(new CompanySeries("company:Company <one>", "Company <one>",
                    "9007199254740993.11", List.of("9007199254740993.12", "-0.01"))));
        }

        @Override
        public GMVDashboardData getGMVDashboard(YearMonth month) throws SQLException {
            failIfRequested();
            calls++;
            this.month = month;
            var end = month.atEndOfMonth();
            var days = month.atDay(1).datesUntil(end.plusDays(1)).map(LocalDate::toString).toList();
            return new GMVDashboardData(month.toString(), end.toString(), days, "RON", "0.00",
                    List.of(new CompanySeries("company:Company <one>", "Company <one>", "0.00",
                            days.stream().map(day -> "0.00").toList())));
        }

        private void failIfRequested() throws SQLException {
            if (currencyFailure) throw new UnsupportedCurrencyException("EUR");
            if (databaseFailure) throw new SQLException("private database detail");
        }
    }
}
