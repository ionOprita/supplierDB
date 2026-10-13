package ro.sellfluence.emagapi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductOfferReadTest {
    private static final String OFFER_PAGE = """
            {"isError":false,"messages":[],"results":[{"id":4294967295,"status":2,"part_number_key":"ES0NKBBBM"}]}
            """;
    private static final String EMPTY_PAGE = """
            {"isError":false,"messages":[],"results":[]}
            """;
    private static final UserPassword CREDENTIALS = new UserPassword("offer-test", "offer-user", "password", null);

    @Test
    void readsEveryPageWithTopLevelFiltersAndCapturesTheFinalEmptyPage() throws Exception {
        try (var endpoint = new Endpoint(List.of(new Reply(200, OFFER_PAGE), new Reply(200, EMPTY_PAGE)))) {
            var delays = new ArrayList<Long>();
            var rawPages = new ArrayList<String>();
            var pageNumbers = new ArrayList<Integer>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());
            var filters = Map.<String, Object>of("part_number_key", "ES0NKBBBM");

            var offers = api.readProductOffers(filters, (page, body) -> {
                pageNumbers.add(page);
                rawPages.add(body);
            });

            assertEquals(1, offers.size());
            assertEquals(4_294_967_295L, offers.getFirst().id());
            assertEquals(2, offers.getFirst().status());
            assertEquals(List.of(1, 2), endpoint.pages());
            assertEquals(List.of(1, 2), pageNumbers);
            assertEquals(List.of(OFFER_PAGE, EMPTY_PAGE), rawPages);
            assertEquals(List.of(350L), delays);
            for (var request : endpoint.requests) {
                assertEquals("POST", request.method());
                assertEquals("Basic " + Base64.getEncoder().encodeToString("offer-user:password".getBytes(StandardCharsets.UTF_8)), request.authorization());
                assertEquals("application/json", request.contentType());
                assertEquals(100, request.body().get("itemsPerPage").getAsInt());
                assertEquals("ES0NKBBBM", request.body().get("part_number_key").getAsString());
                assertFalse(request.body().has("data"));
                assertFalse(request.body().has("status"));
            }
            assertEquals(Map.of("part_number_key", "ES0NKBBBM"), filters);
        }
    }

    @Test
    void rawReadPreservesTheBodyAndExplicitPaginationOverridesFilterEntries() throws Exception {
        String raw = " {\"isError\":true,\"messages\":[\"inspect me\"]} \n";
        try (var endpoint = new Endpoint(List.of(new Reply(200, raw)))) {
            var api = new EmagApi(CREDENTIALS, milliseconds -> {}, endpoint.uri());
            var filters = Map.<String, Object>of("currentPage", 99, "itemsPerPage", 101, "id", 4_294_967_295L);

            assertEquals(raw, api.readProductOffersRaw(filters, 3, 25));

            var body = endpoint.requests.getFirst().body();
            assertEquals(3, body.get("currentPage").getAsInt());
            assertEquals(25, body.get("itemsPerPage").getAsInt());
            assertEquals(4_294_967_295L, body.get("id").getAsLong());
            assertEquals(99, filters.get("currentPage"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 504})
    void retriesTheSamePageAndResetsBackoffForTheNextPage(int status) throws Exception {
        try (var endpoint = new Endpoint(List.of(
                new Reply(status, "temporary failure"), new Reply(200, OFFER_PAGE),
                new Reply(status, "temporary failure"), new Reply(200, EMPTY_PAGE)))) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());

            assertEquals(1, api.readProductOffers(Map.of()).size());
            assertEquals(List.of(1, 1, 2, 2), endpoint.pages());
            assertEquals(List.of(10_000L, 350L, 10_000L), delays);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 504})
    void stopsAfterFourRetries(int status) throws Exception {
        try (var endpoint = new Endpoint(java.util.Collections.nCopies(5, new Reply(status, "temporary failure")))) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());

            var exception = assertThrows(IOException.class, () -> api.readProductOffersRaw(Map.of(), 7, 100));

            assertTrue(exception.getMessage().contains("page 7"));
            assertTrue(exception.getMessage().contains("HTTP " + status));
            assertEquals(List.of(7, 7, 7, 7, 7), endpoint.pages());
            assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L), delays);
        }
    }

    @Test
    void transportFailuresHaveBoundedRetries() throws Exception {
        URI closedEndpoint;
        try (var endpoint = new Endpoint(List.of())) {
            closedEndpoint = endpoint.uri();
        }
        var delays = new ArrayList<Long>();
        var api = new EmagApi(CREDENTIALS, delays::add, closedEndpoint);

        var exception = assertThrows(IOException.class, () -> api.readProductOffersRaw(Map.of(), 1, 100));

        assertTrue(exception.getMessage().contains("after 5 attempts"));
        assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L), delays);
        assertTrue(exception.getCause() instanceof IOException);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void rejectsPermanentHttpErrorsWithoutRetrying(int status) throws Exception {
        try (var endpoint = new Endpoint(List.of(new Reply(status, "not permitted")))) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());

            var exception = assertThrows(IOException.class, () -> api.readProductOffers(Map.of()));

            assertTrue(exception.getMessage().contains("HTTP " + status));
            assertEquals(List.of(1), endpoint.pages());
            assertTrue(delays.isEmpty());
        }
    }

    @Test
    void capturesApplicationErrorsBeforeDecodingAndDoesNotRequestAnotherPage() throws Exception {
        String errorBody = "{\"isError\":true,\"messages\":[\"Invalid vendor ip\"],\"results\":[]}";
        try (var endpoint = new Endpoint(List.of(new Reply(200, errorBody)))) {
            var delays = new ArrayList<Long>();
            var captured = new ArrayList<String>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());

            var exception = assertThrows(IllegalArgumentException.class,
                    () -> api.readProductOffers(Map.of(), (page, body) -> captured.add(body)));

            assertTrue(exception.getMessage().contains("page 1"));
            assertTrue(exception.getMessage().contains("Invalid vendor ip"));
            assertEquals(List.of(errorBody), captured);
            assertEquals(List.of(1), endpoint.pages());
            assertTrue(delays.isEmpty());
        }
    }

    @Test
    void captureFailureAbortsBeforeDecodingOrRequestingAnotherPage() throws Exception {
        try (var endpoint = new Endpoint(List.of(new Reply(200, "malformed JSON")))) {
            var delays = new ArrayList<Long>();
            var api = new EmagApi(CREDENTIALS, delays::add, endpoint.uri());

            var exception = assertThrows(IOException.class, () -> api.readProductOffers(Map.of(), (page, body) -> {
                throw new IOException("capture failed");
            }));

            assertEquals("capture failed", exception.getMessage());
            assertEquals(List.of(1), endpoint.pages());
            assertTrue(delays.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void interruptedPaginationAndRetriesStopImmediately(int status) throws Exception {
        try (var endpoint = new Endpoint(List.of(new Reply(status, OFFER_PAGE)))) {
            var api = new EmagApi(CREDENTIALS, milliseconds -> {
                throw new InterruptedException("stop");
            }, endpoint.uri());

            assertThrows(InterruptedException.class, () -> api.readProductOffers(Map.of()));
            assertEquals(List.of(1), endpoint.pages());
        }
    }

    @Test
    void rejectsInvalidPaginationAndDataWrappersBeforeSending() {
        var api = new EmagApi(CREDENTIALS);
        assertThrows(IllegalArgumentException.class, () -> api.readProductOffersRaw(Map.of(), 0, 100));
        assertThrows(IllegalArgumentException.class, () -> api.readProductOffersRaw(Map.of(), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> api.readProductOffersRaw(Map.of(), 1, 101));
        assertThrows(IllegalArgumentException.class, () -> api.readProductOffersRaw(Map.of("data", Map.of("status", 1)), 1, 100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"isError\":null,\"results\":[]}",
            "{\"isError\":\"false\",\"results\":[]}", "{\"isError\":false}",
            "{\"isError\":false,\"results\":null}", "{\"isError\":false,\"results\":{}}",
            "{\"isError\":false,\"results\":[null]}", "{\"isError\":false,\"results\":[1]}",
            "{\"isError\":false,\"results\":[]} {}", "malformed JSON"})
    void rejectsMalformedOrIncompleteEnvelopes(String body) {
        var api = new EmagApi(CREDENTIALS);
        assertThrows(IllegalArgumentException.class, () -> api.parseProductOffers(body));
    }

    private record Reply(int status, String body) {}

    private record Request(String method, String authorization, String contentType, JsonObject body) {}

    private static class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        Endpoint(List<Reply> replies) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var index = new AtomicInteger();
            server.createContext("/product_offer/read", exchange -> {
                try (exchange) {
                    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"), JsonParser.parseString(body).getAsJsonObject()));
                    int replyIndex = index.getAndIncrement();
                    var reply = replyIndex < replies.size() ? replies.get(replyIndex) : new Reply(599, "Unexpected request");
                    var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:%d/product_offer/read".formatted(server.getAddress().getPort()));
        }

        List<Integer> pages() {
            return requests.stream().map(request -> request.body().get("currentPage").getAsInt()).toList();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
