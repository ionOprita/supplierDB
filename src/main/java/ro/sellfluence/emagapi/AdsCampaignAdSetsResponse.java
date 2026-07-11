package ro.sellfluence.emagapi;

public record AdsCampaignAdSetsResponse(
        AdsPaginationMeta meta,
        AdsCampaignAdSetsData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
