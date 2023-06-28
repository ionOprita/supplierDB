package ro.koppel.supplierDB;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class HelperMethods {

    private static final String[] proxyCountries = new String[]{"AF", "AL", "DZ", "AS", "AD", "AO", "AI", "AQ", "AG", "AR", "AM", "AW", "AU", "AT", "AZ", "BS", "BH", "BD", "BB", "BY", "BE", "BZ", "BJ", "BM", "BT", "BO", "BA", "BW", "BV", "BR", "IO", "BN", "BG", "BF", "BI", "KH", "CM", "CA", "CV", "KY", "CF", "TD", "CL", "CN", "CX", "CC", "CO", "KM", "CG", "CD", "CK", "CR", "CI", "HR", "CU", "CY", "CZ", "DK", "DJ", "DM", "DO", "EC", "EG", "SV", "GQ", "ER", "EE", "ET", "FK", "FO", "FJ", "FI", "FR", "GF", "PF", "TF", "GA", "GM", "GE", "DE", "GH", "GI", "GR", "GL", "GD", "GP", "GU", "GT", "GN", "GW", "GY", "HT", "HM", "VA", "HN", "HK", "HU", "IS", "IN", "ID", "IR", "IQ", "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KI", "KP", "KR", "KW", "KG", "LA", "LV", "LB", "LS", "LR", "LY", "LI", "LT", "LU", "MO", "MK", "MG", "MW", "MY", "MV", "ML", "MT", "MH", "MQ", "MR", "MU", "YT", "MX", "FM", "MD", "MC", "MN", "MS", "MA", "MZ", "MM", "NA", "NR", "NP", "NL", "NC", "NZ", "NI", "NE", "NG", "NU", "NF", "MP", "NO", "OM", "PK", "PW", "PS", "PA", "PG", "PY", "PE", "PH", "PN", "PL", "PT", "PR", "QA", "RE", "RO", "RU", "RW", "SH", "KN", "LC", "PM", "VC", "WS", "SM", "ST", "SA", "SN", "SC", "SL", "SG", "SK", "SI", "SB", "SO", "ZA", "GS", "SS", "ES", "LK", "SD", "SR", "SJ", "SZ", "SE", "CH", "SY", "TW", "TJ", "TZ", "TH", "TL", "TG", "TK", "TO", "TT", "TN", "TR", "TM", "TC", "TV", "UG", "UA", "AE", "GB", "US", "UM", "UY", "UZ", "VU", "VE", "VN", "VG", "VI", "WF", "EH", "YE", "ZM", "ZW"};
    private static final Random random = new Random();
    private static String proxyCountry = proxyCountries[0];
    @Value("${ro.koppel.apikey}")
    private String configuredApiKey;

    private static String getRandomOne() {
        String newCountry;
        do {
            newCountry = proxyCountries[random.nextInt(proxyCountries.length)];
        } while (newCountry == proxyCountry);
        return newCountry;
    }

    public static void replaceProxy() {
        proxyCountry = getRandomOne();
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
}