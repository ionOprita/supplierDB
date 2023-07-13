package ro.koppel.emag;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.logging.Level.*;

public class YesterdaysFinalizedOrders {

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private static final DateTimeFormatter emagFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:");

    private static final Logger logger = Logger.getLogger(YesterdaysFinalizedOrders.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Please supply username and password as arguments.");
            System.exit(1);
        }
        var username = args[0];
        var password = args[1];
        var today00 = LocalDate.now().atTime(0, 0, 0, 0);
        var yesterday00 = today00.minusDays(1);
        String inputJSON = """
                "data": {
                    "status": 4,
                    "modifiedBefore": "%s",
                    "modifiedAfter": "%s"
                }
                """.formatted(today00.format(emagFormat), yesterday00.format(emagFormat));
        var credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        var httpClient = HttpClient.newHttpClient();
        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(readOrder))
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(inputJSON))
                .build();
        logger.log(FINE, "Sending request %s to %s".formatted(inputJSON, readOrder));
        var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int statusCode = httpResponse.statusCode();
        logger.log(FINE, "Status code = " + statusCode);
        if (statusCode == HTTP_OK) {
            String receivedJSON = httpResponse.body();
            logger.log(FINE, () -> "Full response body: %s".formatted(receivedJSON));
            try {
                Response response = new Gson().fromJson(receivedJSON, Response.class);
                if (response.isError) {
                    logger.log(SEVERE, "Received error response %s".formatted(Arrays.toString(response.messages)));
                } else {
                    logger.log(INFO, "Decoded JSON: " + response);
                }
            } catch (JsonSyntaxException e) {
                logger.log(SEVERE, "JSON decoded ended with error %s".formatted(e.getMessage()));
                logger.log(INFO, receivedJSON);
            }
        } else {
            logger.log(SEVERE, "Received error status %s".formatted(statusCode));
        }
    }
}
