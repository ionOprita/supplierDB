package ro.sellfluence.emagdashboard;

import java.math.BigDecimal;

public record AdsAdset(
        Integer id,
        String name,
        String targeting,
        BigDecimal bid,
        String status,
        String inheritedStatus,
        AdsRecommendedBid recommendedBid,
        AdsPerformanceSummary summary
) {
}
