package ro.sellfluence.emagdashboard;

import java.util.List;

public record AdsCampaignKeywordsData(
        AdsPerformanceSummary summary,
        List<AdsKeyword> keywords
) {
}
