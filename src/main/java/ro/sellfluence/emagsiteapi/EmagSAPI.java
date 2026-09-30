package ro.sellfluence.emagsiteapi;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.emagdashboard.FetchAds.randomWait;

public class EmagSAPI {

    private static final Logger logger = Logger.getLogger(EmagSAPI.class.getName());
    private static final int PAGE_LIMIT = 100;
    private static final int MAX_REQUEST_RETRIES = 4;
    private static final int MAX_LOGGED_RESPONSE_CHARS = 8_000;
    private static final long INITIAL_RETRY_DELAY_MILLISECONDS = 10_000;
    private static final Set<Integer> RETRYABLE_STATUS_CODES =
            Set.of(HTTP_INTERNAL_ERROR, HTTP_BAD_GATEWAY, HTTP_GATEWAY_TIMEOUT);

    private static final String BASE_URL =
            "https://sapi.emag.ro/products/%s/reviews"
                    + "?fields%%5Bitems%%5D=1"
                    + "&fields%%5Bitems%%5D%%5Bcontent_no_tags%%5D=1"
                    + "&page%%5Blimit%%5D=" + PAGE_LIMIT
                    + "&page%%5Boffset%%5D=%d";

    private static final JsonMapper objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Fetches and combines every page of reviews for a product.
     */
    public static ReviewsResponse getReviews(Page page, String pnk) throws IOException, InterruptedException {
        return getReviews(
                pnk,
                offset -> sendRequest(page, BASE_URL.formatted(pnk, offset)),
                Thread::sleep
        );
    }

    static ReviewsResponse getReviews(PageFetcher pageFetcher, Sleeper sleeper)
            throws IOException, InterruptedException {
        return getReviews(null, pageFetcher, sleeper);
    }

    private static ReviewsResponse getReviews(String pnk, PageFetcher pageFetcher, Sleeper sleeper)
            throws IOException, InterruptedException {
        var reviews = new ArrayList<Review>();
        var reviewIds = new HashSet<Long>();
        ReviewsResponse firstResponse = null;
        int offset = 0;

        while (firstResponse == null || reviews.size() < firstResponse.data().count()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while fetching eMAG reviews");
            }
            var response = fetchPageWithRetry(offset, pnk, pageFetcher, sleeper);
            validateResponse(response);

            if (firstResponse == null) {
                firstResponse = response;
            } else if (response.data().count() != firstResponse.data().count()) {
                throw new IllegalStateException("eMAG review count changed during pagination");
            }

            var pageReviews = response.data().items();
            if (pageReviews.isEmpty() && reviews.size() < firstResponse.data().count()) {
                throw new IllegalStateException("eMAG returned an empty review page before all reviews were fetched");
            }

            for (var review : pageReviews) {
                if (review.id() == null || !reviewIds.add(review.id())) {
                    throw new IllegalStateException("eMAG returned a missing or duplicate review ID at offset " + offset);
                }
            }
            reviews.addAll(pageReviews);
            offset += pageReviews.size();
        }

        if (firstResponse == null) {
            throw new IllegalStateException("eMAG returned no review response");
        }
        if (reviews.size() != firstResponse.data().count()) {
            throw new IllegalStateException(
                    "eMAG returned %d reviews, expected %d"
                            .formatted(reviews.size(), firstResponse.data().count())
            );
        }
        return firstResponse.withItems(reviews);
    }

    private static ReviewsResponse fetchPageWithRetry(
            int offset,
            String pnk,
            PageFetcher pageFetcher,
            Sleeper sleeper
    ) throws IOException, InterruptedException {
        int retriesRemaining = MAX_REQUEST_RETRIES;
        long retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS;

        while (true) {
            try {
                var httpResponse = pageFetcher.fetch(offset);
                if (httpResponse.statusCode() == HTTP_OK) {
                    try {
                        return objectMapper.readValue(httpResponse.body(), ReviewsResponse.class);
                    } catch (JacksonException exception) {
                        var body = httpResponse.body();
                        var excerpt = body.length() > MAX_LOGGED_RESPONSE_CHARS
                                ? body.substring(0, MAX_LOGGED_RESPONSE_CHARS) + "… [truncated]"
                                : body;
                        logger.log(WARNING,
                                "Unable to parse eMAG reviews for PNK {0} at offset {1}; response body "
                                        + "({2} characters, excerpt follows): {3}",
                                new Object[]{pnk == null ? "(unknown)" : pnk, offset, body.length(), excerpt});
                        throw exception;
                    }
                }
                if (!RETRYABLE_STATUS_CODES.contains(httpResponse.statusCode()) || retriesRemaining == 0) {
                    throw new IllegalStateException("eMAG returned HTTP " + httpResponse.statusCode());
                }

                logger.log(WARNING,
                        "eMAG returned HTTP {0}; retrying offset {1} after {2} seconds ({3} retries remain)",
                        new Object[]{httpResponse.statusCode(), offset, retryDelay / 1_000, retriesRemaining});
            } catch (IOException | PlaywrightException exception) {
                if (retriesRemaining == 0) {
                    throw exception;
                }
                logger.log(WARNING,
                        "eMAG request failed; retrying offset {0} after {1} seconds ({2} retries remain)",
                        new Object[]{offset, retryDelay / 1_000, retriesRemaining});
            }

            retriesRemaining--;
            sleeper.sleep(retryDelay);
            retryDelay *= 2;
        }
    }

    private static void validateResponse(ReviewsResponse response) {
        if (response == null || response.code() != HTTP_OK || response.data() == null) {
            throw new IllegalStateException("eMAG returned an invalid review response");
        }
        if (response.data().count() < 0 || response.data().items() == null) {
            throw new IllegalStateException("eMAG returned invalid review pagination data");
        }
    }

    private static HttpResult sendRequest(Page page, String url) throws InterruptedException {
        randomWait(1.5, 2.5);
        APIResponse response = page.request().get(url, RequestOptions.create()
                .setHeader("X-Request-Source", "mobile-app"));
        try {
            return new HttpResult(response.status(), response.text());
        } finally {
            response.dispose();
        }
    }

    @FunctionalInterface
    interface PageFetcher {
        HttpResult fetch(int offset) throws IOException, InterruptedException, PlaywrightException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    record HttpResult(int statusCode, String body) {
    }
}
