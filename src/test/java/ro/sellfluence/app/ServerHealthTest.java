package ro.sellfluence.app;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerHealthTest {
    @Test
    void reportsAvailabilityWithoutAuthenticationAndImmediatelyReflectsShutdown() throws Exception {
        var shutdownRequested = new AtomicBoolean(false);
        var app = Javalin.create(config -> config.routes.get("/health",
                ctx -> Server.serveHealth(ctx, shutdownRequested.get()))).start(0);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/health"))
                    .GET().build();

            var healthy = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthy.statusCode());
            assertEquals("ok", healthy.body());
            assertEquals("no-store", healthy.headers().firstValue("Cache-Control").orElseThrow());

            shutdownRequested.set(true);

            var stopping = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, stopping.statusCode());
            assertEquals("stopping", stopping.body());
            assertEquals("no-store", stopping.headers().firstValue("Cache-Control").orElseThrow());
        } finally {
            app.stop();
        }
    }
}
