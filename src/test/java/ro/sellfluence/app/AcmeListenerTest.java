package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.sellfluence.apphelper.ACME;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcmeListenerTest {
    @Test
    void servesOnlyChallengeFilesAndStops(@TempDir Path root) throws Exception {
        Path challenges = Files.createDirectories(root.resolve(".well-known/acme-challenge"));
        Files.writeString(challenges.resolve("test_token-1"), "challenge response");
        var listener = ACME.startAcmeListener(root, 0);
        int port = listener.port();
        try (var client = HttpClient.newHttpClient()) {
            assertEquals("challenge response", get(client, port, "/.well-known/acme-challenge/test_token-1").body());
            assertEquals(404, get(client, port, "/health").statusCode());
            assertEquals(404, get(client, port, "/.well-known/acme-challenge/missing").statusCode());
            assertEquals(404, get(client, port, "/.well-known/acme-challenge/%2e%2e/secret").statusCode());
            listener.stop();
            assertThrows(java.io.IOException.class, () -> get(client, port, "/health"));
        } finally {
            listener.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
