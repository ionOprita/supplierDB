package ro.sellfluence.apphelper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.ReviewsTable.StoreResult;
import ro.sellfluence.emagsiteapi.EmagSAPI;
import ro.sellfluence.emagsiteapi.ReviewsResponse;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/** Fetches a complete review snapshot for each product that is still being sold. */
public final class FetchReviews {
    private static final Logger logger = Logs.getConsoleAndFileLogger("FetchReviews", INFO, 10, 1_000_000);

    private FetchReviews() {
    }

    public static void fetchReviews(EmagMirrorDB db, Clock clock) throws SQLException, InterruptedException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(clock, "clock");
        checkInterrupted();
        var pnks = eligiblePnks(db.readProducts());
        if (pnks.isEmpty()) {
            logger.info("Fetching reviews for 0 products");
            return;
        }
        try (var playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var context = browser.newContext()) {
            var page = context.newPage();
            page.navigate("https://emag.ro/");
            processReviews(clock, pnks, pnk -> EmagSAPI.getReviews(page, pnk), db::storeReviews);
        }
    }

    static void fetchReviews(Clock clock, ProductReader reader, ReviewFetcher fetcher, ReviewStore store)
            throws SQLException, InterruptedException {
        Objects.requireNonNull(clock, "clock");
        checkInterrupted();
        var pnks = eligiblePnks(reader.read());
        processReviews(clock, pnks, fetcher, store);
    }

    private static List<String> eligiblePnks(List<ProductInfo> products) {
        return products.stream()
                .filter(product -> product.continueToSell() && !product.retracted())
                .map(ProductInfo::pnk)
                .filter(pnk -> pnk != null && !pnk.isBlank())
                .map(String::strip)
                .distinct()
                .sorted()
                .toList();
    }

    private static void processReviews(Clock clock, List<String> pnks, ReviewFetcher fetcher, ReviewStore store)
            throws SQLException, InterruptedException {
        checkInterrupted();
        var failures = new ArrayList<Exception>();
        int succeeded = 0;
        int inserted = 0;
        int changed = 0;
        int unchanged = 0;
        int notReturned = 0;
        logger.info("Fetching reviews for %d products".formatted(pnks.size()));

        for (int index = 0; index < pnks.size(); index++) {
            checkInterrupted();
            var pnk = pnks.get(index);
            logger.info("Fetching reviews for %s (%d/%d)".formatted(pnk, index + 1, pnks.size()));
            try {
                var response = fetcher.fetch(pnk);
                checkInterrupted();
                // Timestamp only complete responses, after all pages have been fetched.
                var result = store.store(pnk, clock.instant(), response);
                succeeded++;
                inserted += result.inserted();
                changed += result.changed();
                unchanged += result.unchanged();
                notReturned += result.notReturned();
                logger.info("Stored reviews for %s: %d inserted, %d changed, %d unchanged, %d not returned"
                        .formatted(pnk, result.inserted(), result.changed(), result.unchanged(), result.notReturned()));
                checkInterrupted();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (Exception exception) {
                if (Thread.currentThread().isInterrupted()) {
                    var interrupted = new InterruptedException("Review fetch interrupted for " + pnk);
                    interrupted.initCause(exception);
                    throw interrupted;
                }
                failures.add(new IllegalStateException("Unable to fetch or store reviews for " + pnk, exception));
                logger.log(WARNING, "Unable to fetch or store reviews for " + pnk, exception);
            }
        }

        checkInterrupted();
        logger.info("Reviews refresh: %d/%d products succeeded, %d failed; "
                .formatted(succeeded, pnks.size(), failures.size())
                + "%d inserted, %d changed, %d unchanged, %d not returned"
                .formatted(inserted, changed, unchanged, notReturned));
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Review fetch failed for %d of %d products"
                    .formatted(failures.size(), pnks.size()));
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Review fetch interrupted");
        }
    }

    @FunctionalInterface
    interface ProductReader {
        List<ProductInfo> read() throws SQLException;
    }

    @FunctionalInterface
    interface ReviewFetcher {
        ReviewsResponse fetch(String pnk) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ReviewStore {
        StoreResult store(String pnk, Instant fetchedAt, ReviewsResponse response) throws SQLException;
    }
}
