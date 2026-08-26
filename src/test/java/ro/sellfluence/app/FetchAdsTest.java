package ro.sellfluence.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.sellfluence.emagapi.AdsCampaignsResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchAdsTest {
    private static final String validCampaignsResponse = """
            {
              "meta": {"totalCount": 0, "page": 1, "perPage": 1000, "pageCount": 1},
              "data": {"summary": null, "campaigns": []}
            }
            """;

    @TempDir
    Path tempDirectory;

    private String originalHeadlessProperty;

    @BeforeEach
    void clearHeadlessProperty() {
        originalHeadlessProperty = System.getProperty("ads.headless");
        System.clearProperty("ads.headless");
    }

    @AfterEach
    void restoreSystemPropertiesAndInterruptState() {
        if (originalHeadlessProperty == null) {
            System.clearProperty("ads.headless");
        } else {
            System.setProperty("ads.headless", originalHeadlessProperty);
        }
        Thread.interrupted();
    }

    @Test
    void createsIndependentCachePathsForAliasesAndDoesNotUseFlatCache() throws IOException {
        var oldFlatCache = tempDirectory.resolve("adsCampaigns_2026-08-01_1.json");
        Files.writeString(oldFlatCache, "legacy-cache-must-remain-untouched");

        var sellfusionCache = FetchAds.cacheDirectoryForAlias(tempDirectory, "sellfusion");
        var secondCache = FetchAds.cacheDirectoryForAlias(tempDirectory, "second-account");

        assertEquals(tempDirectory.resolve("sellfusion"), sellfusionCache);
        assertEquals(tempDirectory.resolve("second-account"), secondCache);
        assertFalse(sellfusionCache.equals(secondCache));
        var scopedCache = sellfusionCache.resolve(oldFlatCache.getFileName());

        loadCampaignPage(scopedCache, false,
                ignored -> new FetchAds.HttpResult(true, 200, "OK", validCampaignsResponse));

        assertEquals("legacy-cache-must-remain-untouched", Files.readString(oldFlatCache));
        assertEquals(validCampaignsResponse, Files.readString(scopedCache));
        assertFalse(Files.exists(secondCache.resolve(oldFlatCache.getFileName())));
    }

    @Test
    void rejectsAliasesThatCouldEscapeOrCreateAmbiguousCacheDirectories() {
        for (var alias : new String[]{"", ".", "..", "../other", "a/b", "a\\b", "/absolute", "has space"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FetchAds.cacheDirectoryForAlias(tempDirectory, alias), alias);
        }
        assertThrows(IllegalArgumentException.class,
                () -> FetchAds.cacheDirectoryForAlias(tempDirectory, null));
    }

    @Test
    void chromiumIsHeadlessByDefaultAndCanBeMadeHeaded() {
        assertTrue(FetchAds.isHeadless());

        System.setProperty("ads.headless", "false");

        assertFalse(FetchAds.isHeadless());
    }

    @Test
    void rejectsInvalidHeadlessConfiguration() {
        System.setProperty("ads.headless", "sometimes");

        assertThrows(IllegalArgumentException.class, FetchAds::isHeadless);
    }

    @Test
    void rejectsIncompleteCredentialsBeforeStartingBrowser() {
        assertThrows(IllegalStateException.class,
                () -> FetchAds.validateCredentials("alias", null, "password", "otpauth://totp/test"));
        assertThrows(IllegalStateException.class,
                () -> FetchAds.validateCredentials("alias", "user", " ", "otpauth://totp/test"));
        assertThrows(IllegalStateException.class,
                () -> FetchAds.validateCredentials("alias", "user", "password", ""));
    }

    @Test
    void rejectsUnknownAliasBeforeStartingBrowser() {
        assertThrows(IllegalArgumentException.class,
                () -> FetchAds.requireCredentials("codex-fetch-ads-test-alias-that-does-not-exist"));
    }

    @Test
    void cachesOnlyAValidDecodedApiResponse() throws IOException {
        var path = tempDirectory.resolve("sellfusion/campaigns.json");

        var page = loadCampaignPage(path, false,
                ignored -> new FetchAds.HttpResult(true, 200, "OK", validCampaignsResponse));

        assertEquals(1, page.pageCount());
        assertTrue(page.items().isEmpty());
        assertEquals(validCampaignsResponse, Files.readString(path));
    }

    @Test
    void httpAndAdsErrorsPropagateWithoutBeingCached() {
        var httpErrorPath = tempDirectory.resolve("sellfusion/http-error.json");
        var adsErrorPath = tempDirectory.resolve("sellfusion/ads-error.json");
        var adsError = """
                {"error":"access_denied","message":"Denied","status":403,"code":"forbidden","errors":[]}
                """;

        var httpException = assertThrows(RuntimeException.class, () -> loadCampaignPage(httpErrorPath, false,
                ignored -> new FetchAds.HttpResult(false, 503, "Unavailable", "temporary failure")));
        assertTrue(httpException.getMessage().contains("HTTP 503"));
        assertFalse(Files.exists(httpErrorPath));

        assertThrows(FetchAds.EMAGException.class, () -> loadCampaignPage(adsErrorPath, false,
                ignored -> new FetchAds.HttpResult(true, 200, "OK", adsError)));
        assertFalse(Files.exists(adsErrorPath));
    }

    @Test
    void malformedResponsePropagatesWithoutBeingCached() {
        var path = tempDirectory.resolve("sellfusion/malformed.json");

        assertThrows(RuntimeException.class, () -> loadCampaignPage(path, false,
                ignored -> new FetchAds.HttpResult(true, 200, "OK", "not-json")));

        assertFalse(Files.exists(path));
    }

    @Test
    void invalidOnlineCacheIsRemovedAndFetchedAgain() throws IOException {
        var path = tempDirectory.resolve("sellfusion/campaigns.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "not-json");
        var requests = new AtomicInteger();

        var page = loadCampaignPage(path, false, ignored -> {
            requests.incrementAndGet();
            return new FetchAds.HttpResult(true, 200, "OK", validCampaignsResponse);
        });

        assertEquals(1, requests.get());
        assertEquals(1, page.pageCount());
        assertEquals(validCampaignsResponse, Files.readString(path));
    }

    @Test
    void interruptedWaitRestoresInterruptAndAborts() {
        Thread.currentThread().interrupt();

        assertThrows(RuntimeException.class, () -> FetchAds.randomWait(0.0, 0.0));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private FetchAds.DownloadedPage<?> loadCampaignPage(
            Path path,
            boolean offline,
            FetchAds.HttpFetcher fetcher
    ) {
        return FetchAds.loadPage(
                path,
                "https://advertising.emag.net/api/v1/campaigns",
                false,
                offline,
                AdsCampaignsResponse.class,
                response -> response.data.campaigns(),
                fetcher
        );
    }
}
