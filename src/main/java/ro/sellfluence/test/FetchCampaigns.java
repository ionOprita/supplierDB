package ro.sellfluence.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Samples the read-only campaign APIs, retaining their responses without opening a database. */
public final class FetchCampaigns {
    private static final URI API_ROOT = URI.create("https://marketplace-api.emag.ro/api-3");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .setStrictness(Strictness.STRICT).create();
    private static final int MAX_ATTEMPTS = 3;
    private static final long REQUEST_DELAY_MILLIS = 400;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI apiRoot;
    private final Path directory;
    private final String pnk;
    private final String authorization;
    private final Sleeper sleeper;
    private final JsonObject index = new JsonObject();
    private final JsonArray calls = new JsonArray();
    private boolean requestSent;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    record Summary(int succeeded, int failed, int skipped, boolean complete) {}

    FetchCampaigns(UserPassword credentials, URI apiRoot, Path directory, String pnk, Sleeper sleeper) {
        this.apiRoot = URI.create(apiRoot.toString().replaceAll("/+$", ""));
        this.directory = directory;
        this.pnk = pnk;
        this.sleeper = sleeper;
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                (credentials.getUsername() + ":" + credentials.getPassword()).getBytes(StandardCharsets.UTF_8));
        index.addProperty("account", credentials.getAlias());
        index.addProperty("pnk", pnk);
        index.addProperty("startedAt", Instant.now().toString());
        index.addProperty("mode", "Full campaign catalog; first detail page per campaign");
        index.add("assumptions", JSON.toJsonTree(List.of(
                "campaign_categories/read campaign_id is an exploratory selector omitted from the documentation; "
                        + "HTTP success does not establish that the filter was honored.",
                "Smart Deals productId is interpreted as the seller offer ID; this interpretation needs live verification.",
                "Smart Deals is requested with /api-3 once; the documented path repeats the server prefix.",
                "Eligible offers are unfiltered samples. A missing PNK in a sample does not establish ineligibility.")));
        index.add("calls", calls);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var arguments = new Arguments(args);
        if (arguments.hasFlag("help") || arguments.hasFlag("h")) {
            System.out.println("Usage: FetchCampaigns [--account=<alias>] [--pnk=<PNK>] [--help]");
            System.out.println("Defaults: --account=sellfusion --pnk=DDHSVQMBM");
            System.out.println("Output: a fresh /tmp/emag-campaigns-<unique-run>/ directory, including index.json.");
            System.out.println("Reads the campaign catalog and one detail page per campaign; no database or API writes.");
            return;
        }
        for (var argument : args) {
            if (!argument.startsWith("--account=") && !argument.startsWith("--pnk=")) {
                throw new IllegalArgumentException("Unknown argument '%s'. Use --help for usage.".formatted(argument));
            }
        }
        var account = arguments.getOption("account", "sellfusion").strip();
        var pnk = arguments.getOption("pnk", "DDHSVQMBM").strip();
        if (account.isEmpty() || pnk.isEmpty()) {
            throw new IllegalArgumentException("Both --account and --pnk must be nonblank.");
        }
        var credentials = UserPassword.findAlias(account);
        if (credentials == null) {
            throw new IllegalArgumentException("No credentials found for '%s' in ~/Secrets/userpws.txt.".formatted(account));
        }
        var directory = Files.createTempDirectory(Path.of("/tmp"), "emag-campaigns-");
        System.out.println("Saving campaign API captures to " + directory);
        try {
            new FetchCampaigns(credentials, API_ROOT, directory, pnk, Thread::sleep).run(System.out);
        } catch (IOException | InterruptedException exception) {
            System.err.println("Exploration stopped; captured responses are retained at " + directory);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        }
    }

    Summary run(PrintStream stdout) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        index.addProperty("status", "running");
        saveIndex();
        try {
            var offers = readPages("product-offers", "/product_offer/read", Map.of("part_number_key", pnk));
            var matchingOffers = offers.entrySet().stream()
                    .filter(entry -> pnk.equals(string(entry.getValue().get("part_number_key"))))
                    .toList();
            index.add("matchingOfferIds", JSON.toJsonTree(matchingOffers.stream().map(Map.Entry::getKey).toList()));
            var campaigns = readPages("campaigns", "/campaigns/read", Map.of());
            index.addProperty("campaignCount", campaigns.size());

            // The published schema specifies pagination only, despite describing a campaign-specific response.
            request("categories-baseline", "/campaign_categories/read", Map.of("currentPage", 1, "itemsPerPage", 100));
            for (var entry : campaigns.entrySet()) {
                long campaignId = entry.getKey();
                var rules = entry.getValue().get("participationRules");
                if (rules != null && rules.isJsonObject() && isBoolean(rules.getAsJsonObject().get("restrictedCategories"), true)) {
                    var categories = request("campaign-" + campaignId + "-categories-probe", "/campaign_categories/read",
                            Map.of("campaign_id", campaignId, "currentPage", 1, "itemsPerPage", 100));
                    categories.call().addProperty("assumption", "campaign_id selector is undocumented; support unconfirmed");
                }
                var eligible = request("campaign-" + campaignId + "-eligible-offers",
                        "/campaigns/" + campaignId + "/eligible-offers?pageSize=50", null);
                eligible.call().addProperty("sampleOnly", true);
                var results = results(eligible);
                if (results != null) {
                    var matches = new JsonArray();
                    for (var result : results) {
                        if (result.isJsonObject() && pnk.equals(string(result.getAsJsonObject().get("pnk")))) {
                            matches.add(result);
                        }
                    }
                    eligible.call().add("matchingPnkResults", matches);
                }
                saveIndex();
            }
            if (campaigns.isEmpty()) {
                skip("eligible-offers", "No campaign IDs were returned; per-campaign requests could not be made.");
            }
            for (var entry : matchingOffers) {
                long offerId = entry.getKey();
                request("offer-" + offerId + "-badges", "/offers/" + offerId + "/campaign_product_badge_timeline", null);
                var smartDeals = request("offer-" + offerId + "-smart-deals", "/smart-deals-price-check?productId=" + offerId, null);
                smartDeals.call().addProperty("assumption", "productId is interpreted as the seller offer ID; support unconfirmed");
            }
            if (matchingOffers.isEmpty()) {
                var reason = "No exact PNK match with a usable seller offer ID was found for " + pnk;
                skip("badge-timeline", reason);
                skip("smart-deals", reason);
            }
            var summary = summary();
            index.addProperty("status", summary.complete() ? "complete" : "incomplete");
            index.addProperty("finishedAt", Instant.now().toString());
            index.add("summary", JSON.toJsonTree(summary));
            saveIndex();
            stdout.printf("Exploration %s: %d successful, %d failed, %d skipped calls. Captures: %s%n",
                    summary.complete() ? "complete" : "incomplete", summary.succeeded(), summary.failed(), summary.skipped(), directory);
            return summary;
        } catch (IOException | InterruptedException exception) {
            index.addProperty("status", "aborted");
            index.addProperty("reason", exception.toString());
            for (var element : calls) {
                var call = element.getAsJsonObject();
                if ("pending".equals(string(call.get("outcome")))) {
                    error(call, "Exploration aborted: " + exception.getClass().getSimpleName());
                }
            }
            try {
                saveIndex();
            } catch (IOException archiveException) {
                exception.addSuppressed(archiveException);
            }
            throw exception;
        }
    }

    /** Page until empty, retaining distinct records even if a later page fails or repeats. */
    private Map<Long, JsonObject> readPages(String operation, String resource, Map<String, Object> filters)
            throws IOException, InterruptedException {
        var records = new LinkedHashMap<Long, JsonObject>();
        boolean complete = false;
        for (int page = 1; ; page++) {
            var parameters = new LinkedHashMap<>(filters);
            parameters.put("currentPage", page);
            parameters.put("itemsPerPage", 100);
            var response = request(operation + "-page-" + page, resource, parameters);
            var results = results(response);
            if (results == null) {
                break;
            }
            if (results.isEmpty()) {
                complete = true;
                break;
            }
            int before = records.size();
            for (var result : results) {
                var id = result.isJsonObject() ? positiveId(result.getAsJsonObject().get("id")) : null;
                if (id == null) {
                    error(response.call(), "A result has no usable positive integer id; record skipped.");
                    continue;
                }
                records.putIfAbsent(id, result.getAsJsonObject());
            }
            if (records.size() == before) {
                error(response.call(), "Pagination stopped: nonempty page contained no new IDs; catalog may be incomplete.");
                break;
            }
        }
        index.addProperty(operation + "PaginationComplete", complete);
        saveIndex();
        return records;
    }

    private record Response(JsonObject call, JsonObject body) {}

    /** Only callers above select endpoints; this helper is never exposed as a general-purpose API client. */
    private Response request(String operation, String resource, Map<String, Object> body)
            throws IOException, InterruptedException {
        var url = URI.create(apiRoot + resource);
        var call = new JsonObject();
        call.addProperty("operation", operation);
        call.addProperty("method", body == null ? "GET" : "POST");
        call.addProperty("url", url.toString());
        if (body != null) {
            call.add("requestBody", JSON.toJsonTree(body));
        }
        call.addProperty("outcome", "pending");
        var attempts = new JsonArray();
        call.add("attempts", attempts);
        calls.add(call);
        saveIndex();
        var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization).header("Accept", "application/json");
        var request = body == null ? builder.GET().build() : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body), StandardCharsets.UTF_8)).build();
        for (int number = 1; number <= MAX_ATTEMPTS; number++) {
            if (requestSent) {
                sleeper.sleep(REQUEST_DELAY_MILLIS);
            }
            requestSent = true;
            var attempt = new JsonObject();
            attempt.addProperty("number", number);
            attempts.add(attempt);
            HttpResponse<String> http;
            try {
                http = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                attempt.addProperty("outcome", "transport_error");
                attempt.addProperty("reason", exception.toString());
                saveIndex();
                if (number < MAX_ATTEMPTS) {
                    sleeper.sleep(1_000L << (number - 1));
                    continue;
                }
                error(call, "Transport failure after " + number + " attempts: " + exception);
                saveIndex();
                return new Response(call, null);
            }
            attempt.addProperty("status", http.statusCode());
            var file = "%04d-%s-attempt-%d.json".formatted(calls.size(), operation, number);
            attempt.addProperty("file", file);
            // First persist a valid JSON capture even if decoding the body will fail.
            var capture = directory.resolve(file);
            Files.writeString(capture, JSON.toJson(Map.of("rawBody", http.body())), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            saveIndex();
            JsonElement decoded = null;
            try {
                decoded = JSON.fromJson(http.body(), JsonElement.class);
            } catch (JsonParseException exception) {
                attempt.addProperty("parseError", exception.getMessage());
            }
            if (decoded != null) {
                // Keep valid JSON verbatim: no DTO conversion, reformatting, or numeric rounding.
                Files.writeString(capture, http.body(), StandardCharsets.UTF_8);
            }
            attempt.addProperty("responseFormat", decoded == null ? "rawBody capture" : "original JSON");
            int status = http.statusCode();
            if (status < 200 || status >= 300) {
                attempt.addProperty("outcome", "http_error");
                if ((status == 429 || status >= 500) && number < MAX_ATTEMPTS) {
                    saveIndex();
                    sleeper.sleep(retryDelay(http, number));
                    continue;
                }
                error(call, "HTTP " + status);
            } else if (decoded == null || !decoded.isJsonObject()) {
                attempt.addProperty("outcome", "invalid_response");
                error(call, "Expected a JSON API response object; captured the original body.");
            } else {
                var root = decoded.getAsJsonObject();
                if (!isBoolean(root.get("isError"), false)) {
                    attempt.addProperty("outcome", "api_error");
                    error(call, isBoolean(root.get("isError"), true)
                            ? "API returned isError=true; inspect the captured response."
                            : "Unrecognized API envelope: expected boolean isError=false.");
                } else {
                    attempt.addProperty("outcome", "success");
                    call.addProperty("outcome", "success");
                    saveIndex();
                    return new Response(call, root);
                }
            }
            saveIndex();
            return new Response(call, null);
        }
        throw new IllegalStateException("Request attempts exhausted without an outcome");
    }

    private static long retryDelay(HttpResponse<?> response, int attempt) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return Math.max(1, Math.min(60, Long.parseLong(header.get()))) * 1_000;
            } catch (NumberFormatException ignored) {
                // Non-numeric Retry-After values use the bounded backoff below.
            }
        }
        return 1_000L << (attempt - 1);
    }

    private static JsonArray results(Response response) {
        if (response.body() == null) {
            return null;
        }
        var results = response.body().get("results");
        if (results == null || !results.isJsonArray()) {
            error(response.call(), "Expected a results array for pagination/sample matching; response retained unchanged.");
            return null;
        }
        return results.getAsJsonArray();
    }

    private static boolean isBoolean(JsonElement element, boolean expected) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean() == expected;
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static Long positiveId(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            long id = element.getAsBigDecimal().longValueExact();
            return id > 0 ? id : null;
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static void error(JsonObject call, String reason) {
        call.addProperty("outcome", "error");
        call.addProperty("reason", reason);
    }

    private void skip(String operation, String reason) throws IOException {
        var call = new JsonObject();
        call.addProperty("operation", operation);
        call.addProperty("outcome", "skipped");
        call.addProperty("reason", reason);
        calls.add(call);
        saveIndex();
    }

    private Summary summary() {
        int succeeded = 0, failed = 0, skipped = 0;
        for (var element : calls) {
            switch (string(element.getAsJsonObject().get("outcome"))) {
                case "success" -> succeeded++;
                case "skipped" -> skipped++;
                default -> failed++;
            }
        }
        return new Summary(succeeded, failed, skipped, failed == 0 && skipped == 0);
    }

    private void saveIndex() throws IOException {
        Files.writeString(directory.resolve("index.json"), JSON.toJson(index), StandardCharsets.UTF_8);
    }
}
