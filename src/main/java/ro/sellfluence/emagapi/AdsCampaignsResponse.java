package ro.sellfluence.emagapi;

public record AdsCampaignsResponse(
        AdsPaginationMeta meta,
        AdsCampaignsData data
) {
}
