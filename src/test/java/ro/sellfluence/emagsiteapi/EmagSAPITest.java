package ro.sellfluence.emagsiteapi;

import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmagSAPITest {

    @Test
    void parsesAndCombinesAllPagesAndRetriesEachPageIndependently() throws Exception {
        var calls = new AtomicInteger();
        var requestedOffsets = new ArrayList<Integer>();
        var delays = new ArrayList<Long>();

        var response = EmagSAPI.getReviews(offset -> {
            requestedOffsets.add(offset);
            return switch (calls.getAndIncrement()) {
                case 0 -> new EmagSAPI.HttpResult(HTTP_BAD_GATEWAY, "");
                case 1 -> new EmagSAPI.HttpResult(HTTP_OK, page(3, review(11), review(12)));
                case 2 -> throw new IOException("temporary failure");
                case 3 -> new EmagSAPI.HttpResult(HTTP_OK, page(3, review(13)));
                default -> throw new AssertionError("Unexpected request");
            };
        }, delays::add);

        assertEquals(List.of(0, 0, 2, 2), requestedOffsets);
        assertEquals(List.of(10_000L, 10_000L), delays);
        assertEquals(3, response.data().count());
        assertEquals(List.of(11L, 12L, 13L), response.data().items().stream().map(Review::id).toList());
        assertEquals("review 11", response.data().items().getFirst().contentNoTags());
        assertEquals("Alice", response.data().items().getFirst().user().name());
        assertEquals("PNK", response.data().items().getFirst().product().partNumberKey());
        var price = response.data().items().getFirst().product().offer().price();
        assertEquals(new BigDecimal("199.99"), price.current());
        assertEquals(new BigDecimal("90"), price.discount().absolute());
        assertEquals("RON", price.currency().name().defaultName());
        assertEquals(new BigDecimal("289.99"), price.recommendedRetailPrice().amount());
        assertEquals(new BigDecimal("0"), price.lowestPrice30Days().amount());
        assertEquals("https://www.emag.ro", response.data().items().getFirst().viewUrl().desktopBase());
        assertEquals(1, response.data().items().getFirst().comments().size());
        assertEquals(5, response.data().ratingDistribution().get(5));
        assertEquals(true, response.data().sortOptions().getFirst().selected());
    }

    @Test
    void givesUpAfterFourRetries() {
        var requestedOffsets = new ArrayList<Integer>();
        var delays = new ArrayList<Long>();

        var exception = assertThrows(IllegalStateException.class, () -> EmagSAPI.getReviews(offset -> {
            requestedOffsets.add(offset);
            return new EmagSAPI.HttpResult(HTTP_BAD_GATEWAY, "");
        }, delays::add));

        assertEquals("eMAG returned HTTP 502", exception.getMessage());
        assertEquals(List.of(0, 0, 0, 0, 0), requestedOffsets);
        assertEquals(List.of(10_000L, 20_000L, 40_000L, 80_000L), delays);
    }

    @Test
    void retriesPlaywrightTransportFailures() throws Exception {
        var calls = new AtomicInteger();
        var delays = new ArrayList<Long>();

        var response = EmagSAPI.getReviews(offset -> {
            assertEquals(0, offset);
            if (calls.getAndIncrement() == 0) {
                throw new PlaywrightException("connection reset");
            }
            return new EmagSAPI.HttpResult(HTTP_OK, page(0));
        }, delays::add);

        assertEquals(0, response.data().count());
        assertEquals(2, calls.get());
        assertEquals(List.of(10_000L), delays);
    }

    @Test
    void acceptsAnExplicitlyEmptyResponseWithoutAnotherRequest() throws Exception {
        var requestedOffsets = new ArrayList<Integer>();
        var response = EmagSAPI.getReviews(offset -> {
            requestedOffsets.add(offset);
            return new EmagSAPI.HttpResult(HTTP_OK, page(0));
        }, ignored -> { throw new AssertionError("Unexpected retry"); });

        assertEquals(0, response.data().count());
        assertEquals(List.of(), response.data().items());
        assertEquals(List.of(0), requestedOffsets);
    }

    @Test
    void rejectsMissingPaginationFieldsInsteadOfReportingAnEmptyFetch() {
        for (var data : List.of("{}", "{\"items\":[]}")) {
            assertThrows(RuntimeException.class, () -> EmagSAPI.getReviews(
                    offset -> new EmagSAPI.HttpResult(HTTP_OK, "{\"code\":200,\"data\":" + data + "}"),
                    ignored -> { throw new AssertionError("Invalid payload should not be retried"); }
            ), data);
        }
    }

    @Test
    void treatsMissingItemsAsEmptyButStillRejectsAnIncompleteNonemptyPage() throws Exception {
        var emptyResponse = EmagSAPI.getReviews(
                offset -> new EmagSAPI.HttpResult(HTTP_OK, "{\"code\":200,\"data\":{\"count\":0}}"),
                ignored -> { throw new AssertionError("Unexpected retry"); });
        assertEquals(List.of(), emptyResponse.data().items());

        assertThrows(IllegalStateException.class, () -> EmagSAPI.getReviews(
                offset -> new EmagSAPI.HttpResult(HTTP_OK, "{\"code\":200,\"data\":{\"count\":1}}"),
                ignored -> { throw new AssertionError("Unexpected retry"); }));
    }

    @Test
    void rejectsDuplicatedReviewIdsAcrossPages() {
        var offsets = new ArrayList<Integer>();
        assertThrows(IllegalStateException.class, () -> EmagSAPI.getReviews(offset -> {
            offsets.add(offset);
            return new EmagSAPI.HttpResult(HTTP_OK, page(2, review(11)));
        }, ignored -> { throw new AssertionError("Unexpected retry"); }));
        assertEquals(List.of(0, 1), offsets);
    }

    @Test
    void rejectsMissingIdsTruncatedPagesChangingCountsAndExtraItems() {
        var responses = List.of(
                List.of(page(1, "{}")),
                List.of(page(2, review(11)), page(2)),
                List.of(page(2, review(11)), page(3, review(12))),
                List.of(page(1, review(11), review(12)))
        );
        for (var pages : responses) {
            var index = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> EmagSAPI.getReviews(
                    offset -> new EmagSAPI.HttpResult(HTTP_OK, pages.get(index.getAndIncrement())),
                    ignored -> { throw new AssertionError("Unexpected retry"); }
            ));
        }
    }

    @Test
    void propagatesInterruptionDuringBackoffWithoutAnotherRequest() {
        var calls = new AtomicInteger();
        var interruption = new InterruptedException("shutdown");
        var thrown = assertThrows(InterruptedException.class, () -> EmagSAPI.getReviews(offset -> {
            calls.incrementAndGet();
            return new EmagSAPI.HttpResult(HTTP_BAD_GATEWAY, "");
        }, ignored -> { throw interruption; }));
        assertSame(interruption, thrown);
        assertEquals(1, calls.get());
    }

    private static String page(int count, String... reviews) {
        return """
                {
                  "code": 200,
                  "data": {
                    "count": %d,
                    "first_item": %s,
                    "items": [%s],
                    "summary": "Useful summary",
                    "suggested_questions": ["How long does it last?"],
                    "rating_distribution": {"5": 5},
                    "review_characteristics_average_rating": [
                      {"review_characteristics_id": 801, "name": "Quality", "average_rating": 4.8}
                    ],
                    "sort_options": [
                      {"name": "created", "direction": "desc", "placeholder": "Newest",
                       "isSelected": true, "isDefault": true}
                    ]
                  }
                }
                """.formatted(count, reviews.length == 0 ? "null" : reviews[0], String.join(",", reviews));
    }

    private static String review(long id) {
        return """
                {
                  "id": %d,
                  "content": "<p>review %d</p>",
                  "content_no_tags": "review %d",
                  "user": {"id": 7, "name": "Alice", "is_official": false},
                  "is_active": true,
                  "created": "2026-09-21T15:00:11+03:00",
                  "view_url": {"path": "/review/%d", "desktop_base": "https://www.emag.ro"},
                  "product": {
                    "id": 9,
                    "part_number_key": "PNK",
                    "offer": {
                      "id": 10,
                      "price": {
                        "current": 199.99,
                        "is_min": false,
                        "is_max": false,
                        "legal": 0,
                        "prefix": " ",
                        "suffix": " Lei",
                        "is_visible": true,
                        "discount": {
                          "type": "value",
                          "absolute": 90,
                          "percent": 31,
                          "is_special": false,
                          "is_visible": false,
                          "is_restricted_from_view": true,
                          "is_max": false,
                          "label": "Difference:",
                          "labeled_as_discount": false
                        },
                        "currency": {"id": 23, "name": {"default": "RON", "display": "Lei"}},
                        "net": 165.28,
                        "recommended_retail_price": {
                          "amount": 289.99,
                          "is_visible": true,
                          "label": "PRP",
                          "tooltip": "Recommended retail price"
                        },
                        "lowest_price_30_days": {
                          "amount": 0,
                          "is_visible": false,
                          "tooltip": "Lowest price"
                        },
                        "initial": 199.99
                      }
                    }
                  },
                  "rating": 5,
                  "comments": [{"id": 21, "content_no_tags": "Thank you", "parent_id": %d}]
                }
                """.formatted(id, id, id, id, id);
    }
}
