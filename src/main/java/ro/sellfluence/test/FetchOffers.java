package ro.sellfluence.test;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import org.jetbrains.annotations.NotNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.support.Arguments;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.FetchAds.randomWait;
import static ro.sellfluence.apphelper.FetchAds.withPlaywrightSession;

public class FetchOffers {
    static void main(String[] args) throws SQLException, IOException {
        System.setProperty("ads.headless", "false");
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        withPlaywrightSession("sellfusion", (page, _) -> {
            page.navigate("https://marketplace.emag.ro/offers/list");
            randomWait(4.0, 6.0);
            getSeller(page);
            getOffers(page);
        });
    }

    private static final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final URI uri = URI.create("https://marketplace.emag.ro/global-listing");

    private static void getSeller(Page page) {
        // "query seller($seller: SellerSearchFilterInput!) {\n  sellers(filters: $seller) {\n    items {\n      sellerId\n      sellerName\n      platformId\n    }\n  }\n}"}
        var variables = Map.of(
                "seller", Map.of("key", "")
        );
        var query = """
                query seller($seller: SellerSearchFilterInput!) {
                  sellers(filters: $seller) {
                    items {
                      sellerId
                      sellerName
                      platformId
                   }
                  }
                }""";
        sendGarphiQLRequest(page, "seller", query, variables);
    }

    private static void sendGarphiQLRequestJS(Page page, String op, String query, Map<String, ?> variables) {
        Map<String, Object> requestBody = Map.of(
                "operationName", op,
                "variables", variables,
                "query", query
        );
        var jsonBody = jsonMapper.writeValueAsString(requestBody);

        IO.println("Sending request with body:\n" + jsonBody + "\n");
        var result = page.evaluate("""
    async ({ url, body }) => {
        const response = await fetch(url, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json, text/plain, */*',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify(body)
        });

        return {
            status: response.status,
            body: await response.text()
        };
    }
    """,
                Map.of(
                        "url", uri.toASCIIString(),
                        "body", requestBody
                )
        );
        IO.println("Response body:\n" + result + "\n");
    }

    private static void sendGarphiQLRequest(Page page, String op, String query, Map<String, ?> variables) {
        Map<String, Object> requestBody = Map.of(
                "operationName", op,
                "variables", variables,
                "query", query
        );
        var jsonBody = jsonMapper.writeValueAsString(requestBody);

        IO.println("Sending request with body:\n" + jsonBody + "\n");
        var response = page.request().post(
                uri.toASCIIString(),
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Accept", "application/json, text/plain, */*")
                        .setHeader("X-Requested-With", "XMLHttpRequest")
                        .setData(requestBody)
        );
        IO.println("Status code: " + response.status() + "\n");
        IO.println("Status test: " + response.statusText() + "\n");
        IO.println("Response body:\n" + response.text() + "\n");
    }

    private static void getOffers(Page page) {
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
                      productPerformanceBuyButtonRank
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
                        "pagination", Map.of("page", 0, "limit", 25),
                        "platformType", "ROMANIA",
                        "sort", List.of(
                                Map.of("field", "extId", "direction", "ASC")
                        ),
                        "seller", List.of(182179)
                )
        );

        sendGarphiQLRequest(page, "offers", query, variables);
    }
}

/*
{"operationName":"sellerConfigs","variables":{"filters":{"platformType":"ROMANIA","configCodes":["FLOW_GLA_CAMPAIGN_OFFERS"]}},"query":"query sellerConfigs($filters: SellerConfigFilterInput!) {\n  sellerConfigs(filters: $filters) {\n    items {\n      name\n      value\n    }\n  }\n}"}

 */
