package ro.sellfluence.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ro.sellfluence.support.UserPassword;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchCampaignsTest {
    private static final String PNK = "DDHSVQMBM";
    private static final long OFFER_ID = 4_294_967_295L;
    private static final UserPassword CREDENTIALS =
            new UserPassword("campaign-test", "campaign-user", "secret-test-password", null);
    private static final String EMPTY = "{\"isError\":false,\"messages\":[],\"results\":[]}\n";
    private static final String PRODUCT = """
            {"isError":false,"messages":[],"results":[
              {"id":4294967295,"part_number_key":"DDHSVQMBM"},
              {"id":99,"part_number_key":"OTHERPNK"}
            ]}
            """;
    private static final String CAMPAIGN = """
            {"isError":false,"results":[{"id":11,"participationRules":{"restrictedCategories":true}}]}
            """;

    @TempDir
    Path directory;

    @Test
    void readsCatalogPagesOnceAndSamplesOnlyReadEndpointsWithResolvedOfferId() throws Exception {
        var rawSample = """
                 {"isError":false,"results":[{"pnk":"DDHSVQMBM","ext_id":4294967295,
                 "current_price":51.6477000,"undocumented":{"text":"Ofertă nouă","value":null}}],
                 "links":{"next":"https://example.invalid/must-not-follow"}}
                """;
        var delays = new ArrayList<Long>();
        try (var endpoint = new Endpoint(request -> {
            if (request.path().equals("/api-3/product_offer/read")) {
                return ok(request.page() == 1 ? PRODUCT : EMPTY);
            }
            if (request.path().equals("/api-3/campaigns/read")) {
                return ok(switch (request.page()) {
                    case 1 -> """
                            {"isError":false,"results":[
                              {"id":11,"participationRules":{"restrictedCategories":true}},
                              {"id":12,"participationRules":{"restrictedCategories":false}}]}
                            """;
                    case 2 -> """
                            {"isError":false,"results":[
                              {"id":12,"participationRules":{"restrictedCategories":false}},
                              {"id":13,"participationRules":{"restrictedCategories":true}}]}
                            """;
                    default -> EMPTY;
                });
            }
            return ok(request.path().endsWith("/eligible-offers") ? rawSample : EMPTY);
        })) {
            var summary = run(endpoint, delays::add);

            assertTrue(summary.complete());
            assertEquals(0, summary.failed());
            assertEquals(List.of(1, 2, 3), endpoint.requestsAt("/api-3/campaigns/read")
                    .stream().map(Request::page).toList());
            assertEquals(List.of(1, 2), endpoint.requestsAt("/api-3/product_offer/read")
                    .stream().map(Request::page).toList());
            for (var request : endpoint.requestsAt("/api-3/product_offer/read")) {
                assertEquals(PNK, request.body().get("part_number_key").getAsString());
                assertEquals(100, request.body().get("itemsPerPage").getAsInt());
                assertFalse(request.body().has("status"));
            }
            for (var request : endpoint.requestsAt("/api-3/campaigns/read")) {
                assertEquals(Set.of("currentPage", "itemsPerPage"), request.body().keySet());
            }
            var categories = endpoint.requestsAt("/api-3/campaign_categories/read");
            assertEquals(3, categories.size());
            assertEquals(1, categories.stream().filter(r -> !r.body().has("campaign_id")).count());
            assertEquals(Set.of(11L, 13L), categories.stream().filter(r -> r.body().has("campaign_id"))
                    .map(r -> r.body().get("campaign_id").getAsLong()).collect(Collectors.toSet()));
            for (var request : categories) {
                assertEquals(1, request.page());
            }
            for (long id : List.of(11L, 12L, 13L)) {
                var samples = endpoint.requestsAt("/api-3/campaigns/" + id + "/eligible-offers");
                assertEquals(1, samples.size(), "Fetch just one sample, even when a next link is present");
                assertEquals("pageSize=50", samples.getFirst().uri().getRawQuery());
            }
            assertEquals(1, endpoint.requestsAt("/api-3/offers/" + OFFER_ID
                    + "/campaign_product_badge_timeline").size());
            var prices = endpoint.requestsAt("/api-3/smart-deals-price-check");
            assertEquals(1, prices.size());
            assertEquals("productId=" + OFFER_ID, prices.getFirst().uri().getRawQuery());
            assertTrue(endpoint.requestsAt("/api-3/offers/99/campaign_product_badge_timeline").isEmpty());
            var expectedAuthorization = "Basic " + Base64.getEncoder()
                    .encodeToString("campaign-user:secret-test-password".getBytes(StandardCharsets.UTF_8));
            for (var request : endpoint.requests) {
                assertEquals(expectedAuthorization, request.authorization());
                assertFalse(request.path().contains("/save"));
                assertFalse(request.path().contains("/api-3/api-3"));
                assertEquals(request.path().endsWith("/read") ? "POST" : "GET", request.method());
                assertFalse(request.body().has("data"));
            }
            assertEquals(endpoint.requests.size() - 1, delays.size());
            assertTrue(delays.stream().allMatch(delay -> delay >= 400));
            assertTrue(archivedBodies().contains(rawSample), "Keep the original JSON, decimals and UTF-8");
            assertArchiveOmitsCredentials();
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void archivesHttpApiAndMalformedErrorsAndContinuesIndependentRequests() throws Exception {
        var httpError = " {\"isError\":true,\"messages\":[\"No category access\"],\"extra\":123} \n";
        var apiError = "{\"isError\":true,\"messages\":[\"Campaign unavailable\"],\"results\":[]}";
        var malformed = "<html>not JSON: eroare</html>";
        var unexpected = "{\"isError\":false,\"results\":{\"target_price\":12.3400,\"new_field\":true}}";
        try (var endpoint = new Endpoint(request -> {
            if (request.path().equals("/api-3/campaign_categories/read")) return new Reply(403, httpError);
            if (request.path().endsWith("/eligible-offers")) return ok(apiError);
            if (request.path().endsWith("/campaign_product_badge_timeline")) return ok(malformed);
            if (request.path().endsWith("/smart-deals-price-check")) return ok(unexpected);
            return usualReply(request);
        })) {
            var summary = run(endpoint, ignored -> {});

            assertFalse(summary.complete());
            assertTrue(summary.failed() >= 3);
            assertEquals(1, endpoint.requestsAt("/api-3/smart-deals-price-check").size());
            assertTrue(archivedBodies().contains(httpError));
            assertTrue(archivedBodies().contains(apiError));
            assertTrue(archivedBodies().contains(unexpected));
            assertTrue(archivedBodies().stream().map(JsonParser::parseString)
                    .anyMatch(body -> body.isJsonObject() && body.getAsJsonObject().has("rawBody")
                            && malformed.equals(body.getAsJsonObject().get("rawBody").getAsString())));
            var calls = index().getAsJsonArray("calls");
            assertTrue(calls.asList().stream().map(element -> element.getAsJsonObject())
                    .anyMatch(call -> "error".equals(call.get("outcome").getAsString())));
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void missingExactProductMatchSkipsProductEndpointsButStillExploresCampaigns() throws Exception {
        try (var endpoint = new Endpoint(request -> {
            if (request.path().equals("/api-3/product_offer/read")) {
                return ok(request.page() == 1
                        ? "{\"isError\":false,\"results\":[{\"id\":99,\"part_number_key\":\"OTHERPNK\"}]}"
                        : EMPTY);
            }
            return usualReply(request);
        })) {
            var summary = run(endpoint, ignored -> {});

            assertTrue(summary.skipped() >= 2);
            assertEquals(1, endpoint.requestsAt("/api-3/campaigns/11/eligible-offers").size());
            assertTrue(endpoint.requests.stream().noneMatch(r -> r.path().startsWith("/api-3/offers/")));
            assertTrue(endpoint.requestsAt("/api-3/smart-deals-price-check").isEmpty());
            assertTrue(index().getAsJsonArray("calls").asList().stream()
                    .map(element -> element.getAsJsonObject())
                    .filter(call -> "skipped".equals(call.get("outcome").getAsString()))
                    .allMatch(call -> call.has("reason") && !call.get("reason").getAsString().isBlank()));
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void repeatedCatalogPageStopsPaginationAndReportsIncompleteCoverage() throws Exception {
        try (var endpoint = new Endpoint(request -> request.path().equals("/api-3/campaigns/read")
                ? ok(CAMPAIGN) : usualReply(request))) {
            var summary = run(endpoint, ignored -> {});

            assertFalse(summary.complete());
            assertEquals(List.of(1, 2), endpoint.requestsAt("/api-3/campaigns/read")
                    .stream().map(Request::page).toList());
            assertEquals(1, endpoint.requestsAt("/api-3/campaigns/11/eligible-offers").size());
            assertFalse(index().get("campaignsPaginationComplete").getAsBoolean());
            endpoint.assertNoHandlerFailure();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void boundsRetriesAndRetainsEveryReceivedAttempt(int status) throws Exception {
        var attempts = new AtomicInteger();
        var delays = new ArrayList<Long>();
        try (var endpoint = new Endpoint(request -> {
            if (request.path().equals("/api-3/campaigns/11/eligible-offers")) {
                return new Reply(status, "{\"attempt\":" + attempts.incrementAndGet() + "}");
            }
            return usualReply(request);
        })) {
            var summary = run(endpoint, delays::add);

            assertEquals(3, attempts.get());
            assertFalse(summary.complete());
            assertTrue(summary.failed() >= 1);
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertTrue(archivedBodies().contains("{\"attempt\":" + attempt + "}"));
            }
            assertTrue(delays.contains(1_000L));
            assertTrue(delays.contains(2_000L));
            assertEquals(1, endpoint.requestsAt("/api-3/smart-deals-price-check").size());
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void successfulRetryKeepsRateLimitResponseWithoutMarkingRunIncomplete() throws Exception {
        var attempts = new AtomicInteger();
        var rateLimitBody = "{\"message\":\"API rate limit exceeded\"}";
        try (var endpoint = new Endpoint(request -> {
            if (request.path().equals("/api-3/campaign_categories/read") && attempts.getAndIncrement() == 0) {
                return new Reply(429, rateLimitBody);
            }
            return usualReply(request);
        })) {
            var summary = run(endpoint, ignored -> {});

            assertTrue(summary.complete());
            assertEquals(0, summary.failed());
            assertEquals(3, endpoint.requestsAt("/api-3/campaign_categories/read").size());
            assertTrue(archivedBodies().contains(rateLimitBody));
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void interruptionStopsRequestsAndKeepsCapturedResponses() throws Exception {
        try (var endpoint = new Endpoint(request -> ok(EMPTY))) {
            try {
                assertThrows(InterruptedException.class, () -> run(endpoint, ignored -> {
                    if (!endpoint.requests.isEmpty()) throw new InterruptedException("test interruption");
                }));
                assertEquals(1, endpoint.requests.size());
                assertTrue(archivedBodies().contains(EMPTY));
            } finally {
                Thread.interrupted();
            }
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void archiveFailureStopsBeforeAnotherNetworkRequest() throws Exception {
        var output = directory.resolve("output");
        try (var endpoint = new Endpoint(request -> {
            Files.move(output, directory.resolve("retained"));
            Files.writeString(output, "This file blocks response archiving.");
            return ok(EMPTY);
        }); var stdout = new PrintStream(new ByteArrayOutputStream())) {
            var runner = new FetchCampaigns(CREDENTIALS, endpoint.apiRoot(), output, PNK, ignored -> {});

            assertThrows(IOException.class, () -> runner.run(stdout));
            assertEquals(1, endpoint.requests.size());
            endpoint.assertNoHandlerFailure();
        }
    }

    @Test
    void helpNeedsNoCredentialsAndCreatesNoRunDirectory() throws Exception {
        var before = runDirectories();
        var originalOut = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            FetchCampaigns.main(new String[]{"--help", "--account=does-not-exist-for-this-test"});
        } finally {
            System.setOut(originalOut);
        }

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("--account"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("--pnk"));
        assertEquals(before, runDirectories());
    }

    private FetchCampaigns.Summary run(Endpoint endpoint, FetchCampaigns.Sleeper sleeper) throws Exception {
        try (var stdout = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            return new FetchCampaigns(CREDENTIALS, endpoint.apiRoot(), directory, PNK, sleeper).run(stdout);
        }
    }

    private JsonObject index() throws IOException {
        return JsonParser.parseString(Files.readString(directory.resolve("index.json"))).getAsJsonObject();
    }

    private List<String> archivedBodies() throws IOException {
        var bodies = new ArrayList<String>();
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".json"))
                    .filter(file -> !file.getFileName().toString().equals("index.json")).toList()) {
                bodies.add(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return bodies;
    }

    private void assertArchiveOmitsCredentials() throws IOException {
        var indexText = Files.readString(directory.resolve("index.json"));
        assertFalse(indexText.contains("campaign-user"));
        assertFalse(indexText.contains("secret-test-password"));
        assertFalse(indexText.toLowerCase().contains("authorization"));
    }

    private static Set<Path> runDirectories() throws IOException {
        try (var files = Files.list(Path.of("/tmp"))) {
            return files.filter(file -> file.getFileName().toString().startsWith("emag-campaigns-"))
                    .collect(Collectors.toSet());
        }
    }

    private static Reply usualReply(Request request) {
        if (request.path().equals("/api-3/product_offer/read")) return ok(request.page() == 1 ? PRODUCT : EMPTY);
        if (request.path().equals("/api-3/campaigns/read")) return ok(request.page() == 1 ? CAMPAIGN : EMPTY);
        return ok(EMPTY);
    }

    private static Reply ok(String body) {
        return new Reply(200, body);
    }

    private record Reply(int status, String body) {}

    private record Request(String method, URI uri, JsonObject body, String authorization) {
        String path() {
            return uri.getPath();
        }

        int page() {
            return body.get("currentPage").getAsInt();
        }
    }

    @FunctionalInterface
    private interface Responder {
        Reply reply(Request request) throws Exception;
    }

    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

        private Endpoint(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    Reply reply;
                    try {
                        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                                body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject(),
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        requests.add(request);
                        reply = responder.reply(request);
                    } catch (Throwable failure) {
                        handlerFailure.compareAndSet(null, failure);
                        reply = new Reply(500, "test handler failed");
                    }
                    var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private URI apiRoot() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api-3");
        }

        private List<Request> requestsAt(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).toList();
        }

        private void assertNoHandlerFailure() {
            if (handlerFailure.get() != null) {
                throw new AssertionError("HTTP test handler failed", handlerFailure.get());
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
