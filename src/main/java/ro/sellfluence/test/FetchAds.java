package ro.sellfluence.test;

import com.bastiaanjansen.otp.TOTPGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.hc.core5.net.URIBuilder;
import ro.sellfluence.emagapi.AdsCampaign;
import ro.sellfluence.emagapi.AdsCampaignPhrasesResponse;
import ro.sellfluence.emagapi.AdsCampaignTargetedProductsResponse;
import ro.sellfluence.emagapi.AdsCampaignsResponse;
import ro.sellfluence.support.UserPassword;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Random;

import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

public class FetchAds {
    static void main() throws Exception {
        fetchFrom("sellfusion");
    }

    static final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(
                    new SimpleModule()
                            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
            ).build();

    public static String genOTP(String otpAuth) {
        var otpUri = URI.create(otpAuth);
        try {
            TOTPGenerator totp = TOTPGenerator.fromURI(otpUri);
            return totp.now();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Random random = new Random();

    private static void randomWait(Double fromSec, Double toSec) {
        var waitSec = fromSec + (toSec - fromSec) * random.nextDouble();
        try {
            Thread.sleep((long) waitSec * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Get the JSON either from file or from url
     *
     * @param page within the request is executed.
     * @param path to the cache file.
     * @param url from which to fetch the data.
     * @return
     * @throws IOException
     */
    private static String getJSON(Page page, Path path, String url) throws IOException {
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        var json = page.request().get(url).text();
        Files.writeString(path, json);
        return page.request().get(url).text();
    }

    public static void fetchFrom(String alias) throws IOException, URISyntaxException {
        var user = UserPassword.findAlias(alias);
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false))) {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                // login(page, user);
                downloadData(page);
            }
        }
    }

    private static final Path targetDir = Path.of("AdsJSON");

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }

    private static void downloadData(Page page) throws IOException, URISyntaxException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        var endDate = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));
        var startDate = endDate.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        while (LocalDate.of(2026,1,1).isBefore(startDate)) {
            //downloadData(page, startDate, endDate);
            IO.println("%s - %s".formatted(startDate, endDate));
            startDate = startDate.minusDays(7);
            endDate = endDate.minusDays(7);
        }
    }

    private static URIBuilder skeleton(LocalDate startDate, LocalDate endDate, int pageNumber) {
        URIBuilder uriBuilder = new URIBuilder(URI.create("https://advertising.emag.net/api/v1/"));
        uriBuilder.addParameter("page", Integer.toString(pageNumber));
        uriBuilder.addParameter("perPage", "1000");
        uriBuilder.addParameter("dateStart", startDate.toString());
        uriBuilder.addParameter("dateEnd", endDate.toString());
        return uriBuilder;
    }

   private static void downloadData(Page page, LocalDate startDate, LocalDate endDate) {
        var campaigns = downloadCampaigns(page,startDate,endDate);
//        var pageNumber = 1;
//
//        URIBuilder uriBuilder = new URIBuilder(advertisingAPI);
//        uriBuilder.appendPath("campaigns");
//        var uri = uriBuilder.build();
//        uriBuilder.setParameter("page", Integer.toString(pageNumber));
//        //--
//        uriBuilder.appendPath("campaign/%d/adsets".formatted(campaignId));
//        uriBuilder.setParameter("campaignId",Integer.toString(campaignId));
//        //--
//        uriBuilder.appendPath("campaigns/%d/search-phrases".formatted(campaignId));
//        uriBuilder.setParameter("adsetId",Integer.toString(adSetId));
//        //--
//        uriBuilder.appendPath("campaigns/%d/adsets/%d/targeted-products".formatted(campaignId, adSetId));
//
//
//
////        var adsAnalyticsJSON = getJSON(page, "https://advertising.emag.net/api/v1/analytics/campaign?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=spent&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-05-20&dateEnd=2026-06-19");
////        var adsAnalytics = objectMapper.readValue(adsAnalyticsJSON, AdsAnalyticsResponse.class);
////        IO.println(adsAnalytics);
////        IO.println(adsAnalytics.data().summary());
//        var adsCampaignJSON = getJSON(pageNumber, "https://advertising.emag.net/api/v1/campaigns?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=spent&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-05-20&dateEnd=2026-06-19");
//        var adsCampaign = objectMapper.readValue(adsCampaignJSON, AdsCampaignsResponse.class);
//        IO.println(adsCampaign);
//        adsCampaign.data().campaigns().stream().map(AdsCampaign::name).forEach(IO::println);
//        //---
//        var adsCampaignAdSetsJSON = getJSON(pageNumber, "https://advertising.emag.net/api/v1/campaign/505390/adsets?campaignId=505390&page=1&perPage=10&sort%5B0%5D%5Bfield%5D=id&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-06-04&dateEnd=2026-07-03");
//        Files.writeString(targetDir.resolve("adsCampaignAdSets.json"), adsCampaignAdSetsJSON);
//        var adsCampaignPhrasesJSON = getJSON(pageNumber, "https://advertising.emag.net/api/v1/campaigns/174138/search-phrases?adsetId=180461&page=1&perPage=100&sort%5B0%5D%5Bfield%5D=impressions&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-06-01&dateEnd=2026-06-30");
//        Files.writeString(targetDir.resolve("adsCampaignPhrases.json"), adsCampaignPhrasesJSON);
//        var adsCampaignPhrases = objectMapper.readValue(adsCampaignPhrasesJSON, AdsCampaignPhrasesResponse.class);
//        IO.println(adsCampaignPhrases);
//        var adsCampaignTargetedProductsJSON = getJSON(pageNumber, "https://advertising.emag.net/api/v1/campaigns/174138/adsets/180461/targeted-products?page=1&perPage=100&sort%5B0%5D%5Bfield%5D=clicks&sort%5B0%5D%5Bdirection%5D=desc&dateStart=2026-06-01&dateEnd=2026-06-30");
//        Files.writeString(targetDir.resolve("adsCampaignTargetedProducts.json"), adsCampaignTargetedProductsJSON);
//        var adsCampaignTargetedProducts = objectMapper.readValue(adsCampaignTargetedProductsJSON, AdsCampaignTargetedProductsResponse.class);
//        IO.println(adsCampaignTargetedProducts);
   }

    private static AdsCampaignsResponse downloadCampaigns(Page page, LocalDate startDate, LocalDate endDate) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        do {
            var uri = skeleton(startDate,endDate,pageNumber).appendPath("campaigns").build();
            var path = targetDir.resolve("adsCampaigns_%s_%s_%d.json".formatted(startDate,endDate,pageNumber));
            var json = getJSON(page,path,uri.toASCIIString());
            var adsCampaign = objectMapper.readValue(json, AdsCampaignsResponse.class);
            var meta = adsCampaign.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);

    }

    private static void login(Page page, UserPassword user) {
        page.navigate("https://auth.emag.net/login?adk=PQ02EfRdOtbhV7N2");
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your username or e-mail")).dblclick();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your username or e-mail")).fill(user.getUsername());
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Enter your password")).fill(user.getPassword());
        randomWait(2.0, 2.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Introdu OTP")).click();
        randomWait(2.0, 7.0);
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Introdu OTP")).fill(genOTP(user.getOtpAuth()));
        randomWait(1.0, 1.0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Logare")).click();
        randomWait(2.0, 2.0);
        page.navigate("https://marketplace.emag.ro/dashboard");
        randomWait(2.0, 2.0);
        page.navigate("https://advertising.emag.net/");
    }
}
