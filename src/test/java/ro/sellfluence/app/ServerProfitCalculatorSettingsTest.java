package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.api.API;
import ro.sellfluence.api.ProfitCalculatorSettings;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.ConflictException;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.DecimalSection;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.ItemSection;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProfitCalculatorSettingsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROUTE = "/app/profitCalculatorSettings";
    private static final String VALID = """
            {"vendorGroup":"S","section":"miscellaneous","expectedRevision":1,
             "items":[{"label":"Rent","amountRon":"12.0000000000123"}]}
            """;

    @Test
    void readsAndSavesOneSectionAndMapsValidationConflictAndDatabaseErrors() throws Exception {
        var api = new StubAPI();
        var app = Javalin.create(config -> {
            config.routes.get(ROUTE, ctx -> Server.serveProfitCalculatorSettings(ctx, api, false));
            config.routes.post(ROUTE, ctx -> Server.serveProfitCalculatorSettings(ctx, api, true));
        }).start(0);
        try (var client = HttpClient.newHttpClient()) {
            var read = request(client, app, null);
            assertEquals(200, read.statusCode());
            assertEquals("no-store", read.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("S", MAPPER.readTree(read.body()).get("vendorGroup").asString());
            assertEquals("Date Calcul", Server.toPageTitle("profit-date-calcul"));

            assertEquals(400, request(client, app, VALID.replace("\"12.0000000000123\"", "12.00")).statusCode());
            assertEquals(0, api.saves);
            var save = request(client, app, VALID);
            assertEquals(200, save.statusCode());
            assertTrue(MAPPER.readTree(save.body()).get("saved").asBoolean());
            assertEquals("12.0000000000123", api.request.change().items().getFirst().amountRon());
            assertEquals(1, api.reads, "A committed save is acknowledged before a separate reload");
            api.failure = new ConflictException();
            assertEquals(409, request(client, app, VALID).statusCode());
            api.failure = new SQLException("private database detail");
            var failedSave = request(client, app, VALID);
            assertEquals(500, failedSave.statusCode());
            assertFalse(failedSave.body().contains("private database detail"));
        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> request(HttpClient client, Javalin app, String body) throws Exception {
        var url = "http://127.0.0.1:" + app.port() + ROUTE + (body == null ? "?vendorGroup=S" : "");
        var builder = HttpRequest.newBuilder(URI.create(url));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class StubAPI extends API {
        int reads;
        int saves;
        SQLException failure;
        ProfitCalculatorSettings.Request request;

        StubAPI() { super(null); }

        @Override
        public ProfitCalculatorSettings.Response getProfitCalculatorSettings(String vendorGroup) {
            reads++;
            var valueSection = new DecimalSection(1, Map.of());
            var itemSection = new ItemSection(1, List.of());
            var calculated = new ProfitCalculatorSettings.Calculated("2026-10", 0L, "0", "0", null, null, null, null);
            return new ProfitCalculatorSettings.Response(vendorGroup, valueSection, valueSection, itemSection, itemSection, calculated);
        }

        @Override
        public void saveProfitCalculatorSettings(ProfitCalculatorSettings.Request request) throws SQLException {
            if (failure != null) throw failure;
            this.request = request;
            saves++;
        }
    }
}
