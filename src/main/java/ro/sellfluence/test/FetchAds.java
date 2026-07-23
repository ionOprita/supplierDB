package ro.sellfluence.test;

import com.bastiaanjansen.otp.TOTPGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.hc.core5.net.URIBuilder;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.AdSet;
import ro.sellfluence.emagapi.AdsAdset;
import ro.sellfluence.emagapi.AdsCampaign;
import ro.sellfluence.emagapi.AdsCampaignAdSetsResponse;
import ro.sellfluence.emagapi.AdsCampaignKeywordsResponse;
import ro.sellfluence.emagapi.AdsCampaignPhrasesResponse;
import ro.sellfluence.emagapi.AdsCampaignTargetedProductsResponse;
import ro.sellfluence.emagapi.AdsCampaignsResponse;
import ro.sellfluence.emagapi.AdsKeyword;
import ro.sellfluence.emagapi.AdsSearchPhrase;
import ro.sellfluence.emagapi.AdsTargetedProduct;
import ro.sellfluence.emagapi.Campaign;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;
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
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

public class FetchAds {
    private static final boolean offline = Boolean.parseBoolean(System.getProperty("ads.offline", "false"));

    private static final Logger logger = Logs.getConsoleAndFileLogger("FetchAds", Level.INFO, 10, 100_000);

    static void main(String... args) throws Exception {
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        fetchFrom(mirrorDB, "sellfusion");
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
     * @param url  from which to fetch the data.
     * @return JSON read from either the file or url.
     * @throws IOException
     */
    private static String getJSON(Page page, Path path, String url) throws IOException {
        if (Files.exists(path)) {
            logger.log(Level.INFO, "Return %s for %s.".formatted(path, url));
            return Files.readString(path);
        }
        if (offline) {
            throw new RuntimeException("Could not proceed with loading %s because offline.".formatted(url));
        }
        randomWait(0.1, 0.5);
        var json = page.request().get(url).text();
        Files.writeString(path, json);
        logger.log(Level.INFO, "Retrieved %s and stored to %s.".formatted(url, path));
        return json;
    }

    public static void fetchFrom(EmagMirrorDB mirrorDB, String alias) throws IOException, URISyntaxException, SQLException {
        var user = UserPassword.findAlias(alias);
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false))) {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                if (!offline) {
                    login(page, user);
                }
                transferDataToDB(mirrorDB, page);
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

    private static void transferDataToDB(EmagMirrorDB mirrorDB, Page page) throws IOException, URISyntaxException, SQLException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        var currentDate = LocalDate.now();
        var endDate = currentDate.minusDays(31);
        while (endDate.isBefore(currentDate)) {
            currentDate = currentDate.minusDays(1);
            var campaigns = downloadData(page, currentDate);
            updatedDatabase(mirrorDB, campaigns);
        }
    }

    /**
     * Updated the database using the data found in campaigns, avoiding creating duplicates.
     *
     * @param mirrorDB  database to use.
     * @param campaigns new data to add.
     */
    private static void updatedDatabase(EmagMirrorDB mirrorDB, ArrayList<Campaign> campaigns) throws SQLException {
        var changedRows = mirrorDB.addOrUpdateAdCampaigns(campaigns);
        logger.log(Level.INFO, "Inserted or updated %d ads rows from %d campaigns.".formatted(changedRows, campaigns.size()));
    }

    private static URIBuilder skeleton(LocalDate date, int pageNumber) {
        URIBuilder uriBuilder = new URIBuilder(URI.create("https://advertising.emag.net/api/v1"));
        uriBuilder.addParameter("page", Integer.toString(pageNumber));
        uriBuilder.addParameter("perPage", "100");
        uriBuilder.addParameter("dateStart", date.toString());
        uriBuilder.addParameter("dateEnd", date.toString());
        return uriBuilder;
    }

    private static ArrayList<Campaign> downloadData(Page page, LocalDate date) throws IOException, URISyntaxException {
        var campaigns = downloadCampaigns(page, date);
        var campaignList = new ArrayList<Campaign>();
        for (var campaign : campaigns) {
            var adSets = downloadAdSets(page, date, campaign.id());
            var adSetList = new ArrayList<AdSet>();
            for (var adSet : adSets) {
                var searchPhrases = downloadSearchPhrases(page, date, campaign.id(), adSet.id());
                var targetedProducts = downloadTargetedProducts(page, date, campaign.id(), adSet.id());
                var keywords = downloadKeywords(page, date, campaign.id(), adSet.id());
                adSetList.add(new AdSet(adSet, searchPhrases, targetedProducts, keywords));
            }
            campaignList.add(new Campaign(date, campaign, adSetList));
        }
        return campaignList;
    }

    private static List<AdsCampaign> downloadCampaigns(Page page, LocalDate date) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        List<AdsCampaign> result = new ArrayList<>();
        do {
            var uri = skeleton(date, pageNumber).appendPath("campaigns")
                    .setParameter("page", Integer.toString(pageNumber))
                    .build();
            var path = targetDir.resolve("adsCampaigns_%s_%d.json".formatted(date, pageNumber));
            var json = getJSON(page, path, uri.toASCIIString());
            var response = objectMapper.readValue(json, AdsCampaignsResponse.class);
            result.addAll(response.data().campaigns());
            var meta = response.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);
        return result;
    }

    private static List<AdsAdset> downloadAdSets(Page page, LocalDate date, int campaignId) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        List<AdsAdset> result = new ArrayList<>();
        do {
            var uri = skeleton(date, pageNumber)
                    .appendPath("campaign/%d/adsets".formatted(campaignId))
                    .setParameter("page", Integer.toString(pageNumber))
                    .setParameter("campaignId", Integer.toString(campaignId))
                    .build();
            var path = targetDir.resolve("adsAdSets_%s_%d_%d.json".formatted(date, pageNumber, campaignId));
            var json = getJSON(page, path, uri.toASCIIString());
            var response = objectMapper.readValue(json, AdsCampaignAdSetsResponse.class);
            result.addAll(response.data().adsets());
            var meta = response.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);
        return result;
    }

    private static List<AdsSearchPhrase> downloadSearchPhrases(Page page, LocalDate date, int campaignId, int adSetId) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        List<AdsSearchPhrase> result = new ArrayList<>();
        do {
            var uri = skeleton(date, pageNumber)
                    .appendPath("campaigns/%d/search-phrases".formatted(campaignId))
                    .setParameter("page", Integer.toString(pageNumber))
                    .setParameter("adsetId", Integer.toString(adSetId))
                    .build();
            var path = targetDir.resolve("adsSearchPhrases_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId));
            var json = getJSON(page, path, uri.toASCIIString());
            var response = objectMapper.readValue(json, AdsCampaignPhrasesResponse.class);
            result.addAll(response.data().searchPhrases());
            var meta = response.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);
        return result;
    }

    private static List<AdsTargetedProduct> downloadTargetedProducts(Page page, LocalDate date, int campaignId, int adSetId) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        List<AdsTargetedProduct> result = new ArrayList<>();
        do {
            var uri = skeleton(date, pageNumber)
                    .appendPath("campaigns/%d/adsets/%s/targeted-products".formatted(campaignId, adSetId))
                    .setParameter("page", Integer.toString(pageNumber))
                    .setParameter("dateEnd", date.plusDays(1).toString())
                    .build();
            var path = targetDir.resolve("adsTargetedProducts_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId));
            var json = getJSON(page, path, uri.toASCIIString());
            var response = objectMapper.readValue(json, AdsCampaignTargetedProductsResponse.class);
            result.addAll(response.data().docs());
            var meta = response.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);
        return result;
    }

    private static List<AdsKeyword> downloadKeywords(Page page, LocalDate date, int campaignId, int adSetId) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages = 0;
        List<AdsKeyword> result = new ArrayList<>();
        do {
            var uri = skeleton(date, pageNumber)
                    .appendPath("campaign/%d/keywords".formatted(campaignId))
                    .setParameter("page", Integer.toString(pageNumber))
                    .setParameter("campaignId", Integer.toString(campaignId))
                    .setParameter("adsetId", Integer.toString(adSetId))
                    .build();
            var path = targetDir.resolve("adsKeywords_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId));
            if (offline && !Files.exists(path)) {
                logger.log(Level.INFO, "Skip keywords for campaign %d, adset %d, and date %s because %s is not cached."
                        .formatted(campaignId, adSetId, date, path));
                break;
            }
            var json = getJSON(page, path, uri.toASCIIString());
            var response = objectMapper.readValue(json, AdsCampaignKeywordsResponse.class);
            result.addAll(response.data().keywords());
            var meta = response.meta();
            totalPages = meta.pageCount();
            pageNumber++;
        } while (pageNumber <= totalPages);
        return result;
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
