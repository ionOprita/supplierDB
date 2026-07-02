package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdsCampaign(
        Integer id,
        String name,
        Integer advertiserId,
        BigDecimal dailyBudget,
        BigDecimal remainingDailyBudget,
        String status,
        String inheritedStatus,
        String targeting,
        LocalDateTime dateStart,
        LocalDateTime dateEnd,
        AdsPerformanceSummary summary,
        String advertiserName
) {
}
