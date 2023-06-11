package ro.koppel.supplierDB;

import java.net.URLEncoder;

import static java.nio.charset.StandardCharsets.UTF_8;

public class HelperMethods {

    private static final String defaultApiKey = "8e2b076fd0a742a4abbc1a52bec5a456";

    /**
     * Convenience method that uses a fixed api key.
     *
     * @param url original page URL
     * @return url for using ScrapingAnt
     */
    public static String buildScrapingAntURL(String url) {
        return buildScrapingAntURL(url, defaultApiKey);
    }

    /**
     * @param url    original page URL
     * @param apiKey key required by ScrapingAnt for the right to use the API.
     * @return url for using ScrapingAnt
     */
    public static String buildScrapingAntURL(String url, String apiKey) {
        return "https://api.scrapingant.com/v2/general?url=" + URLEncoder.encode(url, UTF_8) + "&x-api-key=" + apiKey + "&proxy_country=CN";
    }
}