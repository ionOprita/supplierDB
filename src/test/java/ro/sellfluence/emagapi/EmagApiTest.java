package ro.sellfluence.emagapi;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmagApiTest {

    private static final String PAGE_WITH_RESULT = """
            {"isError":false,"messages":[],"errors":[],"results":[{"value":"kept"}]}
            """;
    private static final String EMPTY_PAGE = """
            {"isError":false,"messages":[],"errors":[],"results":[]}
            """;

    @Test
    void retries500OnTheCurrentPageWithAPageLocalExponentialBackoff() throws Exception {
        var responses = List.of(
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_OK, PAGE_WITH_RESULT),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_OK, EMPTY_PAGE)
        );
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(responses, requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi("user", "password", delays::add);

            var result = emagApi.emagRequest(endpoint(server), true, Map.of(), null, Map.class);

            assertEquals(1, result.size());
            assertEquals("kept", result.getFirst().get("value"));
            assertEquals(List.of(1, 1, 2, 2, 2, 2, 2), requestedPages);
            assertEquals(List.of(10_000L, 10_000L, 20_000L, 40_000L, 80_000L), delays);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void givesUpAfterFourRetriesOfA500Response() throws Exception {
        var responses = List.of(
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, ""),
                new ScriptedResponse(HTTP_INTERNAL_ERROR, "")
        );
        var requestedPages = new CopyOnWriteArrayList<Integer>();
        var server = startServer(responses, requestedPages);
        try {
            var delays = new ArrayList<Long>();
            var emagApi = new EmagApi("user", "password", delays::add);

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> emagApi.emagRequest(endpoint(server), true, Map.of(), null, Map.class)
            );

            assertEquals("Emag API error 500", exception.getMessage());
            assertEquals(List.of(1, 1, 1, 1, 1), requestedPages);
            assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L), delays);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(List<ScriptedResponse> responses, List<Integer> requestedPages) throws IOException {
        var responseIndex = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/order/read", exchange -> {
            var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestedPages.add(JsonParser.parseString(requestBody).getAsJsonObject().get("currentPage").getAsInt());
            var index = responseIndex.getAndIncrement();
            if (index >= responses.size()) {
                sendResponse(exchange, 599, "Unexpected request");
                return;
            }
            var response = responses.get(index);
            sendResponse(exchange, response.statusCode(), response.body());
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:%d/order/read".formatted(server.getAddress().getPort());
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        var responseBody = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBody.length);
        try (var output = exchange.getResponseBody()) {
            output.write(responseBody);
        }
    }

    private record ScriptedResponse(int statusCode, String body) {
    }
}
