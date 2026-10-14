package ro.sellfluence.emagdashboard;

import java.util.List;

public record AdsCampaignPhrasesData(
        AdsPerformanceSummary summary,
        AdsCampaign campaign,
        List<AdsAdset> adsets,
        List<AdsSearchPhrase> searchPhrases
) {
}
