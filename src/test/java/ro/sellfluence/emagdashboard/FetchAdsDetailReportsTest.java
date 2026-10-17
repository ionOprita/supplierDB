package ro.sellfluence.emagdashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetKey;
import ro.sellfluence.db.AdsCampaignTable.AdsAdsetReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchAdsDetailReportsTest {
    private static final UUID VENDOR_ID = UUID.fromString("9308100c-bb65-4702-8d8f-f724f9c1e4ca");
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 30);
    private static final String EMPTY_RESPONSE = """
            {
              "meta": {"totalCount": 0, "page": 1, "perPage": 100, "pageCount": 1},
              "data": {"summary": null, "campaigns": []}
            }
            """;

    @TempDir
    Path tempDirectory;

    @Test
    void persistsCompletedReportsAndOmitsFailuresButMarksTheTaskFailed() {
        var progress = new FetchAds.DetailFetchProgress();
        var persisted = new ArrayList<AdsAdsetReport<String>>();

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2, 3, 4), key -> {
                    request(progress, key, key == 2 || key == 4);
                    return List.of(report(key));
                }, persisted::addAll, progress)));

        assertEquals(List.of(report(1), report(3)), persisted);
        assertEquals(skipped(2) + "\n" + skipped(4), failure.getMessage());
        assertNull(failure.getCause(), "Completing with gaps is still a task failure");
    }

    @Test
    void thirdConsecutiveUnauthorizedStopsFurtherDownloadsAndFlushesCompletedReports() {
        var progress = new FetchAds.DetailFetchProgress();
        var visited = new ArrayList<Integer>();
        var persisted = new ArrayList<AdsAdsetReport<String>>();

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2, 3, 4, 5), key -> {
                    visited.add(key);
                    request(progress, key, key != 1);
                    return List.of(report(key));
                }, persisted::addAll, progress)));

        assertEquals(List.of(1, 2, 3, 4), visited);
        assertEquals(List.of(report(1)), persisted);
        assertTrue(failure.getCause().getMessage().contains("3 consecutive URLs"));
        assertInstanceOf(FetchAds.UnauthorizedException.class, failure.getCause().getCause());
        assertTrue(failure.getMessage().endsWith(skipped(2) + "\n" + skipped(3) + "\n" + skipped(4)));
    }

    @Test
    void successfulLiveRequestResetsTheStreak() {
        assertLiveSuccessResetsStreak(false);
    }

    @Test
    void successfulEmptyLiveResponseAlsoResetsTheStreak() {
        assertLiveSuccessResetsStreak(true);
    }

    @Test
    void cachedReportDoesNotResetTheLiveUnauthorizedStreak() throws IOException {
        var path = tempDirectory.resolve("cached-campaigns.json");
        Files.writeString(path, EMPTY_RESPONSE);
        var progress = new FetchAds.DetailFetchProgress();
        var visited = new ArrayList<Integer>();
        var persisted = new ArrayList<AdsAdsetReport<String>>();

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2, 3, 4, 5), key -> {
                    visited.add(key);
                    if (key == 3) {
                        var cached = FetchAds.loadPage(path, "https://advertising.emag.net/cached", false, false,
                                AdsCampaignsResponse.class, response -> response.data.campaigns(),
                                progress.fetcher(url -> {
                                    throw new AssertionError("A cache hit must not make a live request");
                                }, milliseconds -> { }));
                        assertTrue(cached.items().isEmpty());
                        return List.of(report(key, List.of()));
                    }
                    request(progress, key, true);
                    return List.of(report(key));
                }, persisted::addAll, progress)));

        assertEquals(List.of(1, 2, 3, 4), visited);
        assertEquals(List.of(report(3, List.of())), persisted);
        assertTrue(failure.getCause().getMessage().contains("3 consecutive URLs"));
    }

    @Test
    void unauthorizedStreakContinuesAcrossDailyBatchesInOneTask() {
        var progress = new FetchAds.DetailFetchProgress();
        var visited = new ArrayList<Integer>();
        var batches = new ArrayList<List<AdsAdsetReport<String>>>();
        Function<Integer, List<AdsAdsetReport<String>>> downloader = key -> {
            visited.add(key);
            request(progress, key, true);
            return List.of(report(key));
        };

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() -> {
            transfer(List.of(1, 2), downloader, reports -> batches.add(List.copyOf(reports)), progress);
            transfer(List.of(3, 4), downloader, reports -> batches.add(List.copyOf(reports)), progress);
        }));

        assertEquals(List.of(1, 2, 3), visited);
        assertEquals(List.of(List.of(), List.of()), batches);
        assertTrue(failure.getMessage().endsWith(skipped(1) + "\n" + skipped(2) + "\n" + skipped(3)));
    }

    @Test
    void separateTaskInvocationsHaveIndependentStreaksAndSkipMessages() {
        var first = new FetchAds.DetailFetchProgress();
        var second = new FetchAds.DetailFetchProgress();
        var persisted = new ArrayList<AdsAdsetReport<String>>();

        var firstFailure = assertThrows(AdsPartialFailureException.class, () -> first.run(() ->
                transfer(List.of(1, 2), key -> {
                    request(first, key, true);
                    return List.of(report(key));
                }, persisted::addAll, first)));
        var secondFailure = assertThrows(AdsPartialFailureException.class, () -> second.run(() ->
                transfer(List.of(3, 4), key -> {
                    request(second, key, key == 3);
                    return List.of(report(key));
                }, persisted::addAll, second)));

        assertNull(firstFailure.getCause());
        assertNull(secondFailure.getCause());
        assertEquals(skipped(3), secondFailure.getMessage());
        assertEquals(List.of(report(4)), persisted);
    }

    @Test
    void failedCampaignCountsOnceAndOmitsEveryAssociatedAdsetReport() {
        var progress = new FetchAds.DetailFetchProgress();
        var visited = new ArrayList<Integer>();
        var persisted = new ArrayList<AdsAdsetReport<String>>();
        var successfulCampaign = List.of(report(3, 31, List.of("first phrase")),
                report(3, 32, List.of("second phrase")));

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() -> {
            try {
                FetchAds.transferDetailReports(List.of(1, 2, 3), key -> {
                    visited.add(key);
                    request(progress, key, key != 3);
                    return key == 3 ? successfulCampaign
                            : List.of(report(key, key * 10 + 1, List.of("first phrase")),
                                    report(key, key * 10 + 2, List.of("second phrase")));
                }, key -> "Skipped campaign %d for dates 2026-09-30-2026-10-01".formatted(key),
                        persisted::addAll, progress);
            } catch (SQLException e) {
                throw new RuntimeException("Error storing reports.", e);
            }
        }));

        assertEquals(List.of(1, 2, 3), visited);
        assertEquals(successfulCampaign, persisted);
        assertNull(failure.getCause(), "Two failed campaign URLs must not trigger the three-URL abort");
        assertEquals(List.of("Skipped campaign 1 for dates 2026-09-30-2026-10-01",
                "Skipped campaign 2 for dates 2026-09-30-2026-10-01"), failure.getMessage().lines().toList());
    }

    @Test
    void fatalDownloadErrorPreservesEarlierSkipMessagesAndTheOriginalCause() {
        var progress = new FetchAds.DetailFetchProgress();
        var original = new IllegalStateException("Invalid response\nmissing metadata");

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2), key -> {
                    if (key == 2) {
                        throw original;
                    }
                    request(progress, key, true);
                    return List.of(report(key));
                }, reports -> { }, progress)));

        assertSame(original, failure.getCause());
        assertEquals("Task aborted: Invalid response missing metadata\n" + skipped(1), failure.getMessage());
    }

    @Test
    void databaseFailurePreservesEarlierSkipMessagesAndSqlCause() {
        var progress = new FetchAds.DetailFetchProgress();
        var original = new SQLException("database unavailable");
        var received = new ArrayList<AdsAdsetReport<String>>();

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2), key -> {
                    request(progress, key, key == 1);
                    return List.of(report(key));
                }, reports -> {
                    received.addAll(reports);
                    throw original;
                }, progress)));

        assertEquals(List.of(report(2)), received);
        assertSame(original, failure.getCause().getCause());
        assertEquals("Task aborted: Error storing reports.\n" + skipped(1), failure.getMessage());
    }

    private void assertLiveSuccessResetsStreak(boolean emptyResponse) {
        var progress = new FetchAds.DetailFetchProgress();
        var visited = new ArrayList<Integer>();
        var persisted = new ArrayList<AdsAdsetReport<String>>();

        var failure = assertThrows(AdsPartialFailureException.class, () -> progress.run(() ->
                transfer(List.of(1, 2, 3, 4, 5, 6), key -> {
                    visited.add(key);
                    request(progress, key, key != 3 && key != 6);
                    return List.of(report(key, emptyResponse ? List.of() : List.of("row-" + key)));
                }, persisted::addAll, progress)));

        assertEquals(List.of(1, 2, 3, 4, 5, 6), visited);
        assertEquals(List.of(report(3, emptyResponse ? List.of() : List.of("row-3")),
                report(6, emptyResponse ? List.of() : List.of("row-6"))), persisted);
        assertNull(failure.getCause());
        assertEquals(skipped(1) + "\n" + skipped(2) + "\n" + skipped(4) + "\n" + skipped(5),
                failure.getMessage());
    }

    private static void request(FetchAds.DetailFetchProgress progress, int key, boolean unauthorized) {
        var url = "https://advertising.emag.net/report/" + key;
        var response = progress.fetcher(ignored -> unauthorized
                        ? new FetchAds.HttpResult(false, 401, "Unauthorized", "")
                        : new FetchAds.HttpResult(true, 200, "OK", EMPTY_RESPONSE), milliseconds -> { })
                .fetch(url);
        if (response.status() == 401) {
            throw new FetchAds.UnauthorizedException(url);
        }
    }

    private static void transfer(
            List<Integer> keys,
            Function<Integer, List<AdsAdsetReport<String>>> downloader,
            FetchAds.ReportWriter<String> writer,
            FetchAds.DetailFetchProgress progress
    ) {
        try {
            FetchAds.transferDetailReports(keys, downloader, FetchAdsDetailReportsTest::skipped, writer, progress);
        } catch (SQLException e) {
            throw new RuntimeException("Error storing reports.", e);
        }
    }

    private static AdsAdsetReport<String> report(int key) {
        return report(key, List.of("row-" + key));
    }

    private static AdsAdsetReport<String> report(int key, List<String> rows) {
        return report(key, key + 1_000, rows);
    }

    private static AdsAdsetReport<String> report(int campaignId, int adsetId, List<String> rows) {
        return new AdsAdsetReport<>(new AdsAdsetKey(VENDOR_ID, REPORT_DATE, campaignId, adsetId), rows);
    }

    private static String skipped(int key) {
        return "Skipped campaign %d adset %d for dates 2026-09-30-2026-10-01".formatted(key, key + 1_000);
    }
}
