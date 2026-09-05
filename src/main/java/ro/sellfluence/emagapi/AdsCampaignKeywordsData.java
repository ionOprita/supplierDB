package ro.sellfluence.emagapi;

import java.util.List;

public record AdsCampaignKeywordsData(
        AdsPerformanceSummary summary,
        List<AdsKeyword> keywords
) {
}
