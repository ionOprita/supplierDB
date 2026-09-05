package ro.sellfluence.emagapi;

import java.util.List;

public record AdsCampaignPhrasesData(
        AdsPerformanceSummary summary,
        AdsCampaign campaign,
        List<AdsAdset> adsets,
        List<AdsSearchPhrase> searchPhrases
) {
}
