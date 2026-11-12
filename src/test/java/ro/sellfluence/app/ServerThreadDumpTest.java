package ro.sellfluence.app;

import io.javalin.Javalin;
import io.javalin.rendering.template.JavalinJte;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.PassKey.Role;
import ro.sellfluence.db.PassKey.User;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerThreadDumpTest {
    @Test
    void rejectsUnauthenticatedAndNonAdminRequestsForBothPageAndSnapshot() throws Exception {
        var app = app();
        try (var client = HttpClient.newHttpClient()) {
            for (var path : List.of("/admin/thread-dump", "/admin/thread-dump/snapshot")) {
                for (var role : new String[]{null, "nobody", "user"}) {
                    var response = get(client, app, path, role);

                    assertEquals(403, response.statusCode(), path + " role=" + role);
                    assertPrivateHeaders(response);
                    assertFalse(response.body().contains("Thread dump captured at:"));
                    assertFalse(response.body().contains("threadDumpContent"));
                }
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void rendersAdminControlsAndEscapesTheAccountName() throws Exception {
        var app = app();
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, app, "/admin/thread-dump", "admin");

            assertEquals(200, response.statusCode());
            assertPrivateHeaders(response);
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            for (var control : List.of("Capture", "Filter", "Copy", "Download", "Content")) {
                assertTrue(response.body().contains("id=\"threadDump" + control + "\""), control);
            }
            assertTrue(response.body().contains("src=\"/js/thread-dump.js\""));
            assertTrue(response.body().contains("admin&lt;script&gt;alert(1)&lt;/script&gt;"));
            assertFalse(response.body().contains("admin<script>"));
        } finally {
            app.stop();
        }
    }

    @Test
    void capturesLiveThreadsForAdminsWithoutDatabaseOrSchedulerServices() throws Exception {
        var app = app();
        try (var client = HttpClient.newHttpClient()) {
            long callerId = Thread.currentThread().threadId();
            var response = get(client, app, "/admin/thread-dump/snapshot", "admin");

            assertEquals(200, response.statusCode());
            assertPrivateHeaders(response);
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            var snapshot = JsonMapper.builder().build().readValue(response.body(), ThreadDump.Snapshot.class);
            Instant.parse(snapshot.capturedAt());
            assertTrue(snapshot.header().contains(snapshot.capturedAt()));
            assertTrue(snapshot.header().contains("JVM:"));
            assertFalse(snapshot.threads().isEmpty());
            var caller = snapshot.threads().stream().filter(thread -> thread.id() == callerId)
                    .findFirst().orElseThrow();
            assertEquals(Thread.currentThread().getName(), caller.name());
            assertTrue(caller.text().contains("ServerThreadDumpTest"));
            assertFalse(caller.state().isBlank());
        } finally {
            app.stop();
        }
    }

    private static Javalin app() {
        return Javalin.create(config -> {
            config.fileRenderer(new JavalinJte(Server.createJteEngine()));
            config.routes.before(ctx -> {
                String role = ctx.header("X-Test-Role");
                if (role != null) {
                    ctx.sessionAttribute("user", new User("admin<script>alert(1)</script>", Role.valueOf(role)));
                }
            });
            Server.configureThreadDumpRoutes(config.routes);
        }).start(0);
    }

    private static HttpResponse<String> get(HttpClient client, Javalin app, String path, String role)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (role != null) {
            request.header("X-Test-Role", role);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertPrivateHeaders(HttpResponse<?> response) {
        assertEquals("no-store, private", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }
}
