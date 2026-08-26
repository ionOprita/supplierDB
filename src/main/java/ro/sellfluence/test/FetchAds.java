package ro.sellfluence.test;

import com.bastiaanjansen.otp.TOTPGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.apache.hc.core5.net.URIBuilder;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetReport;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
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

    private static final Random random = new Random();

    private static final Path cacheDirectory = Path.of("AdsJSON");

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }

    private static final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(
                    new SimpleModule()
                            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
            ).build();

    static class EMAGException extends Exception {
        public AdsResponse response;

        EMAGException(AdsResponse response) {
            this.response = response;
        }
    }

    static void main(String... args) throws Exception {
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        var endDate = LocalDate.now();
        var startDate = endDate.minusDays(31);
        fetchAdsAndCampaigns("sellfusion", mirrorDB, startDate, endDate);
        fetchKeywords("sellfusion", mirrorDB, startDate, endDate);
        fetchSearchPhrases("sellfusion", mirrorDB, startDate, endDate);
        fetchTargetedProducts("sellfusion", mirrorDB, startDate, endDate);
    }

    /**
     * Fetch campaigns and their ad sets and store them in the database.
     *
     * @param alias     eMag user.
     * @param mirrorDB  database.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    public static void fetchAdsAndCampaigns(String alias, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        withPlaywrightSession(alias, page -> transferAdsAndCampaignsToDB(page, mirrorDB, startDate, endDate));
    }

    /**
     * Fetch keywords for the ad sets stored in the database.
     *
     * @param alias     eMag user.
     * @param mirrorDB  database containing the campaign and ad set IDs.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    public static void fetchKeywords(String alias, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        withPlaywrightSession(alias, page -> transferKeywordsToDB(page, mirrorDB, startDate, endDate));
    }

    /**
     * Fetch search phrases for the campaigns and ad sets stored in the database.
     *
     * @param alias     eMag user.
     * @param mirrorDB  database containing the campaign and ad set IDs.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    public static void fetchSearchPhrases(String alias, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        withPlaywrightSession(alias, page -> transferSearchPhrasesToDB(page, mirrorDB, startDate, endDate));
    }

    /**
     * Fetch targeted products for the ad sets stored in the database.
     *
     * @param alias     eMag user.
     * @param mirrorDB  database containing the campaign and ad set IDs.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    public static void fetchTargetedProducts(String alias, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        withPlaywrightSession(alias, page -> transferTargetedProductsToDB(page, mirrorDB, startDate, endDate));
    }

    private static void withPlaywrightSession(String alias, Consumer<Page> transfer) {
        setupCacheDirectory();
        var user = UserPassword.findAlias(alias);
        if (user == null) {
            logger.log(WARNING, "Skipping unknown alias %s".formatted(alias));
        } else {
            try (Playwright playwright = Playwright.create()) {
                try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(false))) {
                    try (BrowserContext context = browser.newContext()) {
                        Page page = context.newPage();
                        if (!offline) {
                            login(page, user);
                        }
                        transfer.accept(page);
                    }
                }
            }
        }
    }

    /**
     * Download campaigns and ad sets from eMag and store them in the database.
     *
     * @param page      Playwright session.
     * @param mirrorDB  database.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    private static void transferAdsAndCampaignsToDB(Page page, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var campaigns = downloadAdsAndCampaigns(page, currentDate);
            try {
                var changedRows = mirrorDB.addOrUpdateAdsAndCampaigns(campaigns);
                logger.log(INFO, "Inserted or updated %d campaign and ad set rows from %d campaigns for %s."
                        .formatted(changedRows, campaigns.size(), currentDate));
            } catch (SQLException e) {
                throw new RuntimeException("Error storing campaigns and ad sets in the database.", e);
            }
            currentDate = currentDate.plusDays(1);
        }
    }

    /**
     * Download keyword data using the ad set IDs stored in the database.
     *
     * @param page      Playwright session.
     * @param mirrorDB  database.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    private static void transferKeywordsToDB(Page page, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        var adsetsByDate = readAdsetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsKeyword>>();
            var downloadedRows = 0;
            for (var adset : adsetsByDate.getOrDefault(currentDate, List.of())) {
                var keywords = downloadKeywords(page, currentDate, adset.campaignId(), adset.adsetId());
                downloadedRows += keywords.size();
                reports.add(new AdsAdsetReport<>(adset, keywords));
            }
            try {
                var changedRows = mirrorDB.addOrUpdateAdsKeywords(reports);
                logger.log(INFO, "Inserted or updated %d keyword rows from %d downloaded rows for %s."
                        .formatted(changedRows, downloadedRows, currentDate));
            } catch (SQLException e) {
                throw new RuntimeException("Error storing keywords in the database.", e);
            }
            currentDate = currentDate.plusDays(1);
        }
    }

    /**
     * Download search phrase data using the campaign and ad set IDs stored in the database.
     *
     * @param page      Playwright session.
     * @param mirrorDB  database.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    private static void transferSearchPhrasesToDB(Page page, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        var adsetsByDate = readAdsetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsSearchPhrase>>();
            var downloadedRows = 0;
            var matchedRows = 0;
            var adsetsByCampaign = adsetsByDate.getOrDefault(currentDate, List.of()).stream()
                    .collect(Collectors.groupingBy(AdsAdsetKey::campaignId, LinkedHashMap::new, Collectors.toList()));
            for (var campaignEntry : adsetsByCampaign.entrySet()) {
                var searchPhrases = downloadSearchPhrases(page, currentDate, campaignEntry.getKey());
                downloadedRows += searchPhrases.size();
                var phrasesByAdset = searchPhrases.stream()
                        .filter(phrase -> phrase.adsetId() != null)
                        .collect(Collectors.groupingBy(AdsSearchPhrase::adsetId));
                for (var adset : campaignEntry.getValue()) {
                    var phrases = phrasesByAdset.getOrDefault(adset.adsetId(), List.of());
                    matchedRows += phrases.size();
                    reports.add(new AdsAdsetReport<>(adset, phrases));
                }
            }
            if (matchedRows != downloadedRows) {
                logger.log(WARNING, "Ignored %d search phrase rows for unknown ad sets on %s."
                        .formatted(downloadedRows - matchedRows, currentDate));
            }
            try {
                var changedRows = mirrorDB.addOrUpdateAdsSearchPhrases(reports);
                logger.log(INFO, "Inserted or updated %d search phrase rows from %d downloaded rows for %s."
                        .formatted(changedRows, downloadedRows, currentDate));
            } catch (SQLException e) {
                throw new RuntimeException("Error storing search phrases in the database.", e);
            }
            currentDate = currentDate.plusDays(1);
        }
    }

    /**
     * Download targeted product data using the ad set IDs stored in the database.
     *
     * @param page      Playwright session.
     * @param mirrorDB  database.
     * @param startDate First day to fetch.
     * @param endDate   End date not to be included.
     */
    private static void transferTargetedProductsToDB(Page page, EmagMirrorDB mirrorDB, LocalDate startDate, LocalDate endDate) {
        var adsetsByDate = readAdsetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsTargetedProduct>>();
            var downloadedRows = 0;
            for (var adset : adsetsByDate.getOrDefault(currentDate, List.of())) {
                var targetedProducts = downloadTargetedProducts(
                        page, currentDate, adset.campaignId(), adset.adsetId()
                );
                downloadedRows += targetedProducts.size();
                reports.add(new AdsAdsetReport<>(adset, targetedProducts));
            }
            try {
                var changedRows = mirrorDB.addOrUpdateAdsTargetedProducts(reports);
                logger.log(INFO, "Inserted or updated %d targeted product rows from %d downloaded rows for %s."
                        .formatted(changedRows, downloadedRows, currentDate));
            } catch (SQLException e) {
                throw new RuntimeException("Error storing targeted products in the database.", e);
            }
            currentDate = currentDate.plusDays(1);
        }
    }

    private static Map<LocalDate, List<AdsAdsetKey>> readAdsetsByDate(
            EmagMirrorDB mirrorDB,
            LocalDate startDate,
            LocalDate endDate
    ) {
        try {
            return mirrorDB.getAdsAdsetKeys(startDate, endDate).stream()
                    .collect(Collectors.groupingBy(AdsAdsetKey::reportDate, LinkedHashMap::new, Collectors.toList()));
        } catch (SQLException e) {
            throw new RuntimeException("Error reading campaign and ad set IDs from the database.", e);
        }
    }

    /**
     * Download campaigns and ad sets for one day.
     *
     * @param page Playwright session.
     * @param date for which to download the data.
     * @return Campaign and ad set snapshots for the date.
     */
    private static ArrayList<Campaign> downloadAdsAndCampaigns(Page page, LocalDate date) {
        var campaignList = new ArrayList<Campaign>();
        for (var campaign : downloadCampaigns(page, date)) {
            var adSetList = downloadAdSets(page, date, campaign.id()).stream()
                    .map(adSet -> new AdSet(adSet, List.of(), List.of(), List.of()))
                    .toList();
            campaignList.add(new Campaign(date, campaign, adSetList));
        }
        return campaignList;
    }

    /**
     * Download campaign information for one day.
     *
     * @param page Playwright session.
     * @param date for which to download the data.
     * @return List of campaign data.
     */
    private static List<AdsCampaign> downloadCampaigns(Page page, LocalDate date) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber).appendPath("campaigns")
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> cacheDirectory.resolve("adsCampaigns_%s_%d.json".formatted(date, pageNumber)),
                AdsCampaignsResponse.class,
                response -> response.data.campaigns()
        );
    }

    /**
     * Download campaign information for one day.
     *
     * @param page Playwright session.
     * @param date for which to download the data.
     * @return List of campaign data.
     */
    private static List<AdsAdset> downloadAdSets(Page page, LocalDate date, int campaignId) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaign/%d/adsets".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId)),
                pageNumber -> cacheDirectory.resolve("adsAdSets_%s_%d_%d.json".formatted(date, pageNumber, campaignId)),
                AdsCampaignAdSetsResponse.class,
                response -> response.data.adsets()
        );
    }

    /**
     * Download adset information for the given campaign and day.
     *
     * @param page       Playwright session.
     * @param date       for which to download the data.
     * @param campaignId selects the campaign.
     * @return List of campaign data.
     */
    private static List<AdsSearchPhrase> downloadSearchPhrases(Page page, LocalDate date, int campaignId) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaigns/%d/search-phrases".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> cacheDirectory.resolve("adsSearchPhrases_%s_%d_%d.json".formatted(date, pageNumber, campaignId)),
                AdsCampaignPhrasesResponse.class,
                response -> response.data.searchPhrases()
        );
    }

    /**
     * Download targeted product information for the given campaign, adset and day.
     *
     * @param page       Playwright session.
     * @param date       for which to download the data.
     * @param campaignId selects the campaign.
     * @param adSetId    selects the ad set within the campaign.
     * @return List of targeted product data.
     */
    private static List<AdsTargetedProduct> downloadTargetedProducts(Page page, LocalDate date, int campaignId, int adSetId) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaigns/%d/adsets/%s/targeted-products".formatted(campaignId, adSetId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("dateEnd", date.plusDays(1).toString()),
                pageNumber -> cacheDirectory.resolve("adsTargetedProducts_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId)),
                AdsCampaignTargetedProductsResponse.class,
                response -> response.data.docs()
        );
    }

    /**
     * Download keywords information for the given campaign, adset and day.
     *
     * @param page       Playwright session.
     * @param date       for which to download the data.
     * @param campaignId selects the campaign.
     * @param adSetId    selects the ad set within the campaign.
     * @return List of keyword data.
     */
    private static List<AdsKeyword> downloadKeywords(Page page, LocalDate date, int campaignId, int adSetId) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaign/%d/keywords".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId))
                        .setParameter("adsetId", Integer.toString(adSetId)),
                pageNumber -> cacheDirectory.resolve("adsKeywords_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId)),
                AdsCampaignKeywordsResponse.class,
                response -> response.data.keywords()
        );
    }

    /**
     * Does the actual downloading of data from eMag and handles paging.
     *
     * @param page              Playwright session.
     * @param uriForPage        method which create the URI.
     * @param pathForPage       method which create the path for the cache file.
     * @param responseType      data type into which to decode the received JSON.
     * @param itemsFromResponse method which extracts the relevant data from the decoded JSON.
     * @return List of campaign data.
     */
    private static <T, R extends AdsResponse> List<T> downloadPages(
            Page page,
            IntFunction<URIBuilder> uriForPage,
            IntFunction<Path> pathForPage,
            Class<R> responseType,
            Function<R, List<T>> itemsFromResponse
    ) {
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
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unexpected error when building the page URI.", e);
        } catch (IOException e) {
            throw new RuntimeException("Error reading JSON from eMag", e);
        }
        return result;
    }

    /**
     * Generate the common part of each URI.
     *
     * @param date       date argument
     * @param pageNumber page number
     * @return URI builder to which additional argument can be added.
     */
    private static URIBuilder createCommonURI(LocalDate date, int pageNumber) {
        URIBuilder uriBuilder = new URIBuilder(URI.create("https://advertising.emag.net/api/v1"));
        uriBuilder.addParameter("page", Integer.toString(pageNumber));
        uriBuilder.addParameter("perPage", "1000");
        uriBuilder.addParameter("dateStart", date.toString());
        uriBuilder.addParameter("dateEnd", date.toString());
        return uriBuilder;
    }

    /**
     * Decode the JSON into the desired class and handle error messages.
     *
     * @param json      as received from eMag.
     * @param valueType desired response type.
     * @param <T>       response type.
     * @return decoded value.
     * @throws EMAGException if the response represents an error.
     */
    private static <T extends AdsResponse> T decodeJSON(String json, Class<T> valueType) throws EMAGException {
        T response;
        try {
            response = objectMapper.readValue(json, valueType);
        } catch (JacksonException e) {
            logger.log(SEVERE, "Cannot decode JSON %s".formatted(json));
            throw new RuntimeException("Error decoding JSON", e);
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

    /**
     * Perform all steps to log into the ads dashboard.
     *
     * @param page Playwright instance.
     * @param user user information needed for the login.
     */
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


    private static String genOTP(String otpAuth) {
        var otpUri = URI.create(otpAuth);
        try {
            TOTPGenerator totp = TOTPGenerator.fromURI(otpUri);
            return totp.now();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error interpreting OTP URI", e);
        }
    }

    private static void randomWait(Double fromSec, Double toSec) {
        var waitSec = fromSec + (toSec - fromSec) * random.nextDouble();
        try {
            Thread.sleep((long) waitSec * 1000);
        } catch (InterruptedException e) {
            logger.log(WARNING, "Sleep was interrupted.", e);
        }
    }

    /**
     * Create the cache directory if it does not exist.
     */
    private static void setupCacheDirectory() {
        if (!Files.exists(cacheDirectory)) {
            try {
                Files.createDirectories(cacheDirectory);
            } catch (IOException e) {
                throw new RuntimeException("Could not create cache directory.", e);
            }
        }
    }


}
