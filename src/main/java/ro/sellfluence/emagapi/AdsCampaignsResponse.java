package ro.sellfluence.emagapi;

public record AdsCampaignsResponse(
        AdsPaginationMeta meta,
        AdsCampaignsData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
