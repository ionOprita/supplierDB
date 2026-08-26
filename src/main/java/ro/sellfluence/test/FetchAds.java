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
import ro.sellfluence.emagapi.AdsError;
import ro.sellfluence.emagapi.AdsKeyword;
import ro.sellfluence.emagapi.AdsResponse;
import ro.sellfluence.emagapi.AdsSearchPhrase;
import ro.sellfluence.emagapi.AdsTargetedProduct;
import ro.sellfluence.emagapi.Campaign;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;
import ro.sellfluence.support.UserPassword;
import tools.jackson.core.JacksonException;
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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

public class FetchAds {
    private static final boolean offline = Boolean.parseBoolean(System.getProperty("ads.offline", "false"));

    private static final Logger logger = Logs.getConsoleAndFileLogger("FetchAds", INFO, 10, 100_000);

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
     * @throws IOException on communication errors.
     */
    private static String getJSON(Page page, Path path, String url, boolean doWait) throws IOException {
        if (Files.exists(path)) {
//            logger.log(INFO, "Return %s for %s.".formatted(path, url));
            return Files.readString(path);
        }
        if (offline) {
            throw new RuntimeException("Could not proceed with loading %s because offline.".formatted(url));
        }
        if (doWait) randomWait(0.1, 0.5);
        var json = page.request().get(url).text();
        Files.writeString(path, json);
        logger.log(INFO, "Retrieved %s and stored to %s.".formatted(url, path));
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
        logger.log(INFO, "Inserted or updated %d ads rows from %d campaigns.".formatted(changedRows, campaigns.size()));
    }

    private static URIBuilder skeleton(LocalDate date, int pageNumber) {
        URIBuilder uriBuilder = new URIBuilder(URI.create("https://advertising.emag.net/api/v1"));
        uriBuilder.addParameter("page", Integer.toString(pageNumber));
        uriBuilder.addParameter("perPage", "1000");
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
            var searchPhrases = downloadSearchPhrases(page, date, campaign.id());
            var searchPhrasesByAdSet = searchPhrases.stream().collect(Collectors.groupingBy(AdsSearchPhrase::adsetId));
            for (var adSet : adSets) {
                var targetedProducts = downloadTargetedProducts(page, date, campaign.id(), adSet.id());
                var keywords = downloadKeywords(page, date, campaign.id(), adSet.id());
                adSetList.add(new AdSet(adSet, searchPhrasesByAdSet.get(adSet.id()), targetedProducts, keywords
                ));
            }
            campaignList.add(new Campaign(date, campaign, adSetList));
        }
        return campaignList;
    }

    private static List<AdsCampaign> downloadCampaigns(Page page, LocalDate date) throws URISyntaxException, IOException {
        return downloadPages(
                page,
                pageNumber -> skeleton(date, pageNumber).appendPath("campaigns")
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> targetDir.resolve("adsCampaigns_%s_%d.json".formatted(date, pageNumber)),
                AdsCampaignsResponse.class,
                response -> response.data.campaigns()
        );
    }

    private static List<AdsAdset> downloadAdSets(Page page, LocalDate date, int campaignId) throws URISyntaxException, IOException {
        return downloadPages(
                page,
                pageNumber -> skeleton(date, pageNumber)
                        .appendPath("campaign/%d/adsets".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId)),
                pageNumber -> targetDir.resolve("adsAdSets_%s_%d_%d.json".formatted(date, pageNumber, campaignId)),
                AdsCampaignAdSetsResponse.class,
                response -> response.data.adsets()
        );
    }

    private static List<AdsSearchPhrase> downloadSearchPhrases(Page page, LocalDate date, int campaignId) throws URISyntaxException, IOException {
        return downloadPages(
                page,
                pageNumber -> skeleton(date, pageNumber)
                        .appendPath("campaigns/%d/search-phrases".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> targetDir.resolve("adsSearchPhrases_%s_%d_%d.json".formatted(date, pageNumber, campaignId)),
                AdsCampaignPhrasesResponse.class,
                response -> response.data.searchPhrases()
        );
    }

    private static List<AdsTargetedProduct> downloadTargetedProducts(Page page, LocalDate date, int campaignId, int adSetId) throws URISyntaxException, IOException {
        return downloadPages(
                page,
                pageNumber -> skeleton(date, pageNumber)
                        .appendPath("campaigns/%d/adsets/%s/targeted-products".formatted(campaignId, adSetId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("dateEnd", date.plusDays(1).toString()),
                pageNumber -> targetDir.resolve("adsTargetedProducts_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId)),
                AdsCampaignTargetedProductsResponse.class,
                response -> response.data.docs()
        );
    }

    private static List<AdsKeyword> downloadKeywords(Page page, LocalDate date, int campaignId, int adSetId) throws URISyntaxException, IOException {
        return downloadPages(
                page,
                pageNumber -> skeleton(date, pageNumber)
                        .appendPath("campaign/%d/keywords".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId))
                        .setParameter("adsetId", Integer.toString(adSetId)),
                pageNumber -> targetDir.resolve("adsKeywords_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId)),
                AdsCampaignKeywordsResponse.class,
                response -> response.data.keywords()
        );
    }

    static class EMAGException extends RuntimeException {
        public AdsResponse response;
        EMAGException(AdsResponse response) {
            this.response = response;
        }
    }

    private static <T, R extends AdsResponse> List<T> downloadPages(
            Page page,
            IntFunction<URIBuilder> uriForPage,
            IntFunction<Path> pathForPage,
            Class<R> responseType,
            Function<R, List<T>> itemsFromResponse
    ) throws URISyntaxException, IOException {
        int pageNumber = 1;
        int totalPages;
        List<T> result = new ArrayList<>();
        URI uri = null;
        try {
            do {
                uri = uriForPage.apply(pageNumber).build();
                var path = pathForPage.apply(pageNumber);
                var json = getJSON(page, path, uri.toASCIIString(), pageNumber == 1);
                var response = decodeJSON(json, responseType);
                result.addAll(itemsFromResponse.apply(response));
                totalPages = response.meta.pageCount();
                pageNumber++;
            } while (pageNumber <= totalPages);
        } catch (EMAGException e) {
            logger.log(WARNING, "Skipping over URI %s because of error %s".formatted(uri, e.response));
        }
        return result;
    }

    private static <T extends AdsResponse> T decodeJSON(String json, Class<T> valueType) {
        T response;
        try {
            response = objectMapper.readValue(json, valueType);
        } catch (JacksonException e) {
            logger.log(SEVERE, "Cannot decode JSON %s".formatted(json));
            throw new RuntimeException(e);
        }
        if (response.error != null && !response.error.isBlank()) {
            logger.log(SEVERE, "Error response %s: %s (%s, %s)".formatted(response.error, response.message, response.code, response.status));
            if (response.errors != null) {
                for (AdsError error : response.errors) {
                    logger.log(SEVERE, "%s: %s".formatted(error.propertyPath(), error.message()));
                }
            }
            throw new EMAGException(response);
        }
        return response;
    }

    private static void login(Page page, UserPassword user) {
        page.navigate("https://auth.emag.net/login");
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
        randomWait(5.0, 8.0);
    }
}
