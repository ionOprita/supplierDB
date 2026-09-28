package ro.sellfluence.emagsiteapi;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


public class EmagSAPI {

    private static final String BASE_URL =
            "https://sapi.emag.ro/products/%s/reviews"
                    + "?fields%%5Bitems%%5D=1"
                    + "&fields%%5Bitems%%5D%%5Bcontent_no_tags%%5D=1"
                    + "&page%%5Blimit%%5D=100"
                    + "&page%%5Boffset%%5D=%d";

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void getReviews(String pnk) throws Exception {
        var offset = 0;
        String url = BASE_URL.formatted(pnk, offset);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Request-Source", "mobile-app")
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "eMAG returned HTTP " + response.statusCode());
        }
    }

    static void main() throws Exception {
        getReviews("DDHSVQMBM");
    }
}
