package ro.sellfluence.emagdashboard;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Logs;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static ro.sellfluence.emagdashboard.FetchAds.randomWait;
import static ro.sellfluence.emagdashboard.FetchAds.withPlaywrightSession;

public class FetchOffers {
    private static final Logger logger = Logs.getConsoleAndFileLogger("FetchOffers", INFO, 10, 100_000);
    private static final int PAGE_SIZE = 25;

    private static final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new SimpleModule()
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer())
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer()))
            .build();

    private static final URI uri = URI.create("https://marketplace.emag.ro/global-listing");

    private static APIResponse sendGraphQLRequest(Page page, String op, String query, Map<String, ?> variables) {
        Map<String, Object> requestBody = Map.of(
                "operationName", op,
                "variables", variables,
                "query", query
        );
        return page.request().post(
                uri.toASCIIString(),
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Accept", "application/json, text/plain, */*")
                        .setHeader("X-Requested-With", "XMLHttpRequest")
                        .setData(requestBody)
        );
    }

    static OffersResponse getOffers(Page page) {
        page.navigate("https://marketplace.emag.ro/offers/list");
        randomWait(4.0, 6.0);
        return getOffers(pageNumber -> getOffersPage(page, pageNumber));
    }

    private static OffersResponse getOffersPage(Page page, int pageNumber) {
        var query = """
                query offers($filters: OfferFilterInput!) {
                  offers(filters: $filters) {
                    items {
                      id
                      isUnsafeForSite
                      sellerId
                      sellerName
                      eans
                      categoryDocId
                      categoryId
                      extId
                      valid
                      inactivationCriticalities
                      extStock
                      stock2p
                      stock3p
                      extSalePrice
                      extOriginalSalePrice
                      invalidationReason
                      extStatus
                      extHandlingTime
                      fullfilledByEmag
                      extUrl
                      extPartNumber
                      brandName
                      mktId
                      type
                      offerProperties
                      minPrice
                      maxPrice
                      extWarranty
                      offerDetailsUnfairPrice
                      offerDetailsLockerEligibility
                      offerDetailsSupplyLeadTime
                      offerDetailsEmagClub
                      offerDetailsEmagClubType
                      offerDetailsEmagClubMerged
                      greenTax
                      productPerformanceSuggestedPrice
                      productPerformanceBuyButtonRank
                      productPerformanceNeededStockNext7
                      productPerformanceNeededStockNext30
                      productPerformanceDepletionDays
                      productPerformanceMultiofferOffersCount
                      productPerformanceOrderValue2
                      rrp
                      rrpEmag
                      rrpEmagType
                      rrpSeller
                      rrpType
                      rrpBadge
                      isStockSupplyNeeded
                      platformId
                      currency
                      docProductPartNumberKey
                      docProductPartNumber
                      docProductName
                      docProductId
                      docProductBrandName
                      docProductBrandMktId
                      productPerformanceMultiofferBestPrice
                      productPerformanceMultiofferNoOfReviews
                      productPerformanceMultiofferReviewScore
                      measurements
                      offerStock {
                        category
                        statusType
                        statusName
                        inactivationReason
                        hotnessType
                        hotnessName
                        rrpBadgeColor
                        rrpGuideline
                        rrpValue
                        vatId
                        startDate
                        propertyTypes
                        placeOrderToSupplierUntil
                        invalidationReasonLabel
                      }
                      offerPrice {
                        isEanMandatory
                        isEmagClubEligible
                        statusDetails
                      }
                      links {
                        edit
                        seller
                        details
                      }
                      recycleWarrantiesQuantity
                      productPerformanceLostOrderValue
                      productPerformanceDaysWithoutStock
                      offersOutOfStockDay
                      productPerformanceOrderValue1
                      invalidationReasonDate
                      invalidationReasonName
                      invalidationSubReason
                      invalidationSubReasonName
                      hasSupplyRecommendation
                      supplyRecommendationData {
                        recommendedQtyReplenish
                        replenishmentPeriod
                        daysUntilOos
                        estimatedDaysUntilOos
                        daysInStockL35
                        unitsSoldL35
                        unitsForecastN35
                        unitsInTransit
                        stockInCurrentDay
                      }
                    }
                    totalNumberOfItems
                  }
                }""";


        var variables = Map.of(
                "filters", Map.of(
                        "pagination", Map.of("page", pageNumber, "limit", PAGE_SIZE),
                        "platformType", "ROMANIA",
                        "sort", List.of(
                                Map.of("field", "extId", "direction", "ASC")
                        )
                )
        );

        logger.log(INFO, "Fetching offers page " + pageNumber);
        return decodeResponse(sendGraphQLRequest(page, "offers", query, variables));
    }

    static OffersResponse decodeResponse(APIResponse response) {
        try {
            if (response.status() != 200) {
                throw new IllegalStateException("eMAG offers request failed with HTTP %d %s."
                        .formatted(response.status(), response.statusText()));
            }
            try {
                var decoded = jsonMapper.readValue(response.text(), OffersResponse.class);
                requireOffers(decoded);
                return decoded;
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Could not deserialize offers response", exception);
            }
        } finally {
            response.dispose();
        }
    }

    /** Fetch every page before exposing a snapshot that can replace existing daily data. */
    static OffersResponse getOffers(IntFunction<OffersResponse> fetchPage) {
        var items = new ArrayList<Offer>();
        var ids = new HashSet<String>();
        Integer expectedTotal = null;
        for (int pageNumber = 0; ; pageNumber++) {
            var offers = requireOffers(fetchPage.apply(pageNumber));
            if (expectedTotal == null) {
                expectedTotal = offers.totalNumberOfItems();
            } else if (!expectedTotal.equals(offers.totalNumberOfItems())) {
                throw new IllegalStateException("eMAG offers total changed during pagination.");
            }
            for (var offer : offers.items()) {
                if (offer == null || offer.id() == null || offer.id().isBlank()) {
                    throw new IllegalStateException("eMAG offers response contains an offer without an ID.");
                }
                if (!ids.add(offer.id())) {
                    throw new IllegalStateException("eMAG offers response contains duplicate offer ID " + offer.id());
                }
                items.add(offer);
            }
            if (items.size() > expectedTotal) {
                throw new IllegalStateException("eMAG offers response contains more offers than its total.");
            }
            if (items.size() == expectedTotal) {
                return new OffersResponse(new OffersData(new Offers(List.copyOf(items), expectedTotal)));
            }
            if (offers.items().size() != PAGE_SIZE) {
                throw new IllegalStateException("eMAG offers response ended before all offers were fetched.");
            }
        }
    }

    private static Offers requireOffers(OffersResponse response) {
        if (response == null || response.data() == null || response.data().offers() == null) {
            throw new IllegalStateException("eMAG offers response has no offers data.");
        }
        var offers = response.data().offers();
        if (offers.items() == null || offers.totalNumberOfItems() == null || offers.totalNumberOfItems() < 0) {
            throw new IllegalStateException("eMAG offers response has missing or invalid pagination data.");
        }
        return offers;
    }

    /** Fetch the vendor's current offers and replace its snapshot for the fetch date. */
    public static void fetchOffers(String alias, EmagMirrorDB mirrorDB, Clock clock) {
        Objects.requireNonNull(mirrorDB);
        Objects.requireNonNull(clock);
        if (Boolean.parseBoolean(System.getProperty("ads.offline", "false"))) {
            throw new IllegalStateException("Offers snapshots require a live fetch; ads.offline must be false.");
        }
        UUID vendorId;
        try {
            vendorId = mirrorDB.requireVendorIdByAccount(alias);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot resolve the offers vendor for account " + alias + ".", exception);
        }
        withPlaywrightSession(alias, (page, _) -> transferOffersToDB(
                vendorId, clock, () -> getOffers(page), mirrorDB::storeOffersSnapshot));
    }

    @FunctionalInterface
    interface SnapshotStore {
        int store(UUID vendorId, LocalDate fetchDate, OffersData data) throws SQLException;
    }

    static void transferOffersToDB(UUID vendorId, Clock clock, Supplier<OffersResponse> fetch, SnapshotStore store) {
        var fetchDate = LocalDate.now(clock);
        var response = fetch.get();
        var offers = requireOffers(response);
        if (offers.items().size() != offers.totalNumberOfItems()) {
            throw new IllegalStateException("Cannot store an incomplete offers snapshot.");
        }
        try {
            var rows = store.store(vendorId, fetchDate, response.data());
            logger.log(INFO, "Stored %d offers for %s (vendor %s).".formatted(rows, fetchDate, vendorId));
        } catch (SQLException exception) {
            logger.log(SEVERE, "Could not store offers for %s (vendor %s).".formatted(fetchDate, vendorId));
            throw new IllegalStateException("Could not store offers for %s (vendor %s)."
                    .formatted(fetchDate, vendorId), exception);
        }
    }

    static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {
        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) {
            return LocalDateTime.parse(parser.getString(), FORMATTER);
        }
    }

    static class LocalDateDeserializer extends ValueDeserializer<LocalDate> {
        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
            return LocalDate.parse(parser.getString(), FORMATTER);
        }
    }
}
