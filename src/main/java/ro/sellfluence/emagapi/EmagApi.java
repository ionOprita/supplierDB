package ro.sellfluence.emagapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jspecify.annotations.NonNull;
import ro.sellfluence.support.Logs;
import ro.sellfluence.support.UserPassword;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.sheetSupport.Conversions.isoLikeLocalDateTimeWithoutFractionalSeconds;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

/**
 * Class for dealing with the emag API.
 * <p>
 * Based on API version 4.4.6.
 */
public class EmagApi {

    private static final Logger logger = Logger.getLogger(EmagApi.class.getName());

    private static final Logger communicationLogger = Logs.getFileLogger("emag_communication", INFO, 20, 100_000_000);
    private static final Logger errorLogger = Logs.getConsoleAndFileLogger("emag_error", INFO, 20, 100_000_000);

    private static final Logger jsonLogger = Logs.getFileLogger("emag_decoded_json", FINE, 20, 100_000_000);

    private static final int MAX_REQUEST_RETRIES = 4;
    private static final long INITIAL_RETRY_DELAY_MILLISECONDS = 10_000;
    private static final long PRODUCT_OFFER_PAGE_DELAY_MILLISECONDS = 350;

    public static final String emagRO = "https://marketplace-api.emag.ro";

    public static final String emagROApi3 = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagROApi3 + "/order";

    private static final String countOrders = orderURI + "/count";

    private final String alias;
    private final String emagUser;
    private final String credentials;
    private final HttpClient httpClient;
    private final Sleeper sleeper;
    private final URI productOfferReadUri;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    /** Receives each successful HTTP response before it is decoded into offer records. */
    @FunctionalInterface
    public interface ProductOfferPageConsumer {
        void accept(int currentPage, String responseBody) throws IOException;
    }

    private static final DateTimeFormatter emagDate = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static int statusFinalized = 4;

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                @Override
                public void write(JsonWriter writer, LocalDateTime time) throws IOException {
                    if (time == null) {
                        writer.nullValue();
                    } else {
                        writer.value(isoLikeLocalDateTimeWithoutFractionalSeconds.format(time));
                    }
                }

                @Override
                public LocalDateTime read(JsonReader reader) throws IOException {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        return null;
                    }
                    return toLocalDateTime(reader.nextString());
                }
            })
            .registerTypeAdapter(LocalDate.class, new TypeAdapter<LocalDate>() {
                @Override
                public void write(JsonWriter writer, LocalDate time) throws IOException {
                    if (time == null) {
                        writer.nullValue();
                    } else {
                        writer.value(emagDate.format(time));
                    }
                }

                @Override
                public LocalDate read(JsonReader reader) throws IOException {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        return null;
                    }
                    return LocalDate.parse(reader.nextString(), emagDate);
                }
            })
            .create();

    private final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(
                    new SimpleModule()
                            .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer())
                            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
                            .addSerializer(LocalDate.class, new LocalDateSerializer())
                            .addDeserializer(LocalDate.class, new LocalDateDeserializer())
            ).build();

    private static class LocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext serializers) {
            gen.writeString(formatter.format(value));
        }
    }

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }

    private static class LocalDateSerializer extends ValueSerializer<LocalDate> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializationContext serializers) {
            gen.writeString(formatter.format(value));
        }
    }

    private static class LocalDateDeserializer extends ValueDeserializer<LocalDate> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) {
            return LocalDate.parse(p.getString(), formatter);
        }
    }


    public EmagApi(UserPassword emagCredentials) {
        this(emagCredentials, Thread::sleep);
    }

    EmagApi(UserPassword emagCredentials, Sleeper sleeper) {
        this(emagCredentials, sleeper, URI.create(emagROApi3 + "/product_offer/read"));
    }

    EmagApi(UserPassword emagCredentials, Sleeper sleeper, URI productOfferReadUri) {
        Objects.requireNonNull(emagCredentials, "eMAG credentials are required");
        alias = emagCredentials.getAlias();
        emagUser = emagCredentials.getUsername();
        credentials = Base64.getEncoder().encodeToString((emagCredentials.getUsername() + ":" + emagCredentials.getPassword()).getBytes());
        httpClient = HttpClient.newHttpClient();
        this.sleeper = sleeper;
        this.productOfferReadUri = Objects.requireNonNull(productOfferReadUri);
    }

    /**
     * Reads one product-offer page without decoding its body. Filters are top-level keys;
     * explicit pagination arguments take precedence over pagination keys in {@code filters}.
     * HTTP 429/500/502/504 and transport failures receive at most four retries.
     */
    public String readProductOffersRaw(Map<String, Object> filters, int currentPage, int itemsPerPage)
            throws IOException, InterruptedException {
        if (currentPage < 1) {
            throw new IllegalArgumentException("currentPage must be at least 1");
        }
        if (itemsPerPage < 1 || itemsPerPage > 100) {
            throw new IllegalArgumentException("itemsPerPage must be between 1 and 100");
        }
        var requestBody = new HashMap<String, Object>();
        if (filters != null) {
            if (filters.containsKey("data")) {
                throw new IllegalArgumentException("Product-offer read filters must be top-level keys, without a data wrapper");
            }
            requestBody.putAll(filters);
        }
        requestBody.put("currentPage", currentPage);
        requestBody.put("itemsPerPage", itemsPerPage);
        var json = gson.toJson(requestBody);
        var request = HttpRequest.newBuilder(productOfferReadUri)
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        var retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS;
        for (int attempt = 0; ; attempt++) {
            communicationLogger.log(INFO, () -> "Sent product_offer/read " + json);
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt == MAX_REQUEST_RETRIES) {
                    throw new IOException("Product-offer page %d failed after %d attempts".formatted(currentPage, attempt + 1), e);
                }
                communicationLogger.log(WARNING, "Product-offer page %d transport failure; retrying in %d ms".formatted(currentPage, retryDelay), e);
                sleeper.sleep(retryDelay);
                retryDelay *= 2;
                continue;
            }
            checkAuthenticationError(response);
            int status = response.statusCode();
            communicationLogger.log(INFO, () -> "Received product_offer/read HTTP %d: %s".formatted(status, response.body()));
            if (status == HTTP_OK) {
                return response.body();
            }
            boolean retryable = status == 429 || status == HTTP_INTERNAL_ERROR
                    || status == HTTP_BAD_GATEWAY || status == HTTP_GATEWAY_TIMEOUT;
            if (!retryable || attempt == MAX_REQUEST_RETRIES) {
                throw new IOException("Product-offer page %d returned HTTP %d: %s".formatted(currentPage, status, response.body()));
            }
            communicationLogger.log(WARNING, "Product-offer page %d returned HTTP %d; retrying in %d ms".formatted(currentPage, status, retryDelay));
            sleeper.sleep(retryDelay);
            retryDelay *= 2;
        }
    }

    /**
     * Decodes a successful offer response, preserving undocumented offer fields.
     * Only an explicit false {@code isError} and an actual results array indicate success.
     */
    public ProductOfferResponse parseProductOffers(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Product-offer response is empty");
        }
        try {
            var root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Product-offer response must be a JSON object");
            }
            var isError = root.get("isError");
            if (isError == null || !isError.isBoolean()) {
                throw new IllegalArgumentException("Product-offer response must contain a boolean isError");
            }
            if (isError.asBoolean()) {
                throw new IllegalArgumentException("eMAG product_offer/read returned an error: messages=%s, errors=%s"
                        .formatted(root.get("messages"), root.get("errors")));
            }
            var results = root.get("results");
            if (results == null || !results.isArray()) {
                throw new IllegalArgumentException("Successful product-offer response must contain a results array");
            }
            for (var result : results) {
                if (!result.isObject()) {
                    throw new IllegalArgumentException("Product-offer results must contain JSON objects");
                }
            }
            // Decode the original text so decimal precision and scale do not pass through a tree's doubles.
            return objectMapper.readerFor(ProductOfferResponse.class)
                    .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                    .readValue(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not decode product-offer response: " + e.getMessage(), e);
        }
    }

    /** Reads all offer pages, including inactive and End of Life offers when no status filter is supplied. */
    public List<ProductOffer> readProductOffers(Map<String, Object> filters) throws IOException, InterruptedException {
        return readProductOffers(filters, (page, body) -> {});
    }

    /**
     * Reads all pages and invokes {@code rawResponseConsumer} before each page is decoded.
     * The final empty page is also delivered. Any fetch, capture or decoding failure aborts the read.
     */
    public List<ProductOffer> readProductOffers(Map<String, Object> filters, ProductOfferPageConsumer rawResponseConsumer)
            throws IOException, InterruptedException {
        Objects.requireNonNull(rawResponseConsumer, "Raw response consumer is required");
        var offers = new ArrayList<ProductOffer>();
        for (int currentPage = 1; ; currentPage++) {
            var body = readProductOffersRaw(filters, currentPage, 100);
            rawResponseConsumer.accept(currentPage, body);
            ProductOfferResponse response;
            try {
                response = parseProductOffers(body);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Product-offer page %d: %s".formatted(currentPage, e.getMessage()), e);
            }
            if (response.results().isEmpty()) {
                return offers;
            }
            offers.addAll(response.results());
            sleeper.sleep(PRODUCT_OFFER_PAGE_DELAY_MILLISECONDS);
        }
    }

    public CountResponse countOrderRequest() throws IOException, InterruptedException {
        var receivedJSON = countOrderRequestRaw("");
        CountResponse counterResponse = null;
        if (receivedJSON != null) {
            communicationLogger.log(FINE, () -> "Full response body: %s".formatted(receivedJSON));
            try {
                var typeRef = new TypeReference<SingleResponse<CountResponse>>() {
                    @Override
                    public Type getType() {
                        return objectMapper.getTypeFactory().constructParametricType(SingleResponse.class, CountResponse.class);
                    }
                };
                var response = objectMapper.readValue(receivedJSON, typeRef);

                if (response.isError) {
                    errorLogger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                    if (Arrays.stream(response.messages).anyMatch(x -> x.contains("Invalid vendor ip"))) {
                        errorLogger.log(INFO, "Please register your IP address in the EMAG dashboard for user %s.".formatted(emagUser));
                    }
                } else {
                    jsonLogger.log(FINE, () -> "Decoded JSON: %s".formatted(response));
                    counterResponse = response.results;
                }
            } catch (MismatchedInputException e) {
                String msg = "JSON decoded ended with error %s".formatted(e.getMessage());
                errorLogger.log(SEVERE, msg);
                errorLogger.log(SEVERE, receivedJSON);
                throw new RuntimeException(msg, e);
            }
        }
        return counterResponse;
    }

    public String countOrderRequestRaw(String requestBody) throws IOException, InterruptedException {
        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(countOrders))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        checkAuthenticationError(httpResponse);
        int statusCode = httpResponse.statusCode();
        communicationLogger.log(FINE, "Status code = " + statusCode);
        String receivedJSON = null;
        if (statusCode == HTTP_OK) {
            receivedJSON = httpResponse.body();
        }
        return receivedJSON;
    }

    public <T> List<T> readRequest(String category, Map<String, Object> filter, Map<String, Object> data, Class<T> responseClass) throws IOException, InterruptedException {
        return emagRequest(emagROApi3 + "/" +category + "/read", true, filter, data, responseClass);
    }

    public <T> @NonNull ArrayList<T> emagRequest(String url, boolean post, Map<String, Object> filter, Map<String, Object> data, Class<T> responseClass) throws InterruptedException {
        var page = 0;
        var finished = false;
        var jsonInput = new HashMap<String, Object>(
                Map.of("itemsPerPage", 100)
        );
        if (filter != null && !filter.isEmpty()) {
            jsonInput.putAll(filter);
        }
        if (data != null && !data.isEmpty()) {
            jsonInput.put("data", data);
        }
        var accumulatedResponses = new ArrayList<T>();
        var retryCount = MAX_REQUEST_RETRIES;
        var retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS;
        while (!finished) {
            page++;
            jsonInput.put("currentPage", page);
            // Filter items are on the first level together with the pagination items.
            // The data item, which is also on the first level, is used only for submitting data.
            var jsonAsString = gson.toJson(jsonInput);
            jsonLogger.log(FINE, "JSON = " + jsonAsString);
            var httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json");
            var httpRequest = post ? httpRequestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonAsString)).build() : httpRequestBuilder.GET().build();
            communicationLogger.log(INFO, () -> "Sent " + jsonAsString);
            try {
                var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                checkAuthenticationError(httpResponse);
                int statusCode = httpResponse.statusCode();
                communicationLogger.log(FINE, "Status code = " + statusCode);
                if (statusCode == HTTP_OK) {
                    retryCount = MAX_REQUEST_RETRIES;
                    retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS;
                    String receivedJSON = httpResponse.body();
                    communicationLogger.log(INFO, () -> "Received " + receivedJSON);
                    try {
                        //var responseItemClass = TypeToken.getParameterized(Response.class, responseClass);
                        //Response<T> response = gson.fromJson(receivedJSON, responseItemClass.getType());
                        var typeRef = new TypeReference<Response<T>>() {
                            @Override
                            public Type getType() {
                                return objectMapper.getTypeFactory().constructParametricType(Response.class, responseClass);
                            }
                        };
                        var response = objectMapper.readValue(receivedJSON, typeRef);

                        if (response.isError) {
                            errorLogger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                            if (Arrays.stream(response.messages).anyMatch(x -> x.contains("Invalid vendor ip"))) {
                                errorLogger.log(INFO, "Please register your IP address in the EMAG dashboard for user %s.".formatted(emagUser));
                                finished = true;
                            }
                        } else {
                            errorLogger.log(INFO, () -> "Received %d items.".formatted(response.results.length));
                            jsonLogger.log(FINE, () -> "Decoded JSON: %s".formatted(response));
                            if (response.results.length > 0) {
                                accumulatedResponses.addAll(Arrays.asList(response.results));
                            } else {
                                finished = true;
                            }
                        }
                    } catch (MismatchedInputException e) {
                        String message = "JSON decoded ended with error %s".formatted(e.getMessage());
                        errorLogger.log(SEVERE, message);
                        errorLogger.log(SEVERE, receivedJSON);
                        throw new RuntimeException(message, e);
                    }
                } else if (statusCode == HTTP_FORBIDDEN) {
                    errorLogger.log(SEVERE, "Received 403 for user %s, please check your password.".formatted(emagUser));
                    finished = true;
                } else if ((statusCode == HTTP_INTERNAL_ERROR || statusCode == HTTP_BAD_GATEWAY || statusCode == HTTP_GATEWAY_TIMEOUT) && retryCount > 0) {
                    errorLogger.log(WARNING, "Received %d, retrying, retryCount=%d, retryDelay=%d s".formatted(statusCode, retryCount, retryDelay / 1000));
                    retryCount--;
                    sleeper.sleep(retryDelay);
                    retryDelay *= 2; // Double delay
                    page--; // Refetch the same page.
                } else {
                    logger.log(SEVERE, "Received error status %s".formatted(statusCode));
                    throw new RuntimeException(String.format("Emag API error %d", statusCode));
                }
            } catch (IOException e) {
                if (retryCount > 0) {
                    logger.log(WARNING, "Received IOException %s, retrying, retryCount=%d, retryDelay=%d s".formatted(e, retryCount, retryDelay / 1000));
                } else {
                    throw new RuntimeException("Received IOException", e);
                }
                retryCount--;
                sleeper.sleep(retryDelay);
                retryDelay *= 2; // Double delay
                page--; // Refetch the same page.
            }
        }
        return accumulatedResponses;
    }

    private void checkAuthenticationError(HttpResponse<String> httpResponse) {
        var status = httpResponse.statusCode();
        if (status ==HTTP_UNAUTHORIZED || status==HTTP_FORBIDDEN) {
            logger.log(WARNING, "Authentication error for alias %s user %s, status code %s".formatted(alias, emagUser, status));
            EmagAccounts.invalidAccount(emagUser);
        }
    }

    public static void setAPILogLevel(Level level) {
        logger.setLevel(level);
        for (Handler handler : logger.getHandlers()) {
            handler.setLevel(level);
        }
        for (Handler handler : logger.getParent().getHandlers()) {
            handler.setLevel(level);
        }
    }
}
