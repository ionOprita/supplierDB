package ro.sellfluence.emagapi;

public record AdsCampaignKeywordsResponse(
        AdsPaginationMeta meta,
        AdsCampaignKeywordsData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
