package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorFbeTariffs;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.ConflictException;
import ro.sellfluence.db.ProfitCalculatorFbeTariffTable.Tariff;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProfitCalculatorFbeTariffsTest {
    private static final String REQUEST = """
            {"changes":[{"id":1,"expectedRevision":1,"values":{"orderProcessingFeeRon":"2.55555555555555555555"}}]}
            """;

    @Test
    void readsExactSavedTariffsAndAcknowledgesSaveWithoutPostCommitReadback() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            var read = request(client, app, null);
            assertEquals(200, read.statusCode());
            var saved = new ObjectMapper().readTree(read.body()).get("tariffs").get(0);
            assertEquals(1, saved.get("id").asInt());
            assertEquals("3.5", saved.get("orderProcessingFeeRon").asString());
            assertTrue(saved.get("weightRangeKg").isNull());
            assertEquals("no-store", read.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(1, api.reads);
            api.readFailure = true;
            var save = request(client, app, REQUEST);
            assertEquals(200, save.statusCode());
            assertTrue(new ObjectMapper().readTree(save.body()).get("saved").asBoolean());
            assertEquals(1, api.saves);
            assertEquals(1, api.reads, "A committed save is acknowledged before any separate reload");
            assertEquals("2.55555555555555555555", api.lastRequest.changes().getFirst().values().get("orderProcessingFeeRon"));
            assertEquals("no-store", save.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("FBE Tariffs", Server.toPageTitle("profit-fbe-tariffs"));
        } finally {
            app.stop();
        }
    }

    @Test
    void rejectsMalformedRequestsBeforeCallingSave() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            for (var payload : new String[]{"{", "null", "{}", REQUEST.replace("2.55555555555555555555", "-1"),
                    REQUEST.replace("\"id\":1", "\"id\":13"), REQUEST.replace("orderProcessingFeeRon", "unknown")}) {
                assertEquals(400, request(client, app, payload).statusCode());
            }
            assertEquals(0, api.saves);
            assertEquals(0, api.reads);
        } finally {
            app.stop();
        }
    }

    @Test
    void reportsConflictsValidationAndDatabaseFailuresWithoutPrivateDetails() throws Exception {
        var api = new StubAPI();
        var app = app(api);
        try (var client = HttpClient.newHttpClient()) {
            api.conflict = true;
            var conflict = request(client, app, REQUEST);
            assertEquals(409, conflict.statusCode());
            assertEquals(1, new ObjectMapper().readTree(conflict.body()).get("id").asInt());
            api.conflict = false;
            api.invalid = true;
            assertEquals(400, request(client, app, REQUEST).statusCode());
            api.invalid = false;
            api.saveFailure = true;
            var failedSave = request(client, app, REQUEST);
            assertEquals(500, failedSave.statusCode());
            assertEquals("Database error", new ObjectMapper().readTree(failedSave.body()).get("error").asString());
            assertFalse(failedSave.body().contains("private database detail"));
            api.readFailure = true;
            var failedRead = request(client, app, null);
            assertEquals(500, failedRead.statusCode());
            assertFalse(failedRead.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    private static Javalin app(API api) {
        return Javalin.create(config -> {
            config.routes.get("/tariffs", ctx -> Server.serveProfitCalculatorFbeTariffs(ctx, api, false));
            config.routes.post("/tariffs", ctx -> Server.serveProfitCalculatorFbeTariffs(ctx, api, true));
        }).start(0);
    }

    private static HttpResponse<String> request(HttpClient client, Javalin app, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/tariffs"));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class StubAPI extends API {
        int reads;
        int saves;
        boolean readFailure;
        boolean saveFailure;
        boolean conflict;
        boolean invalid;
        ProfitCalculatorFbeTariffs.Request lastRequest;

        StubAPI() { super(null); }

        @Override
        public ProfitCalculatorFbeTariffs.Response getProfitCalculatorFbeTariffs() throws SQLException {
            reads++;
            if (readFailure) throw new SQLException("private database detail");
            return new ProfitCalculatorFbeTariffs.Response(List.of(new Tariff(1, "Packable Small", new BigDecimal("200"),
                    new BigDecimal("150"), BigDecimal.TEN, new BigDecimal("520"), new BigDecimal("2"),
                    new BigDecimal("3.5"), new BigDecimal("1.8"), new BigDecimal("2"), new BigDecimal("2"),
                    null, null, null, null, null, 1)));
        }

        @Override
        public void saveProfitCalculatorFbeTariffs(ProfitCalculatorFbeTariffs.Request request) throws SQLException {
            if (conflict) throw new ConflictException(1);
            if (invalid) throw new IllegalArgumentException("Invalid tariff");
            if (saveFailure) throw new SQLException("private database detail");
            lastRequest = request;
            saves++;
        }
    }
}
