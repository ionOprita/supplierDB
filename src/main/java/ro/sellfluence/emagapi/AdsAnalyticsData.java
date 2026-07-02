package ro.sellfluence.emagapi;

import java.util.List;

public record AdsAnalyticsData(
        String groupedBy,
        AdsPerformanceSummary summary,
        List<AdsStatistic> statistics
) {
}
