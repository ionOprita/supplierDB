package ro.sellfluence.apphelper;

import com.bastiaanjansen.otp.TOTPGenerator;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
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
import ro.sellfluence.emagapi.AdsCampaignSnapshot;
import ro.sellfluence.emagapi.AdsCampaignTargetedProductsResponse;
import ro.sellfluence.emagapi.AdsCampaignsResponse;
import ro.sellfluence.emagapi.AdsError;
import ro.sellfluence.emagapi.AdsKeyword;
import ro.sellfluence.emagapi.AdsResponse;
import ro.sellfluence.emagapi.AdsSearchPhrase;
import ro.sellfluence.emagapi.AdsTargetedProduct;
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
import java.util.Objects;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

public class FetchAds {
    private static final Logger logger = Logs.getConsoleAndFileLogger("FetchAds", INFO, 10, 100_000);

    private static final int MAX_REQUEST_RETRIES = 4;
    private static final long INITIAL_RETRY_DELAY_MILLISECONDS = 10_000;

    private static final Random random = new Random();

    private static final Path cacheDirectory = Path.of("AdsJSON");

    private static final Pattern safeAlias = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

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

    static class EMAGException extends RuntimeException {
        public final AdsResponse response;

        EMAGException(AdsResponse response) {
            super("eMAG Ads returned an error: %s (%s, %s)"
                    .formatted(response.error, response.code, response.status));
            this.response = response;
        }
    }

    record HttpResult(boolean ok, int status, String statusText, String body) {
    }

    @FunctionalInterface
    interface HttpFetcher {
        HttpResult fetch(String url);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    record DownloadedPage<T>(List<T> items, int pageCount) {
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
        withPlaywrightSession(alias, (page, aliasCacheDirectory) ->
                transferAdsAndCampaignsToDB(page, aliasCacheDirectory, mirrorDB, startDate, endDate));
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
        withPlaywrightSession(alias, (page, aliasCacheDirectory) ->
                transferKeywordsToDB(page, aliasCacheDirectory, mirrorDB, startDate, endDate));
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
        withPlaywrightSession(alias, (page, aliasCacheDirectory) ->
                transferSearchPhrasesToDB(page, aliasCacheDirectory, mirrorDB, startDate, endDate));
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
        withPlaywrightSession(alias, (page, aliasCacheDirectory) ->
                transferTargetedProductsToDB(page, aliasCacheDirectory, mirrorDB, startDate, endDate));
    }

    private static void withPlaywrightSession(String alias, BiConsumer<Page, Path> transfer) {
        var aliasCacheDirectory = cacheDirectoryForAlias(cacheDirectory, alias);
        var user = requireCredentials(alias);
        setupCacheDirectory(aliasCacheDirectory);
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(isHeadless()))) {
                try (BrowserContext context = browser.newContext()) {
                    Page page = context.newPage();
                    if (!isOffline()) {
                        login(page, user);
                    }
                    transfer.accept(page, aliasCacheDirectory);
                }
            }
        }
    }

    /**
     * Delete the cache for the given alias.
     *
     * @param alias account.
     */
    public static void deleteAdsCache(String alias) {
        var aliasCacheDirectory = cacheDirectoryForAlias(cacheDirectory, alias);
        try (var stream = Files.newDirectoryStream(aliasCacheDirectory, "*.json")) {
            for (Path path : stream) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path cacheDirectoryForAlias(Path root, String alias) {
        Objects.requireNonNull(root, "Cache root must not be null.");
        if (alias == null || !safeAlias.matcher(alias).matches()) {
            throw new IllegalArgumentException("Invalid eMAG Ads alias: %s".formatted(alias));
        }
        var normalizedRoot = root.toAbsolutePath().normalize();
        var aliasDirectory = normalizedRoot.resolve(alias).normalize();
        if (!normalizedRoot.equals(aliasDirectory.getParent())) {
            throw new IllegalArgumentException("eMAG Ads alias must identify one cache directory.");
        }
        return root.resolve(alias);
    }

    public static boolean isHeadless() {
        return booleanProperty("ads.headless", true);
    }

    private static boolean isOffline() {
        return booleanProperty("ads.offline", false);
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        var value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("System property %s must be either true or false.".formatted(name));
    }

    public static UserPassword requireCredentials(String alias) {
        var user = UserPassword.findAlias(alias);
        if (user == null) {
            throw new IllegalArgumentException("Unknown eMAG Ads alias: %s".formatted(alias));
        }
        validateCredentials(alias, user.getUsername(), user.getPassword(), user.getOtpAuth());
        return user;
    }

    public static void validateCredentials(String alias, String username, String password, String otpAuth) {
        if (username == null || username.isBlank()
                || password == null || password.isBlank()
                || otpAuth == null || otpAuth.isBlank()) {
            throw new IllegalStateException("Incomplete eMAG Ads credentials for alias %s.".formatted(alias));
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
    private static void transferAdsAndCampaignsToDB(
            Page page,
            Path aliasCacheDirectory,
            EmagMirrorDB mirrorDB,
            LocalDate startDate,
            LocalDate endDate
    ) {
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var campaigns = downloadAdsAndCampaigns(page, aliasCacheDirectory, currentDate);
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
    private static void transferKeywordsToDB(
            Page page,
            Path aliasCacheDirectory,
            EmagMirrorDB mirrorDB,
            LocalDate startDate,
            LocalDate endDate
    ) {
        var adSetsByDate = readAdSetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsKeyword>>();
            var downloadedRows = 0;
            for (var adSet : adSetsByDate.getOrDefault(currentDate, List.of())) {
                var keywords = downloadKeywords(
                        page, aliasCacheDirectory, currentDate, adSet.campaignId(), adSet.adsetId()
                );
                downloadedRows += keywords.size();
                reports.add(new AdsAdsetReport<>(adSet, keywords));
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
    private static void transferSearchPhrasesToDB(
            Page page,
            Path aliasCacheDirectory,
            EmagMirrorDB mirrorDB,
            LocalDate startDate,
            LocalDate endDate
    ) {
        var adSetsByDate = readAdSetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsSearchPhrase>>();
            var downloadedRows = 0;
            var matchedRows = 0;
            var adSetsByCampaign = adSetsByDate.getOrDefault(currentDate, List.of()).stream()
                    .collect(Collectors.groupingBy(AdsAdsetKey::campaignId, LinkedHashMap::new, Collectors.toList()));
            for (var campaignEntry : adSetsByCampaign.entrySet()) {
                var searchPhrases = downloadSearchPhrases(
                        page, aliasCacheDirectory, currentDate, campaignEntry.getKey()
                );
                downloadedRows += searchPhrases.size();
                var phrasesByAdSet = searchPhrases.stream()
                        .filter(phrase -> phrase.adsetId() != null)
                        .collect(Collectors.groupingBy(AdsSearchPhrase::adsetId));
                for (var adSet : campaignEntry.getValue()) {
                    var phrases = phrasesByAdSet.getOrDefault(adSet.adsetId(), List.of());
                    matchedRows += phrases.size();
                    reports.add(new AdsAdsetReport<>(adSet, phrases));
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
    private static void transferTargetedProductsToDB(
            Page page,
            Path aliasCacheDirectory,
            EmagMirrorDB mirrorDB,
            LocalDate startDate,
            LocalDate endDate
    ) {
        var adSetsByDate = readAdSetsByDate(mirrorDB, startDate, endDate);
        var currentDate = startDate;
        while (currentDate.isBefore(endDate)) {
            var reports = new ArrayList<AdsAdsetReport<AdsTargetedProduct>>();
            var downloadedRows = 0;
            for (var adSet : adSetsByDate.getOrDefault(currentDate, List.of())) {
                var targetedProducts = downloadTargetedProducts(
                        page, aliasCacheDirectory, currentDate, adSet.campaignId(), adSet.adsetId()
                );
                downloadedRows += targetedProducts.size();
                reports.add(new AdsAdsetReport<>(adSet, targetedProducts));
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

    private static Map<LocalDate, List<AdsAdsetKey>> readAdSetsByDate(
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
    private static ArrayList<AdsCampaignSnapshot> downloadAdsAndCampaigns(
            Page page,
            Path aliasCacheDirectory,
            LocalDate date
    ) {
        var campaignList = new ArrayList<AdsCampaignSnapshot>();
        for (var campaign : downloadCampaigns(page, aliasCacheDirectory, date)) {
            var adSetList = downloadAdSets(page, aliasCacheDirectory, date, campaign.id()).stream()
                    .map(adSet -> new AdSet(adSet, List.of(), List.of(), List.of()))
                    .toList();
            campaignList.add(new AdsCampaignSnapshot(date, campaign, adSetList));
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
    private static List<AdsCampaign> downloadCampaigns(Page page, Path aliasCacheDirectory, LocalDate date) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber).appendPath("campaigns")
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> aliasCacheDirectory.resolve("adsCampaigns_%s_%d.json".formatted(date, pageNumber)),
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
    private static List<AdsAdset> downloadAdSets(
            Page page,
            Path aliasCacheDirectory,
            LocalDate date,
            int campaignId
    ) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaign/%d/adsets".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId)),
                pageNumber -> aliasCacheDirectory.resolve(
                        "adsAdSets_%s_%d_%d.json".formatted(date, pageNumber, campaignId)
                ),
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
    private static List<AdsSearchPhrase> downloadSearchPhrases(
            Page page,
            Path aliasCacheDirectory,
            LocalDate date,
            int campaignId
    ) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaigns/%d/search-phrases".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber)),
                pageNumber -> aliasCacheDirectory.resolve(
                        "adsSearchPhrases_%s_%d_%d.json".formatted(date, pageNumber, campaignId)
                ),
                AdsCampaignPhrasesResponse.class,
                response -> response.data.searchPhrases()
        );
    }

    /**
     * Download targeted product information for the given campaign, adset, and day.
     *
     * @param page       Playwright session.
     * @param date       for which to download the data.
     * @param campaignId selects the campaign.
     * @param adSetId    selects the ad set within the campaign.
     * @return List of targeted product data.
     */
    private static List<AdsTargetedProduct> downloadTargetedProducts(
            Page page,
            Path aliasCacheDirectory,
            LocalDate date,
            int campaignId,
            int adSetId
    ) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaigns/%d/adsets/%s/targeted-products".formatted(campaignId, adSetId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("perPage", "100")
                        .setParameter("dateEnd", date.plusDays(1).toString()),
                pageNumber -> aliasCacheDirectory.resolve(
                        "adsTargetedProducts_%s_%d_%d_%d.json"
                                .formatted(date, pageNumber, campaignId, adSetId)
                ),
                AdsCampaignTargetedProductsResponse.class,
                response -> response.data.docs()
        );
    }

    /**
     * Download keyword information for the given campaign, adset, and day.
     *
     * @param page       Playwright session.
     * @param date       for which to download the data.
     * @param campaignId selects the campaign.
     * @param adSetId    selects the ad set within the campaign.
     * @return List of keyword data.
     */
    private static List<AdsKeyword> downloadKeywords(
            Page page,
            Path aliasCacheDirectory,
            LocalDate date,
            int campaignId,
            int adSetId
    ) {
        return downloadPages(
                page,
                pageNumber -> createCommonURI(date, pageNumber)
                        .appendPath("campaign/%d/keywords".formatted(campaignId))
                        .setParameter("page", Integer.toString(pageNumber))
                        .setParameter("campaignId", Integer.toString(campaignId))
                        .setParameter("adsetId", Integer.toString(adSetId)),
                pageNumber -> aliasCacheDirectory.resolve(
                        "adsKeywords_%s_%d_%d_%d.json".formatted(date, pageNumber, campaignId, adSetId)
                ),
                AdsCampaignKeywordsResponse.class,
                response -> response.data.keywords()
        );
    }

    /**
     * Does the actual downloading of data from eMag and handles paging.
     *
     * @param page              Playwright session.
     * @param uriForPage        method which creates the URI.
     * @param pathForPage       method which creates the path for the cache file.
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
        try {
            do {
                var uri = uriForPage.apply(pageNumber).build();
                var path = pathForPage.apply(pageNumber);
                var downloadedPage = loadPage(
                        path,
                        uri.toASCIIString(),
                        pageNumber == 1,
                        isOffline(),
                        responseType,
                        itemsFromResponse,
                        url -> fetch(page, url)
                );
                result.addAll(downloadedPage.items());
                totalPages = downloadedPage.pageCount();
                pageNumber++;
            } while (pageNumber <= totalPages);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unexpected error when building the page URI.", e);
        }
        return result;
    }

    static <T, R extends AdsResponse> DownloadedPage<T> loadPage(
            Path path,
            String url,
            boolean doWait,
            boolean offlineMode,
            Class<R> responseType,
            Function<R, List<T>> itemsFromResponse,
            HttpFetcher fetcher
    ) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(url);
        Objects.requireNonNull(responseType);
        Objects.requireNonNull(itemsFromResponse);
        Objects.requireNonNull(fetcher);

        if (Files.exists(path)) {
            try {
                return decodePage(Files.readString(path), responseType, itemsFromResponse);
            } catch (IOException e) {
                throw new RuntimeException("Error reading cached eMAG Ads response from %s.".formatted(path), e);
            } catch (RuntimeException e) {
                if (offlineMode) {
                    throw new RuntimeException("Cached eMAG Ads response is invalid: %s".formatted(path), e);
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException deleteException) {
                    e.addSuppressed(deleteException);
                    throw new RuntimeException("Could not remove invalid eMAG Ads cache file %s."
                            .formatted(path), e);
                }
                logger.log(WARNING, "Removed invalid eMAG Ads cache file %s; downloading it again."
                        .formatted(path));
            }
        }

        if (offlineMode) {
            throw new IllegalStateException("Could not load %s because ads.offline is true and no valid cache exists."
                    .formatted(url));
        }
        if (doWait) {
            randomWait(0.05, 0.2);
        }
        var httpResult = fetcher.fetch(url);
        if (!httpResult.ok()) {
            throw new RuntimeException("eMAG Ads request failed with HTTP %d %s for %s."
                    .formatted(httpResult.status(), httpResult.statusText(), url));
        }

        var downloadedPage = decodePage(httpResult.body(), responseType, itemsFromResponse);
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            Files.writeString(path, httpResult.body());
        } catch (IOException e) {
            throw new RuntimeException("Could not cache eMAG Ads response at %s.".formatted(path), e);
        }
        logger.log(INFO, "Retrieved %s and stored to %s.".formatted(url, path));
        return downloadedPage;
    }

    private static HttpResult fetch(Page page, String url) {
        return fetchWithRetry(url, requestUrl -> fetchOnce(page, requestUrl), Thread::sleep);
    }

    static HttpResult fetchWithRetry(String url, HttpFetcher fetcher, Sleeper sleeper) {
        Objects.requireNonNull(url);
        Objects.requireNonNull(fetcher);
        Objects.requireNonNull(sleeper);

        var retriesRemaining = MAX_REQUEST_RETRIES;
        var retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS;
        while (true) {
            try {
                return fetcher.fetch(url);
            } catch (TimeoutError timeout) {
                if (retriesRemaining == 0) {
                    throw timeout;
                }
                logger.log(WARNING, "eMAG Ads request timed out for %s. Retrying after %d s; retries remaining=%d."
                        .formatted(url, retryDelay / 1_000, retriesRemaining));
                retriesRemaining--;
                try {
                    sleeper.sleep(retryDelay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                            "Interrupted while waiting to retry eMAG Ads request for %s.".formatted(url),
                            interrupted
                    );
                }
                retryDelay *= 2;
            }
        }
    }

    private static HttpResult fetchOnce(Page page, String url) {
        var response = page.request().get(url);
        try {
            return new HttpResult(response.ok(), response.status(), response.statusText(), response.text());
        } finally {
            response.dispose();
        }
    }

    private static <T, R extends AdsResponse> DownloadedPage<T> decodePage(
            String json,
            Class<R> responseType,
            Function<R, List<T>> itemsFromResponse
    ) {
        var response = decodeJSON(json, responseType);
        if (response.meta == null || response.meta.pageCount() == null || response.meta.pageCount() < 0) {
            throw new IllegalArgumentException("eMAG Ads response has missing or invalid pagination metadata.");
        }
        var items = itemsFromResponse.apply(response);
        if (items == null) {
            throw new IllegalArgumentException("eMAG Ads response has no data collection.");
        }
        return new DownloadedPage<>(items, response.meta.pageCount());
    }

    /**
     * Generate the common part of each URI.
     *
     * @param date       date argument
     * @param pageNumber page number
     * @return URI builder object to which additional argument can be added.
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
    private static <T extends AdsResponse> T decodeJSON(String json, Class<T> valueType) {
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
     * Perform all steps to log into the advertising dashboard.
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

    /**
     * Wait a random time of minimum {@see fromSec} to less than {@see toSetc}.
     *
     * @param fromSec minimum time to wait for.
     * @param toSec   maximum time to wait for.
     */
    static void randomWait(Double fromSec, Double toSec) {
        var waitSec = fromSec + (toSec - fromSec) * random.nextDouble();
        try {
            Thread.sleep((long) (waitSec * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to access eMAG Ads.", e);
        }
    }

    /**
     * Create the cache directory if it does not exist.
     */
    private static void setupCacheDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new RuntimeException("Could not create cache directory %s.".formatted(directory), e);
        }
    }
}
