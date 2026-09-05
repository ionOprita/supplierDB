package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdsStatistic(
        LocalDateTime date,
        BigDecimal averageCostOfSale,
        Integer clicks,
        BigDecimal ctr,
        BigDecimal effectiveCpc,
        Integer impressions,
        BigDecimal sales,
        Integer salesCount,
        Integer soldUnits,
        BigDecimal spent,
        BigDecimal conversionRate,
        BigDecimal returnOnAdvertisingSpend
) {
}
