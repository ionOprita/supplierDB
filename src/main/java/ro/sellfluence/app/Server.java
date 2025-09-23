package ro.sellfluence.app;

import io.javalin.Javalin;
import ro.sellfluence.api.API;
import ro.sellfluence.db.EmagMirrorDB;

/**
 * Server for the EmagMirror app.
 */
public class Server {
    static void main() throws Exception {
        String configNamePort = "PORT";
        int port = Integer.parseInt(System.getProperty(configNamePort,
                System.getenv().getOrDefault(configNamePort, "8080")));

        EmagMirrorDB db = EmagMirrorDB.getEmagMirrorDB("emagTest");

        API api = new API(db);

        var app = Javalin.create(cfg -> {
            cfg.bundledPlugins.enableDevLogging();                 // request log
            cfg.http.defaultContentType = "application/json";
            // Serve static files from classpath:/public
            cfg.staticFiles.add("/public");
        });

        app.get("/products", ctx -> {
            String json = api.getProducts();
            if (json == null) {
                ctx.status(500).result("""
                        {"error":"Database error"}""");
            } else {
                ctx.contentType("application/json").result(json);
            }
        });

        app.get("/rrr/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var rrr = api.getRRR(id);
            if (rrr == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(rrr);
            }
        });

        app.get("/orders/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var orders = api.getOrders(id);
            if (orders == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(orders);
            }
        });

        app.get("/stornos/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var stornos = api.getStornos(id);
            if (stornos == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(stornos);
            }
        });

        app.get("/returns/{id}", ctx -> {
            String id = ctx.pathParam("id");
            var returns = api.getReturns(id);
            if (returns == null) {
                ctx.status(500).result("{\"error\":\"Database error\"}");
            } else {
                ctx.json(returns);
            }
        });

        // Health check (handy)
        app.get("/health", ctx -> ctx.result("ok"));

        app.start(port);
    }
}