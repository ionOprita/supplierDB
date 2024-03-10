package ro.koppel.emag;

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

    private static final DateTimeFormatter emagDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                @Override
                public void write(JsonWriter writer, LocalDateTime time) throws IOException {
                    if (time == null) {
                        writer.nullValue();
                    } else {
                        writer.value(emagDate.format(time));
                    }
                }

                @Override
                public LocalDateTime read(JsonReader reader) throws IOException {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        return null;
                    }
                    return LocalDateTime.parse(reader.nextString(), emagDate);
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
                Map.of("itemsPerPage", 5)
        );
        if (filter != null && !filter.isEmpty()) {
            jsonInput.putAll(filter);
        }
        if (data != null && !data.isEmpty()) {
            jsonInput.put("data", data);
        }
        var accumulatedResponses = new ArrayList<T>();
        while (!finished && page < 3) {
            page++;
            jsonInput.put("currentPage", page);
            System.out.println("Requesting page " + page);
            // Filter items are on the first level together with the pagination items.
            // The data item which is also on the first level is used only for submitting data.
            var jsonAsString = gson.toJson(jsonInput);
            logger.log(INFO, "JSON = " + jsonAsString);
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonAsString)).build();
            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResponse.statusCode();
            logger.log(INFO, "Status code = " + statusCode);
            if (statusCode == HTTP_OK) {
                String receivedJSON = httpResponse.body();
                logger.log(INFO, () -> "Full response body: %s".formatted(receivedJSON));
                try {
                    var responseItemClass = TypeToken.getParameterized(Response.class, responseClass);
                    Response<T> response = gson.fromJson(receivedJSON, responseItemClass.getType());
                    if (response.isError) {
                        logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                    } else {
                        logger.log(INFO, "Decoded JSON: " + response);
                        System.out.printf("Received %d results%n", response.results.length);
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
            }
        }
        return accumulatedResponses;
    }

//    public static void main(String[] args) throws IOException, InterruptedException {
//        var user = System.getenv("EMAG_USER");
//        var pw = System.getenv("EMAG_PASS");
//        var emag = new EmagApi(user, pw);
//        var startDate = LocalDate.of(2024, 2, 20).atStartOfDay();
//        var endDate = now().minusDays(13).atStartOfDay();
//        var response = emag.readRequest("order",
//                Map.of("status",
//                        4,
//                        "createdAfter",
//                        emagDate.format(startDate),
//                        "createdBefore",
//                        emagDate.format(endDate)),
//                null,
//                OrderResult.class);
//        System.out.println(response);
//    }
}

/*
Liebe Eltern, Liebe Schülerinnen

Nachdem ich mehrere Jahre an verschiedenen Schulen unterrichtete, eröffnete ich anfangs 1999 meine eigene Ballettschule Tanz-Atelier an der Wallisellenstrasse 23 in Dübendorf. Innerhalb kurzer Zeit wuchs diese auf drei Studios. Im Jahr 2014 musste ich zwei davon wegen Umbauten des Gebäudes wieder verlassen.

Durch den Lockdown sank die Zahl der Schülerinnen nochmals und nun ist die Zeit Reif für einen Wiederaufbau. Doch wie mir in Gesprächen mit Freunden und Familie klar wurde, braucht es dafür jemand der denselben Elan aufbringen kann, den ich vor 25 Jahren hatte.

Ich habe mich deshalb schweren Herzens dafür entschieden die Schule an Frau Larissa Tritten zu verkaufen. In Ihr habe ich eine würdige Nachfolgerin gefunden, die das nötige Feuer hat, um den Erfolg des Tanz-Ateliers weiterzuführen und auszubauen. Sie liebt den Tanz und ist sehr motiviert diese Freude den Schüler*innen zu vermitteln.

Frau Larissa Tritten wird nach den Sommerferien alle Stunden unterrichten.

In den 25 Jahren durfte ich viele Schüler*innen auf ihrem tänzerischen Weg begleiten und viele schöne Aufführungen mit ihnen erleben. Das einige Schülerinnen noch immer da sind erfreut mich sehr und der Abschied wird schmerzen.
 */