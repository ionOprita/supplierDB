package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.ReviewsTable.StoreResult;
import ro.sellfluence.emagsiteapi.ReviewsData;
import ro.sellfluence.emagsiteapi.ReviewsResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FetchReviewsTest {
    private static final Instant START = Instant.parse("2026-09-28T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
    private static final StoreResult EMPTY_RESULT = new StoreResult(0, 0, 0, 0);
    private static final ReviewsResponse EMPTY_RESPONSE = new ReviewsResponse(200,
            new ReviewsData(0, null, List.of(), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null), List.of(), null);

    @Test
    void readsOnceFiltersAllThreeConditionsAndFetchesDistinctStrippedPnksSequentially() throws Exception {
        var reads = new AtomicInteger();
        var events = new ArrayList<String>();
        var products = List.of(
                product(" B ", true, false),
                product("A", true, false),
                product("B", true, false),
                product("\tA\n", true, false),
                product("not-for-sale", false, false),
                product("retracted", true, true),
                product("both-excluded", false, true),
                product(null, true, false),
                product("", true, false),
                product(" \t\n", true, false)
        );

        FetchReviews.fetchReviews(CLOCK, () -> {
            reads.incrementAndGet();
            return products;
        }, pnk -> {
            events.add("fetch:" + pnk);
            return EMPTY_RESPONSE;
        }, (pnk, fetchedAt, response) -> {
            events.add("store:" + pnk);
            assertEquals(START, fetchedAt);
            assertSame(EMPTY_RESPONSE, response);
            return EMPTY_RESULT;
        });

        assertEquals(1, reads.get());
        assertEquals(List.of("fetch:A", "store:A", "fetch:B", "store:B", "fetch:not-for-sale", "store:not-for-sale"), events);
    }

    @Test
    void timestampsTheCompletedResponseAfterFetching() throws Exception {
        var time = new AtomicReference<>(START);
        var clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return Clock.fixed(instant(), zone);
            }

            @Override
            public Instant instant() {
                return time.get();
            }
        };

        FetchReviews.fetchReviews(clock, () -> List.of(product("A", true, false)), _ -> {
            time.set(START.plusSeconds(90));
            return EMPTY_RESPONSE;
        }, (_, fetchedAt, _) -> {
            assertEquals(START.plusSeconds(90), fetchedAt);
            return EMPTY_RESULT;
        });
    }

    @Test
    void continuesAfterFetchAndStorageFailuresThenReportsEveryFailedPnk() {
        var events = new ArrayList<String>();
        var fetchFailure = new IOException("Connection failed");
        var storeFailure = new SQLException("Transaction rolled back");

        var exception = assertThrows(IllegalStateException.class, () -> FetchReviews.fetchReviews(CLOCK,
                () -> List.of(product("C", true, false), product("B", true, false), product("A", true, false)),
                pnk -> {
                    events.add("fetch:" + pnk);
                    if (pnk.equals("A")) throw fetchFailure;
                    return EMPTY_RESPONSE;
                }, (pnk, _, _) -> {
                    events.add("store:" + pnk);
                    if (pnk.equals("B")) throw storeFailure;
                    return new StoreResult(1, 2, 3, 4);
                }));

        assertEquals(List.of("fetch:A", "fetch:B", "store:B", "fetch:C", "store:C"), events);
        assertEquals("Review fetch failed for 2 of 3 products", exception.getMessage());
        assertEquals(2, exception.getSuppressed().length);
        assertTrue(exception.getSuppressed()[0].getMessage().contains("A"));
        assertSame(fetchFailure, exception.getSuppressed()[0].getCause());
        assertTrue(exception.getSuppressed()[1].getMessage().contains("B"));
        assertSame(storeFailure, exception.getSuppressed()[1].getCause());
    }

    @Test
    void noEligibleProductsRequiresNoNetworkOrStorage() throws Exception {
        FetchReviews.fetchReviews(CLOCK, List::of,
                _ -> fail("No products to fetch"), (_, _, _) -> fail("No response to store"));
    }

    @Test
    void productReadFailurePreventsFetching() {
        var failure = new SQLException("Read failed");
        var exception = assertThrows(SQLException.class, () -> FetchReviews.fetchReviews(CLOCK,
                () -> { throw failure; },
                _ -> fail("Products could not be read"), (_, _, _) -> fail("No response to store")));

        assertSame(failure, exception);
    }

    @Test
    void interruptionStopsFetchingAndPreservesTheInterruptFlag() {
        var requested = new ArrayList<String>();
        var interrupted = new InterruptedException("Interrupted request");
        try {
            var exception = assertThrows(InterruptedException.class, () -> FetchReviews.fetchReviews(CLOCK,
                    () -> List.of(product("A", true, false), product("B", true, false)), pnk -> {
                        requested.add(pnk);
                        throw interrupted;
                    }, (_, _, _) -> fail("Interrupted response must not be stored")));

            assertSame(interrupted, exception);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("A"), requested);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void alreadyInterruptedTaskDoesNotEvenReadProducts() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> FetchReviews.fetchReviews(CLOCK,
                    () -> fail("Interrupted task must not read products"),
                    _ -> fail("Interrupted task must not fetch"),
                    (_, _, _) -> fail("Interrupted task must not store")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptAfterFetchPreventsStorage() {
        try {
            assertThrows(InterruptedException.class, () -> FetchReviews.fetchReviews(CLOCK,
                    () -> List.of(product("A", true, false)), _ -> {
                        Thread.currentThread().interrupt();
                        return EMPTY_RESPONSE;
                    }, (_, _, _) -> fail("Interrupted task must not store")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptDuringStorageFailureStopsSubsequentProducts() {
        var requested = new ArrayList<String>();
        var failure = new SQLException("Interrupted storage");
        try {
            var exception = assertThrows(InterruptedException.class, () -> FetchReviews.fetchReviews(CLOCK,
                    () -> List.of(product("A", true, false), product("B", true, false)), pnk -> {
                        requested.add(pnk);
                        return EMPTY_RESPONSE;
                    }, (_, _, _) -> {
                        Thread.currentThread().interrupt();
                        throw failure;
                    }));
            assertSame(failure, exception.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("A"), requested);
        } finally {
            Thread.interrupted();
        }
    }

    private static ProductInfo product(String pnk, boolean continueToSell, boolean retracted) {
        return new ProductInfo(pnk, "code", "Product", null, continueToSell, retracted,
                null, null, null, null);
    }
}
