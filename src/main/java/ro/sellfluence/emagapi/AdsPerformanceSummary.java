package ro.sellfluence.emagapi;

import java.math.BigDecimal;

public record AdsPerformanceSummary(
        BigDecimal averageCostOfSale,
        Integer clicks,
        BigDecimal ctr,
        BigDecimal effectiveCpc,
        Integer impressions,
        BigDecimal sales,
        Integer salesCount,
        Integer soldUnits,
        BigDecimal spent,
        Integer activeOfferCount,
        Integer offerCount,
        Integer pausedOfferCount,
        Integer adsetCount,
        Integer keywordCount,
        Integer productTargetCount,
        BigDecimal conversionRate,
        BigDecimal returnOnAdvertisingSpend
) {
}
