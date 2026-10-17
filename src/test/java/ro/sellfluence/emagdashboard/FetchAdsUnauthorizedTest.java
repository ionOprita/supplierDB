package ro.sellfluence.emagdashboard;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.apache.hc.core5.net.URIBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchAdsUnauthorizedTest {
    private static final String URL = "https://advertising.emag.net/api/v1/campaigns/123/targeted-products";
    private static final FetchAds.HttpResult UNAUTHORIZED =
            new FetchAds.HttpResult(false, 401, "Unauthorized", "authorization failed");
    private static final FetchAds.HttpResult SUCCESS =
            new FetchAds.HttpResult(true, 200, "OK", campaignPage(1));

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearInterruptState() {
        Thread.interrupted();
    }

    @Test
    void retriesFourUnauthorizedResponsesWithFiveSecondExponentialBackoffAndThenSucceeds() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var result = FetchAds.fetchWithRetry(URL, ignored ->
                        requests.incrementAndGet() <= 4 ? UNAUTHORIZED : SUCCESS,
                delays::add, true);

        assertSame(SUCCESS, result);
        assertEquals(5, requests.get());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L), delays);
    }

    @Test
    void returnsTheFifthUnauthorizedResponseAfterExhaustingRetries() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var result = FetchAds.fetchWithRetry(URL, ignored -> {
            requests.incrementAndGet();
            return UNAUTHORIZED;
        }, delays::add, true);

        assertSame(UNAUTHORIZED, result);
        assertEquals(5, requests.get());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L), delays);
    }

    @Test
    void campaignRequestsRetainImmediateUnauthorizedFailureWithoutOptingIn() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var result = FetchAds.fetchWithRetry(URL, ignored -> {
            requests.incrementAndGet();
            return UNAUTHORIZED;
        }, delays::add);

        assertSame(UNAUTHORIZED, result);
        assertEquals(1, requests.get());
        assertTrue(delays.isEmpty());
    }

    @Test
    void explicitDisabledUnauthorizedRetriesReturnImmediately() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var result = FetchAds.fetchWithRetry(URL, ignored -> {
            requests.incrementAndGet();
            return UNAUTHORIZED;
        }, delays::add, false);

        assertSame(UNAUTHORIZED, result);
        assertEquals(1, requests.get());
        assertTrue(delays.isEmpty());
    }

    @Test
    void sharesFourRetriesBetweenTransportAndUnauthorizedFailures() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var result = FetchAds.fetchWithRetry(URL, ignored -> switch (requests.incrementAndGet()) {
            case 1 -> throw new TimeoutError("timed out");
            case 3 -> throw new PlaywrightException("connection reset");
            default -> UNAUTHORIZED;
        }, delays::add, true);

        assertSame(UNAUTHORIZED, result);
        assertEquals(5, requests.get());
        assertEquals(List.of(10_000L, 10_000L, 40_000L, 40_000L), delays);
    }

    @Test
    void propagatesFinalTransportFailureAfterEarlierUnauthorizedRetries() {
        var requests = new AtomicInteger();
        var delays = new ArrayList<Long>();
        var failure = new TimeoutError("final timeout");

        var thrown = assertThrows(TimeoutError.class, () -> FetchAds.fetchWithRetry(URL, ignored -> {
            if (requests.incrementAndGet() == 5) {
                throw failure;
            }
            return UNAUTHORIZED;
        }, delays::add, true));

        assertSame(failure, thrown);
        assertEquals(5, requests.get());
        assertEquals(List.of(5_000L, 10_000L, 20_000L, 40_000L), delays);
    }

    @Test
    void interruptedUnauthorizedBackoffRestoresInterruptAndStopsRequests() {
        var requests = new AtomicInteger();
        var interruption = new InterruptedException("task cancelled");

        var thrown = assertThrows(RuntimeException.class, () -> FetchAds.fetchWithRetry(URL, ignored -> {
            requests.incrementAndGet();
            return UNAUTHORIZED;
        }, ignored -> { throw interruption; }, true));

        assertSame(interruption, thrown.getCause());
        assertEquals(1, requests.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void otherHttpErrorsDoNotGainRetries() {
        for (var status : List.of(400, 403, 429, 500, 503)) {
            var requests = new AtomicInteger();
            var delays = new ArrayList<Long>();
            var failure = new FetchAds.HttpResult(false, status, "Error", "request failed");

            var result = FetchAds.fetchWithRetry(URL, ignored -> {
                requests.incrementAndGet();
                return failure;
            }, delays::add, true);

            assertSame(failure, result);
            assertEquals(1, requests.get(), "HTTP " + status);
            assertTrue(delays.isEmpty(), "HTTP " + status);
        }
    }

    @Test
    void finalUnauthorizedPageHasDedicatedFailureAndIsNeverCached() {
        var path = tempDirectory.resolve("unauthorized.json");

        var failure = assertThrows(FetchAds.UnauthorizedException.class, () -> FetchAds.loadPage(
                path, URL, false, false, AdsCampaignsResponse.class,
                response -> response.data.campaigns(), ignored -> UNAUTHORIZED));

        assertTrue(failure.getMessage().contains("401"));
        assertTrue(failure.getMessage().contains(URL));
        assertFalse(Files.exists(path));
    }

    @Test
    void failedSecondPagePreservesSuccessfulCacheAndRetriesOnlyTheMissingPage() throws IOException {
        var requestedUrls = new ArrayList<String>();
        var completedReports = new ArrayList<List<Integer>>();

        var failure = assertThrows(FetchAds.UnauthorizedException.class, () -> completedReports.add(
                downloadPageNumbers(url -> {
                    requestedUrls.add(url);
                    return url.endsWith("page=1") ? SUCCESS : UNAUTHORIZED;
                })));

        assertInstanceOf(FetchAds.UnauthorizedException.class, failure);
        assertTrue(completedReports.isEmpty(), "An incomplete report must not reach the caller");
        assertEquals(List.of(pageUrl(1), pageUrl(2)), requestedUrls);
        assertEquals(campaignPage(1), Files.readString(pagePath(1)));
        assertFalse(Files.exists(pagePath(2)));

        requestedUrls.clear();
        completedReports.add(downloadPageNumbers(url -> {
            requestedUrls.add(url);
            return new FetchAds.HttpResult(true, 200, "OK", campaignPage(2));
        }));

        assertEquals(List.of(List.of(1, 2)), completedReports);
        assertEquals(List.of(pageUrl(2)), requestedUrls);
        assertEquals(campaignPage(1), Files.readString(pagePath(1)));
        assertEquals(campaignPage(2), Files.readString(pagePath(2)));
    }

    private List<Integer> downloadPageNumbers(FetchAds.HttpFetcher fetcher) {
        return FetchAds.downloadPages(
                page -> new URIBuilder(URI.create(URL)).setParameter("page", Integer.toString(page)),
                this::pagePath,
                AdsCampaignsResponse.class,
                response -> List.of(response.meta.page()),
                false,
                fetcher
        );
    }

    private Path pagePath(int page) {
        return tempDirectory.resolve("report-page-%d.json".formatted(page));
    }

    private static String pageUrl(int page) {
        return URL + "?page=" + page;
    }

    private static String campaignPage(int page) {
        return """
                {
                  "meta": {"totalCount": 0, "page": %d, "perPage": 1000, "pageCount": 2},
                  "data": {"summary": null, "campaigns": []}
                }
                """.formatted(page);
    }
}
