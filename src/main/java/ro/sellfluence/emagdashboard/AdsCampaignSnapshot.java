package ro.sellfluence.emagdashboard;

import java.time.LocalDate;
import java.util.List;

public record AdsCampaignSnapshot(
        LocalDate reportDate,
        AdsCampaign campaign,
        List<AdSet> adSets
) {
    public AdsCampaignSnapshot(AdsCampaign campaign, List<AdSet> adSets) {
        this(null, campaign, adSets);
    }
}
