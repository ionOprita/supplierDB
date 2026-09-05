package ro.sellfluence.emagapi;

import java.util.List;

public record AdsCampaignsData(
        AdsPerformanceSummary summary,
        List<AdsCampaign> campaigns
) {
}
