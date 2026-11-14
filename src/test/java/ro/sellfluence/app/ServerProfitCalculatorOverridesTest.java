package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorCategory;
import ro.sellfluence.api.ProfitCalculatorOverrides;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideConflictException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProfitCalculatorOverridesTest {
    private static final String REQUEST = """
            {"vendorGroup":"S","category":"Trimmers","changes":[
              {"productCode":"code","expectedRevision":0,"values":{"salePriceRon":"169.99"}}]}
            """;

    @Test
    void previewDoesNotSaveAndSaveAcknowledgesWithoutReadingBackAfterCommit() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            var preview = post(client, app, "/preview", REQUEST);
            assertEquals(200, preview.statusCode());
            assertEquals(1, api.previews);
            assertEquals(0, api.saves);
            assertEquals("Trimmers", new ObjectMapper().readTree(preview.body()).get("category").asString());
            assertEquals("no-store", preview.headers().firstValue("Cache-Control").orElseThrow());
            var save = post(client, app, "/save", REQUEST);
            assertEquals(200, save.statusCode());
            assertTrue(new ObjectMapper().readTree(save.body()).get("saved").asBoolean());
            assertEquals(1, api.saves);
            assertEquals("no-store", save.headers().firstValue("Cache-Control").orElseThrow());
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsMalformedRequestsBeforeCallingTheApi() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            for (var payload : new String[]{"{", "null", "{}", REQUEST.replace("169.99", "-1")}) {
                assertEquals(400, post(client, app, "/save", payload).statusCode());
            }
            assertEquals(0, api.saves);
            assertEquals(0, api.previews);
        } finally {
            app.stop();
        }
    }

    @Test
    void reportsConflictsAndDatabaseFailuresWithoutInternalDetails() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            api.conflict = true;
            var conflict = post(client, app, "/save", REQUEST);
            assertEquals(409, conflict.statusCode());
            assertEquals("code", new ObjectMapper().readTree(conflict.body()).get("productCode").asString());
            api.conflict = false;
            api.databaseFailure = true;
            var failed = post(client, app, "/save", REQUEST);
            assertEquals(500, failed.statusCode());
            assertEquals("Database error", new ObjectMapper().readTree(failed.body()).get("error").asString());
            assertFalse(failed.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    private static Javalin app(API api) {
        return Javalin.create(config -> {
            config.routes.post("/preview", ctx -> Server.serveProfitCalculatorOverrides(ctx, api, false));
            config.routes.post("/save", ctx -> Server.serveProfitCalculatorOverrides(ctx, api, true));
        }).start(0);
    }

    private static HttpResponse<String> post(HttpClient client, Javalin app, String path, String payload) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static class StubAPI extends API {
        int previews;
        int saves;
        boolean conflict;
        boolean databaseFailure;

        StubAPI() { super(null); }

        @Override
        public ProfitCalculatorCategory.Response previewProfitCalculatorCategory(ProfitCalculatorOverrides.Request request) {
            previews++;
            return new ProfitCalculatorCategory.Response(request.vendorGroup(), request.category(), List.of());
        }

        @Override
        public void saveProfitCalculatorOverrides(ProfitCalculatorOverrides.Request request) throws SQLException {
            if (conflict) throw new OverrideConflictException("code");
            if (databaseFailure) throw new SQLException("private database detail");
            saves++;
        }

        @Override
        public ProfitCalculatorCategory.Response getProfitCalculatorCategory(String vendorGroup, String category) {
            throw new AssertionError("Save must not attempt a post-commit read");
        }
    }
}
