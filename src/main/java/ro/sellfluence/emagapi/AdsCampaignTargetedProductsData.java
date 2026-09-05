package ro.sellfluence.emagapi;

import java.util.List;

public record AdsCampaignTargetedProductsData(
        AdsPerformanceSummary summary,
        AdsCampaign campaign,
        List<AdsAdset> adsets,
        List<AdsTargetedProduct> docs
) {
}
