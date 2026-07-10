package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdsCampaignAdSetsData(
        AdsPerformanceSummary summary,
        List<AdsAdset> adsets,
        Integer id,
        String name,
        Integer advertiserId,
        LocalDateTime dateStart,
        LocalDateTime dateEnd,
        BigDecimal dailyBudget,
        BigDecimal remainingDailyBudget,
        String status,
        String inheritedStatus,
        String targeting
) {
}
