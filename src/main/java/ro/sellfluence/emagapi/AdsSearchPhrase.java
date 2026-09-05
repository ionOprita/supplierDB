package ro.sellfluence.emagapi;

public record AdsSearchPhrase(
        Integer adsetId,
        String searchPhrase,
        Boolean isAggregated,
        AdsPerformanceSummary summary
) {
}
