package ro.sellfluence.emagapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
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
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

/**
 * Class for dealing with the emag API.
 * <p>
 * Based on API version 4.4.6.
 */
public class EmagApi {

    private static final Logger logger = Logger.getLogger(EmagApi.class.getName());

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private final String credentials;
    private final HttpClient httpClient;

    private static final DateTimeFormatter emagDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter emagDate = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static int statusFinalized = 4;

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                @Override
                public void write(JsonWriter writer, LocalDateTime time) throws IOException {
                    if (time == null) {
                        writer.nullValue();
                    } else {
                        writer.value(emagDateTime.format(time));
                    }
                }

                @Override
                public LocalDateTime read(JsonReader reader) throws IOException {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        return null;
                    }
                    return LocalDateTime.parse(reader.nextString(), emagDateTime);
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

    public EmagApi(String username, String password) {
        credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        httpClient = HttpClient.newHttpClient();
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
        while (!finished) {
            page++;
            jsonInput.put("currentPage", page);
            System.out.println("Requesting page " + page);
            // Filter items are on the first level together with the pagination items.
            // The data item which is also on the first level is used only for submitting data.
            var jsonAsString = gson.toJson(jsonInput);
            logger.log(FINE, "JSON = " + jsonAsString);
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonAsString)).build();
            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResponse.statusCode();
            logger.log(FINE, "Status code = " + statusCode);
            if (statusCode == HTTP_OK) {
                String receivedJSON = httpResponse.body();
                logger.log(FINE, () -> "Full response body: %s".formatted(receivedJSON));
                try {
                    var responseItemClass = TypeToken.getParameterized(Response.class, responseClass);
                    Response<T> response = gson.fromJson(receivedJSON, responseItemClass.getType());
                    if (response.isError) {
                        logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                        if (Arrays.stream(response.messages).anyMatch(x -> x.contains("Invalid vendor ip"))) {
                            logger.log(INFO, "Please register your IP address in the EMAG dashboard.");
                            finished = true;
                        }
                    } else {
                        logger.log(INFO, () -> "Received %d items.".formatted(response.results.length));
                        logger.log(FINE, () -> "Decoded JSON: %s".formatted(response));
                        if (response.results.length > 0) {
                            accumulatedResponses.addAll(Arrays.asList(response.results));
                        } else {
                            finished = true;
                        }
                    }
                } catch (JsonSyntaxException e) {
                    logger.log(SEVERE, "JSON decoded ended with error %s".formatted(e.getMessage()));
                    logger.log(INFO, receivedJSON);
                }
            } else {
                logger.log(SEVERE, "Received error status %s".formatted(statusCode));
                throw new RuntimeException(String.format("Emag API error %d", statusCode));
            }
        }
        return accumulatedResponses;
    }
}