package ro.sellfluence.emagdashboard;

import com.microsoft.playwright.APIResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchOffersTest {
    private static final JsonMapper mapper = JsonMapper.builder().build();
    private static final UUID vendorId = UUID.fromString("c5e89231-35af-44b0-bf21-cf4f3b3cab67");
    private static final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneId.of("Europe/Zurich"));

    @Test
    void fetchesEveryPageStartingAtZeroAndIncludesTheLastShortPage() {
        var requestedPages = new ArrayList<Integer>();

        var response = FetchOffers.getOffers(page -> {
            requestedPages.add(page);
            return switch (page) {
                case 0 -> page(27, 0, 25);
                case 1 -> page(27, 25, 27);
                default -> throw new AssertionError("Unexpected page " + page);
            };
        });

        assertEquals(List.of(0, 1), requestedPages);
        assertEquals(27, response.data().offers().totalNumberOfItems());
        assertEquals(27, response.data().offers().items().size());
        assertEquals("offer_26", response.data().offers().items().getLast().id());
    }

    @Test
    void stopsAtTheDeclaredTotalWithoutFetchingAnExtraPage() {
        var requests = new AtomicInteger();

        var response = FetchOffers.getOffers(page -> {
            requests.incrementAndGet();
            return page(25, 0, 25);
        });

        assertEquals(1, requests.get());
        assertEquals(25, response.data().offers().items().size());
    }

    @Test
    void acceptsAGenuinelyEmptySnapshot() {
        var response = FetchOffers.getOffers(page -> page(0, 0, 0));

        assertEquals(0, response.data().offers().totalNumberOfItems());
        assertTrue(response.data().offers().items().isEmpty());
    }

    @Test
    void rejectsIncompleteOrChangingPagination() {
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> page(26, 0, 24)));
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> page == 0 ? page(26, 0, 25) : page(26, 25, 25)));
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> page == 0 ? page(26, 0, 25) : page(27, 25, 27)));
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> page(1, 0, 2)));
    }

    @Test
    void rejectsMissingEnvelopeCollectionsAndInvalidTotals() {
        var invalidResponses = Arrays.asList(
                null,
                new OffersResponse(null),
                new OffersResponse(new OffersData(null)),
                new OffersResponse(new OffersData(new Offers(null, 0))),
                new OffersResponse(new OffersData(new Offers(List.of(), null))),
                new OffersResponse(new OffersData(new Offers(List.of(), -1)))
        );

        for (var response : invalidResponses) {
            assertThrows(IllegalStateException.class, () -> FetchOffers.getOffers(page -> response));
        }
    }

    @Test
    void rejectsMissingAndDuplicateOfferIdsIncludingAcrossPages() {
        for (var id : Arrays.asList(null, "", " ")) {
            var offer = mapper.readValue(mapper.writeValueAsString(java.util.Collections.singletonMap("id", id)), Offer.class);
            assertThrows(IllegalStateException.class, () -> FetchOffers.getOffers(page -> response(1, List.of(offer))));
        }
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> response(1, Arrays.asList((Offer) null))));
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> response(2, List.of(offer(0), offer(0)))));
        assertThrows(IllegalStateException.class,
                () -> FetchOffers.getOffers(page -> page == 0 ? page(26, 0, 25) : page(26, 0, 1)));
    }

    @Test
    void decodesSuccessfulResponseAndDisposesIt() {
        var disposed = new AtomicBoolean();

        var response = FetchOffers.decodeResponse(httpResponse(200,
                "{\"data\":{\"offers\":{\"items\":[],\"totalNumberOfItems\":0}}}", disposed));

        assertEquals(0, response.data().offers().totalNumberOfItems());
        assertTrue(disposed.get());
    }

    @Test
    void rejectsHttpErrorsAndDisposesTheirResponses() {
        var disposed = new AtomicBoolean();

        var error = assertThrows(IllegalStateException.class,
                () -> FetchOffers.decodeResponse(httpResponse(503, "Unavailable", disposed)));

        assertTrue(error.getMessage().contains("HTTP 503"));
        assertTrue(disposed.get());
    }

    @Test
    void rejectsMalformedGraphQlAndMissingDataResponsesAndDisposesThem() {
        for (var body : List.of(
                "not json",
                "null",
                "{}",
                "{\"data\":null}",
                "{\"data\":{\"offers\":{\"items\":[],\"totalNumberOfItems\":0}},\"errors\":[{\"message\":\"partial failure\"}]}"
        )) {
            var disposed = new AtomicBoolean();

            assertThrows(IllegalStateException.class,
                    () -> FetchOffers.decodeResponse(httpResponse(200, body, disposed)), body);

            assertTrue(disposed.get(), body);
        }
    }

    @Test
    void storesCompleteSnapshotOnceForTheVendorAndDateWhenFetchStarted() {
        var instant = new AtomicReference<>(Instant.parse("2026-09-24T21:59:59Z"));
        var movingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("Europe/Zurich");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return Clock.fixed(instant(), zone);
            }

            @Override
            public Instant instant() {
                return instant.get();
            }
        };
        var complete = page(1, 0, 1);
        var writes = new AtomicInteger();

        FetchOffers.transferOffersToDB(vendorId, movingClock, () -> {
            instant.set(Instant.parse("2026-09-24T22:00:01Z"));
            return complete;
        }, (vendor, date, data) -> {
            writes.incrementAndGet();
            assertEquals(vendorId, vendor);
            assertEquals(LocalDate.of(2026, 9, 24), date);
            assertSame(complete.data(), data);
            return data.offers().items().size();
        });

        assertEquals(1, writes.get());
        assertEquals(LocalDate.of(2026, 9, 25), LocalDate.now(movingClock));
    }

    @Test
    void doesNotStoreAfterAPartialFetchFailure() {
        var stored = new AtomicBoolean();
        var failure = new IllegalStateException("second page failed");

        var thrown = assertThrows(IllegalStateException.class, () -> FetchOffers.transferOffersToDB(
                vendorId, clock, () -> FetchOffers.getOffers(page -> {
                    if (page == 0) {
                        return page(26, 0, 25);
                    }
                    throw failure;
                }), (vendor, date, data) -> {
                    stored.set(true);
                    return 0;
                }));

        assertSame(failure, thrown);
        assertFalse(stored.get());
    }

    @Test
    void rejectsIncompleteSnapshotBeforeStorage() {
        var stored = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> FetchOffers.transferOffersToDB(
                vendorId, clock, () -> page(26, 0, 25), (vendor, date, data) -> {
                    stored.set(true);
                    return 0;
                }));

        assertFalse(stored.get());
    }

    @Test
    void propagatesStorageFailureWithVendorAndDateContext() {
        var failure = new SQLException("database failure");

        var thrown = assertThrows(IllegalStateException.class, () -> FetchOffers.transferOffersToDB(
                vendorId, clock, () -> page(0, 0, 0), (vendor, date, data) -> {
                    throw failure;
                }));

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains(vendorId.toString()));
        assertTrue(thrown.getMessage().contains("2026-09-24"));
    }

    private static OffersResponse page(int total, int from, int until) {
        return response(total, IntStream.range(from, until).mapToObj(FetchOffersTest::offer).toList());
    }

    private static OffersResponse response(int total, List<Offer> items) {
        return new OffersResponse(new OffersData(new Offers(items, total)));
    }

    private static Offer offer(int index) {
        return mapper.readValue("{\"id\":\"offer_" + index + "\"}", Offer.class);
    }

    private static APIResponse httpResponse(int status, String body, AtomicBoolean disposed) {
        return (APIResponse) Proxy.newProxyInstance(APIResponse.class.getClassLoader(), new Class<?>[]{APIResponse.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "status" -> status;
                    case "statusText" -> status == 200 ? "OK" : "Unavailable";
                    case "text" -> body;
                    case "dispose" -> {
                        disposed.set(true);
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected APIResponse call " + method.getName());
                });
    }
}
