package ro.sellfluence.emagapi;

import java.time.LocalDate;
import java.util.List;

public record Campaign(
        LocalDate reportStartDate,
        LocalDate reportEndDate,
        AdsCampaign campaign,
        List<AdSet> adSets
) {
    public Campaign(AdsCampaign campaign, List<AdSet> adSets) {
        this(null, null, campaign, adSets);
    }
}
