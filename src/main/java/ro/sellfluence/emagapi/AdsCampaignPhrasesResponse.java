package ro.sellfluence.emagapi;

public record AdsCampaignPhrasesResponse(
        AdsPaginationMeta meta,
        AdsCampaignPhrasesData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
