package ro.sellfluence.app;

import ch.claudio.db.DB;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.StockImportTable;
import ro.sellfluence.db.versions.EmagMirrorDBVersion55;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StockImportRoutesIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void employeeCanPreviewSaveEditAndSwitchGroupsWithoutLosingPrecision() throws Exception {
        String url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Requires a disposable PostgreSQL database");
        var credentials = new Properties();
        for (String key : new String[]{"user", "password"}) {
            var value = System.getenv("ADS_TEST_DB_" + key.toUpperCase(java.util.Locale.ROOT));
            if (value != null) credentials.setProperty(key, value);
        }
        var schema = "stock_routes_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, credentials); var sql = db.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                db.setSchema(schema);
                sql.execute("""
                        CREATE TABLE vendor(id UUID PRIMARY KEY, vendor_group TEXT, company_name TEXT);
                        INSERT INTO vendor VALUES (gen_random_uuid(), 'S', 'Company S'), (gen_random_uuid(), 'K', 'Company K');
                        CREATE TABLE product(product_code TEXT PRIMARY KEY, name TEXT, model TEXT, emag_pnk TEXT, import_tax NUMERIC, vendor UUID);
                        INSERT INTO product SELECT 'SKU-' || vendor_group, 'Sample', 'Standard', 'PNK-' || vendor_group, 0.022, id FROM vendor;
                        """);
                for (String transport : new String[]{"air", "rail", "sea", "truck"}) {
                    String prefix = transport + "_transport_";
                    sql.execute("ALTER TABLE product ADD COLUMN " + prefix + "pcs_per_carton NUMERIC, ADD COLUMN "
                            + prefix + "kg_per_carton NUMERIC, ADD COLUMN " + prefix + "volume_m3_per_carton NUMERIC");
                }
                EmagMirrorDBVersion55.version55(db);
                var config = new HikariConfig();
                config.setJdbcUrl(url);
                config.setDataSourceProperties(credentials);
                config.setSchema(schema);
                config.setMaximumPoolSize(2);
                try (var pool = new HikariDataSource(config);
                     var http = HttpClient.newHttpClient()) {
                    var app = Javalin.create(cfg -> StockImportRoutes.register(cfg.routes, new StockImportTable(new DB(pool))))
                            .start("127.0.0.1", 0);
                    try {
                        checkRegister(http, "http://127.0.0.1:" + app.port() + "/app/stocuri");
                    } finally {
                        app.stop();
                    }
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void checkRegister(HttpClient client, String base) throws Exception {
        String input = """
                {"id":null,"revision":0,"fields":{"reference":"49A","sellingCompany":"Company S"},
                 "lines":[{"model":"Manual sample","quantity":"2","unitUsd":"11.957900000000000001"}]}
                """;
        assertEquals(2, JSON.readTree(call(client, base + "/groups", "GET", null, 200)).size());
        String catalog = base + "/S/products";
        var found = JSON.readTree(call(client, catalog + "?q=pnk-s", "GET", null, 200));
        assertEquals("SKU-S", found.get(0).get("productCode").asString());
        assertTrue(JSON.readTree(call(client, catalog + "?q=sku-k", "GET", null, 200)).isEmpty());
        String imports = base + "/S/imports";
        call(client, imports, "POST", "{", 400);
        call(client, imports, "POST", input.replace("\"model\":", "\"productCode\":\"SKU-K\",\"model\":"), 400);
        var preview = JSON.readTree(call(client, base + "/S/calculate", "POST", input, 200));
        assertEquals("23.915800000000000002", preview.get("totals").get("purchaseUsd").asString());
        assertTrue(JSON.readTree(call(client, imports, "GET", null, 200)).isEmpty());

        var first = JSON.readTree(call(client, imports, "POST", input, 201)).get("document");
        String id = first.get("id").asString();
        String record = imports + "/" + id;
        var loaded = JSON.readTree(call(client, record, "GET", null, 200)).get("document");
        assertEquals("11.957900000000000001", loaded.get("lines").get(0).get("unitUsd").asString());
        assertEquals(first, loaded);
        assertEquals(1, JSON.readTree(call(client, imports + "?q=49a", "GET", null, 200)).size());
        assertTrue(JSON.readTree(call(client, base + "/K/imports", "GET", null, 200)).isEmpty());
        call(client, base + "/K/imports/" + id, "GET", null, 404);
        call(client, base + "/K/imports/" + id, "PUT", loaded.toString(), 404);
        call(client, base + "/K/calculate", "POST", loaded.toString(), 404);

        var edited = loaded.toString().replace("Manual sample", "Updated sample");
        call(client, imports, "POST", edited, 400);
        call(client, record, "PUT", edited, 200);
        call(client, record, "PUT", loaded.toString(), 409);
        assertTrue(call(client, record, "GET", null, 200).contains("Updated sample"));
    }

    private static String call(HttpClient client, String url, String method, String body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response::body);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return response.body();
    }
}
