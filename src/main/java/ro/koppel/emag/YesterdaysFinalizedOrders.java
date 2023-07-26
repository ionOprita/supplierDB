package ro.koppel.emag;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.logging.Level.*;

public class YesterdaysFinalizedOrders {

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private static final DateTimeFormatter emagFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger logger = Logger.getLogger(YesterdaysFinalizedOrders.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Please supply username and password as arguments.");
            System.exit(1);
        }
        var username = args[0];
        var password = args[1];
//        var today00 = LocalDate.now().atTime(0, 0, 0, 0);
//        var yesterday00 = today00.minusDays(1);
        var startTime = LocalDate.of(2023,7,12)
                .atStartOfDay();
        var endTime = LocalDate.of(2023,7,20).plusDays(1).atStartOfDay();
        String inputJSON = """
                "data": {
                    "status": 1,
                    "createdBefore": "%s",
                    "createdAfter": "%s"
                }
                """.formatted(endTime.format(emagFormat), startTime.format(emagFormat));
        System.out.println(inputJSON);
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
                    processResponse(response.results);
                }
            } catch (JsonSyntaxException e) {
                logger.log(SEVERE, "JSON decoded ended with error %s".formatted(e.getMessage()));
                logger.log(INFO, receivedJSON);
            }
        } else {
            logger.log(SEVERE, "Received error status %s".formatted(statusCode));
        }
    }

    private static void processResponse(OrderResult[] results) throws GeneralSecurityException, IOException {
        List<List<Object>> values = new ArrayList<>();
        for(var orderResult: results){
            for(Product product: orderResult.products){
                List<Object> row = new ArrayList<>();
                row.add(orderResult.id);
                row.add(product.part_number_key);
                row.add(orderResult.date);
                row.add(dateConversion(orderResult.date));
                values.add(row);
            }
        }
        SheetsQuickstart.update(values, "A1:D" + values.size());
    }

    private static String dateConversion(String date) {
        DateTimeFormatter emag;
        DateTimeFormatter excel;
        emag = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        excel = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        return LocalDateTime.parse(date, emag).format(excel);
    }
}
