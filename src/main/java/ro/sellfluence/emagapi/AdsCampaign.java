package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdsCampaign(
        Integer id,
        String name,
        Integer advertiserId,
        BigDecimal dailyBudget,
        BigDecimal effectiveDailyBudget,
        BigDecimal remainingDailyBudget,
        String status,
        String inheritedStatus,
        String targeting,
        LocalDateTime dateStart,
        LocalDateTime dateEnd,
        AdsPerformanceSummary summary,
        String advertiserName,
        List<AdsAdset> adSets
) {
}
