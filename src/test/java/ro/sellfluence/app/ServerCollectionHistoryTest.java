package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.db.OffersTable.ProductVisits;
import ro.sellfluence.db.TaskHistory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerCollectionHistoryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T22:30:00Z"), ZoneOffset.UTC);
    private static final UUID VENDOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void historyDefaultsUseThirtyBucharestDaysAndAllowOlderRanges() {
        var defaults = Server.parseTaskHistoryRequest(null, null, null, null, null, null, CLOCK);
        assertEquals(LocalDate.of(2026, 11, 5), defaults.to());
        assertEquals(LocalDate.of(2026, 10, 7), defaults.from());
        assertEquals(1, defaults.page());
        assertEquals(50, defaults.pageSize());
        var old = Server.parseTaskHistoryRequest("Fetch offers for test", "FAILED", "2024-01-01", "2024-12-31", "2", "10", CLOCK);
        assertEquals(2, old.page());
        assertEquals("Fetch offers for test", old.taskName());
        assertEquals("FAILED", old.status());
    }

    @Test
    void rejectsInvalidDatesRangesStatusesAndPagination() {
        for (var date : List.of("2026-2-01", "2026-02-30", "0000-01-01", "bad")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseTaskHistoryRequest(null, null, date, null, null, null, CLOCK));
        }
        for (var range : List.of(List.of("2026-11-06", "2026-11-06"), List.of("2026-11-05", "2026-11-04"),
                List.of("2024-01-01", "2025-01-01"))) {
            assertThrows(IllegalArgumentException.class, () -> Server.parseTaskHistoryRequest(
                    null, null, range.get(0), range.get(1), null, null, CLOCK));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseTaskHistoryRequest(null, "MISSING", null, null, null, null, CLOCK));
        for (var page : List.of("0", "-1", "1.1", "999999999999", "1000001")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Server.parseTaskHistoryRequest(null, null, null, null, page, null, CLOCK));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Server.parseTaskHistoryRequest(null, null, null, null, null, "201", CLOCK));
    }

    @Test
    void visitsAreIndependentOfCalendarPeriodAndValidateProductBeforeReturningData() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            var path = "/app/productVisits?vendorId=" + VENDOR + "&productCode=CODE";
            var response = get(client, app, path + "&period=month");
            assertEquals(200, response.statusCode());
            var data = JsonMapper.builder().build().readTree(response.body());
            assertEquals(14L, data.get("visits14").asLong());
            assertEquals(30L, data.get("visits30").asLong());
            assertEquals("2026-11-04", data.get("snapshotDate").asString());
            assertEquals("2026-11-04T13:00:00+02:00", data.get("fetchedAt").asString());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(400, get(client, app, "/app/productVisits?vendorId=bad&productCode=CODE").statusCode());
            assertEquals(1, api.calls);
            api.absent = true;
            assertEquals(404, get(client, app, path).statusCode());
            api.absent = false;
            api.fail = true;
            var failed = get(client, app, path);
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    @Test
    void historyAndCoverageHaveSeparateResponsesAndDoNotLeakDatabaseErrors() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, app, "/app/tasks/history?page=2&pageSize=10&status=FAILED");
            assertEquals(200, response.statusCode());
            var data = JsonMapper.builder().build().readTree(response.body());
            assertEquals(2, data.get("page").asInt());
            assertEquals(10, data.get("pageSize").asInt());
            assertEquals("FAILED", api.status);
            assertEquals("2026-11-04T13:00:00+02:00", data.get("items").get(0).get("startedAt").asString());
            var coverage = get(client, app, "/app/tasks/coverage?status=FAILED");
            assertEquals(200, coverage.statusCode());
            assertEquals("2026-11-04", JsonMapper.builder().build().readTree(coverage.body())
                    .get("days").get(0).get("date").asString());
            assertEquals(400, get(client, app, "/app/tasks/history?status=MISSING").statusCode());
            api.fail = true;
            var failed = get(client, app, "/app/tasks/history");
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("private database detail"));
            assertEquals("no-store", failed.headers().firstValue("Cache-Control").orElseThrow());
        } finally {
            app.stop();
        }
    }

    private static Javalin app(API api) {
        return Javalin.create(config -> {
            config.routes.get("/app/productVisits", ctx -> Server.serveProductVisits(ctx, api));
            config.routes.get("/app/tasks/history", ctx -> Server.serveTaskHistory(ctx, api, false));
            config.routes.get("/app/tasks/coverage", ctx -> Server.serveTaskHistory(ctx, api, true));
        }).start(0);
    }

    private static HttpResponse<String> get(HttpClient client, Javalin app, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static class StubAPI extends API {
        boolean absent;
        boolean fail;
        int calls;
        String status;

        StubAPI() { super(null); }

        @Override
        public Optional<ProductVisits> getProductVisits(UUID vendorId, String productCode) throws SQLException {
            calls++;
            if (fail) throw new SQLException("private database detail");
            assertEquals(VENDOR, vendorId);
            assertEquals("CODE", productCode);
            return absent ? Optional.empty() : Optional.of(new ProductVisits("available", 14L, 30L,
                    LocalDate.of(2026, 11, 4), OffsetDateTime.parse("2026-11-04T13:00:00+02:00"), true));
        }

        @Override
        public TaskHistory.HistoryPage getTaskHistory(String taskName, String status, LocalDate from, LocalDate to,
                                                       int page, int pageSize) throws SQLException {
            if (fail) throw new SQLException("private database detail");
            this.status = status;
            return new TaskHistory.HistoryPage(List.of(new TaskHistory.Attempt(1L, "Fetch offers for test",
                    OffsetDateTime.parse("2026-11-04T13:00:00+02:00"),
                    OffsetDateTime.parse("2026-11-04T13:01:00+02:00"), "FAILED", "Fetch failed")), 11, page, pageSize);
        }

        @Override
        public List<TaskHistory.CollectionDay> getOfferCollectionDays(String taskName, LocalDate from, LocalDate to) {
            return List.of(new TaskHistory.CollectionDay("Fetch offers for test", VENDOR, "Test company",
                    LocalDate.of(2026, 11, 4), "MISSING", null, 1, "Fetch failed"));
        }
    }
}
