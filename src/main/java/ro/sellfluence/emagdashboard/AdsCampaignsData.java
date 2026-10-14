package ro.sellfluence.emagdashboard;

import java.util.List;

public record AdsCampaignsData(
        AdsPerformanceSummary summary,
        List<AdsCampaign> campaigns
) {
}
