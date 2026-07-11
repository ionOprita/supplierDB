package ro.sellfluence.emagapi;

import java.util.List;

public record Campaign(
        AdsCampaign campaign,
        List<AdSet> adSets
) {}
