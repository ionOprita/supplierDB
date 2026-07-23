package ro.sellfluence.emagapi;

import java.math.BigDecimal;

public record AdsKeyword(
        Integer id,
        BigDecimal bid,
        String status,
        String keyword,
        String matchType,
        String inheritedStatus,
        BigDecimal inheritedBid,
        AdsAdset adset,
        AdsPerformanceSummary summary
) {
}
