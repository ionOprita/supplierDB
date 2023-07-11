package ro.koppel.emag;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.UTF_8;

public class YesterdaysFinalizedOrders {

    private static final String emagRO = "https://marketplace-api.emag.ro/api-3";

    private static final String orderURI = emagRO + "/order";

    private static final String readOrder = orderURI + "/read";

    private static final DateTimeFormatter emagFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:");


    public static void main(String[] args) throws Exception {
        var today00 = LocalDate.now().atTime(0, 0, 0, 0);
        var yesterday00 = today00.minusDays(1);
        String inputJSON = """
                "data": {
                    "status": 4,
                    "modifiedBefore": "%s",
                    "modifiedAfter": "%s"
                }
                """.formatted(today00.format(emagFormat), yesterday00.format(emagFormat));
        var connection = URI.create(readOrder).toURL().openConnection();
        try (var writer = new PrintWriter(connection.getOutputStream())) {
            writer.println(inputJSON);
            writer.flush();
        }
        try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8))) {
            new Gson().fromJson(reader, OrderResult.class);
        }
    }
}
