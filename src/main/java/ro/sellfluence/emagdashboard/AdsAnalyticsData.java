package ro.sellfluence.emagdashboard;

import java.util.List;

public record AdsAnalyticsData(
        String groupedBy,
        AdsPerformanceSummary summary,
        List<AdsStatistic> statistics
) {
}
