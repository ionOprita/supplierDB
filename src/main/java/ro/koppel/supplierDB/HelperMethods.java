package ro.koppel.supplierDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class HelperMethods {

    @Value("${ro.koppel.apikey}")
    private String configuredApiKey;

    private static final String[] proxyCountries = new String[]{"CN", "JP", "US", "UK", "AU", "RO", "BR", "IN", "FR"};

    private static String proxyCountry = proxyCountries[0];

    private static final Random random = new Random();

    private static String getRandomOne() {
        String newCountry;
        do {
            newCountry = proxyCountries[random.nextInt(proxyCountries.length)];
        } while (newCountry == proxyCountry);
        return newCountry;
    }

    /**
     * Convenience method that uses a fixed api key.
     *
     * @param url original page URL
     * @return url for using ScrapingAnt
     */
    public String buildScrapingAntURL(String url) {
        return buildScrapingAntURL(url, configuredApiKey);
    }

    /**
     * @param url    original page URL
     * @param apiKey key required by ScrapingAnt for the right to use the API.
     * @return url for using ScrapingAnt
     */
    public String buildScrapingAntURL(String url, String apiKey) {
        return "https://api.scrapingant.com/v2/general?url=" + URLEncoder.encode(url, UTF_8) + "&x-api-key=" + apiKey + "&proxy_country=" + proxyCountry;
    }

    public static void replaceProxy() {
        proxyCountry = getRandomOne();
    }
}