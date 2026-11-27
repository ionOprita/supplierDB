package ro.sellfluence.app;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import ro.sellfluence.db.StockImport.Document;
import ro.sellfluence.db.StockImportTable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** The session role check for /app/* is registered in Server.configureAPI. */
final class StockImportRoutes {
    private static final String ROOT = "/app/stocuri";
    private static final String GROUP = ROOT + "/{group}";
    private static final String IMPORTS = GROUP + "/imports";
    private static final String RECORD = IMPORTS + "/{id}";
    private static final Logger LOG = Logger.getLogger(StockImportRoutes.class.getName());
    // Keep original decimal inputs intact when a browser edits an unrelated field and saves.
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StockImportRoutes() {}

    static void register(JavalinDefaultRoutingApi app, StockImportTable table) {
        app.get(ROOT + "/groups", ctx -> respond(ctx, 200, table::groups));
        app.get(IMPORTS, ctx -> respond(ctx, 200, () -> {
            var records = table.list(ctx.pathParam("group"));
            String query = query(ctx);
            if (query.isEmpty()) return records;
            return records.stream().filter(row -> matches(query, row.reference(), row.title(), row.sellingCompany(),
                    row.orderDate(), row.arrivalDate(), row.eta(), row.method(), row.status())).toList();
        }));
        app.get(GROUP + "/products", ctx -> respond(ctx, 200, () -> {
            var products = table.products(ctx.pathParam("group"));
            String query = query(ctx);
            if (query.isEmpty()) return products;
            return products.stream().filter(row -> matches(query, row.productCode(), row.name(), row.model(), row.pnk()))
                    .toList();
        }));
        app.get(RECORD, ctx -> respond(ctx, 200, () -> table.get(ctx.pathParam("group"), recordId(ctx))));
        app.post(GROUP + "/calculate", ctx -> respond(ctx, 200,
                () -> table.preview(ctx.pathParam("group"), readDocument(ctx)).calculation()));
        app.post(IMPORTS, ctx -> respond(ctx, 201, () -> {
            var document = readDocument(ctx);
            if (document.id() != null || document.revision() != 0)
                throw new IllegalArgumentException("Un import nou trebuie să aibă revizia 0 și să nu aibă un ID.");
            return table.save(ctx.pathParam("group"), document);
        }));
        app.put(RECORD, ctx -> respond(ctx, 200, () -> {
            var document = readDocument(ctx);
            if (!recordId(ctx).equals(document.id()) || document.revision() < 1)
                throw new IllegalArgumentException("ID-ul sau revizia importului nu corespunde înregistrării.");
            return table.save(ctx.pathParam("group"), document);
        }));
    }

    private static UUID recordId(Context ctx) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("ID-ul importului este invalid.");
        }
    }

    private static Document readDocument(Context ctx) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new IllegalArgumentException("Trimiteți importul în format JSON.");
        Document document;
        try {
            document = JSON.readValue(ctx.body(), Document.class);
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Datele importului nu au un format valid.");
        }
        if (document == null) throw new IllegalArgumentException("Lipsesc datele importului.");
        return document;
    }

    private static String query(Context ctx) {
        return Objects.toString(ctx.queryParam("q"), "").strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String query, String... values) {
        return Stream.of(values).filter(Objects::nonNull).anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(query));
    }

    @FunctionalInterface
    private interface Action {
        Object run() throws SQLException;
    }

    private static void respond(Context ctx, int status, Action action) {
        ctx.header("Cache-Control", "no-store");
        try {
            var result = action.run();
            ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(result));
        } catch (StockImportTable.NotFound error) {
            ctx.status(404).json(Map.of("error", "Grupul sau importul nu a fost găsit."));
        } catch (StockImportTable.Conflict error) {
            ctx.status(409).json(Map.of("error", error.getMessage()));
        } catch (IllegalArgumentException error) {
            ctx.status(400).json(Map.of("error", Objects.toString(error.getMessage(), "Date de import invalide.")));
        } catch (SQLException error) {
            LOG.log(Level.SEVERE, "Database failure for stock imports", error);
            ctx.status(500).json(Map.of("error", "Eroare la accesarea registrului de importuri."));
        }
    }
}
