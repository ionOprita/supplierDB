package ro.sellfluence.emagapi;

public record AdsCampaignTargetedProductsResponse(
        AdsPaginationMeta meta,
        AdsCampaignTargetedProductsData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
