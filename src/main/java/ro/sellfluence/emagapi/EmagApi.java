package ro.sellfluence.emagapi;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import ro.sellfluence.support.Logs;

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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.sheetSupport.Conversions.isoLikeLocalDateTime;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

/**
 * Class for dealing with the emag API.
 * <p>
 * Based on API version 4.4.6.
 */
public class EmagApi {

    private static final Logger logger = Logger.getLogger(EmagApi.class.getName());

    private static final Logger communicationLogger = Logs.getFileLogger("emag_communication", INFO, 20, 100_000_000);

    private static final Logger jsonLogger = Logs.getFileLogger("emag_decoded_json", FINE, 20, 100_000_000);

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";
    private static final String countOrders = orderURI + "/count";

    private final String credentials;
    private final HttpClient httpClient;

    private static final DateTimeFormatter emagDate = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static int statusFinalized = 4;

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                @Override
                public void write(JsonWriter writer, LocalDateTime time) throws IOException {
                    if (time == null) {
                        writer.nullValue();
                    } else {
                        writer.value(isoLikeLocalDateTime.format(time));
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

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .registerModule(new SimpleModule()
                    .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer())
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
                    .addSerializer(LocalDate.class, new LocalDateSerializer())
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer()));
    //.registerModule(new JavaTimeModule());

    private static class LocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(formatter.format(value));
        }
    }

    private static class LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return toLocalDateTime(p.getText());
        }
    }

    private static class LocalDateSerializer extends JsonSerializer<LocalDate> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(formatter.format(value));
        }
    }

    private static class LocalDateDeserializer extends JsonDeserializer<LocalDate> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return LocalDate.parse(p.getText(), formatter);
        }
    }


    public EmagApi(String username, String password) {
        credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        httpClient = HttpClient.newHttpClient();
    }

    public CountResponse countOrderRequest() throws IOException, InterruptedException {
        var receivedJSON = countOrderRequestRaw("");
        CountResponse counterResponse = null;
        if (receivedJSON!=null) {
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
                    logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                    if (Arrays.stream(response.messages).anyMatch(x -> x.contains("Invalid vendor ip"))) {
                        logger.log(INFO, "Please register your IP address in the EMAG dashboard.");
                    }
                } else {
                    jsonLogger.log(FINE, () -> "Decoded JSON: %s".formatted(response));
                    counterResponse = response.results;
                }
            } catch (MismatchedInputException e) {
                String msg = "JSON decoded ended with error %s".formatted(e.getMessage());
                logger.log(SEVERE, msg);
                logger.log(SEVERE, receivedJSON);
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
        int statusCode = httpResponse.statusCode();
        communicationLogger.log(FINE, "Status code = " + statusCode);
        String receivedJSON = null;
        if (statusCode == HTTP_OK) {
            receivedJSON = httpResponse.body();
        }
        return receivedJSON;
    }

    public <T> List<T> readRequest(String category, Map<String, Object> filter, Map<String, Object> data, Class<T> responseClass) throws IOException, InterruptedException {
        var page = 0;
        var finished = false;
        var url = emagRO + "/" + category + "/read";
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
        var retryCount = 4;
        var retryDelay = 10_000;
        while (!finished) {
            page++;
            jsonInput.put("currentPage", page);
            // Filter items are on the first level together with the pagination items.
            // The data item, which is also on the first level, is used only for submitting data.
            var jsonAsString = gson.toJson(jsonInput);
            jsonLogger.log(FINE, "JSON = " + jsonAsString);
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonAsString)).build();
            communicationLogger.log(INFO, () -> "Sent " + jsonAsString);
            try {
                var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int statusCode = httpResponse.statusCode();
                communicationLogger.log(FINE, "Status code = " + statusCode);
                if (statusCode == HTTP_OK) {
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
                            logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                            if (Arrays.stream(response.messages).anyMatch(x -> x.contains("Invalid vendor ip"))) {
                                logger.log(INFO, "Please register your IP address in the EMAG dashboard.");
                                finished = true;
                            }
                        } else {
                            logger.log(INFO, () -> "Received %d items.".formatted(response.results.length));
                            jsonLogger.log(FINE, () -> "Decoded JSON: %s".formatted(response));
                            if (response.results.length > 0) {
                                accumulatedResponses.addAll(Arrays.asList(response.results));
                            } else {
                                finished = true;
                            }
                        }
                    } catch (MismatchedInputException e) {
                        String message = "JSON decoded ended with error %s".formatted(e.getMessage());
                        logger.log(SEVERE, message);
                        logger.log(SEVERE, receivedJSON);
                        throw new RuntimeException(message, e);
                    }
                } else if (statusCode == HTTP_GATEWAY_TIMEOUT && retryCount > 0) {
                    logger.log(WARNING, "Received 504, retrying, retryCount=%d, retryDelay=%d s".formatted(retryCount, retryDelay / 1000));
                    retryCount--;
                    Thread.sleep(retryDelay);
                    retryDelay *= 2; // Double delay
                    page--; // Refetch the same page.
                } else {
                    logger.log(SEVERE, "Received error status %s".formatted(statusCode));
                    throw new RuntimeException(String.format("Emag API error %d", statusCode));
                }
            } catch (IOException e) {
                logger.log(WARNING, "Received IOException %s, %n retrying, retryCount=%d, retryDelay=%d s".formatted(e.getMessage(), retryCount,retryDelay/1000));
                retryCount--;
                Thread.sleep(retryDelay);
                retryDelay *= 2; // Double delay
                page--; // Refetch the same page.
            }
        }
        return accumulatedResponses;
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