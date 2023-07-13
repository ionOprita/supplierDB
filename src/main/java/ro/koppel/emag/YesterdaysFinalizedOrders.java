package ro.koppel.emag;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public class YesterdaysFinalizedOrders {

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private static final DateTimeFormatter emagFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Please supply username and password as arguments.");
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
        var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        System.out.println("RC = " + httpResponse.statusCode());
        var response = new Gson().fromJson(httpResponse.body(), Response.class);
        System.out.println(response);
    }
}
