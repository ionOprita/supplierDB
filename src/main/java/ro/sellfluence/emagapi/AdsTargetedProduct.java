package ro.sellfluence.emagapi;

import java.math.BigDecimal;

public record AdsTargetedProduct(
        Integer docId,
        String productName,
        BigDecimal price,
        BigDecimal rating,
        String pnk,
        String categoryName,
        Integer categoryId,
        String brandName,
        Integer brandId,
        Integer adsetId,
        Integer clicks,
        Integer impressions,
        BigDecimal ctr,
        BigDecimal effectiveCpc,
        BigDecimal spent,
        BigDecimal averageCostOfSale,
        BigDecimal sales,
        Integer soldUnits,
        Integer salesCount,
        String imageUrl,
        BigDecimal returnOnAdvertisingSpend,
        BigDecimal conversionRate
) {
}
