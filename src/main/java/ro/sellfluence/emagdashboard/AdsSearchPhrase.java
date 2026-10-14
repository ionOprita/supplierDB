package ro.sellfluence.emagdashboard;

public record AdsSearchPhrase(
        Integer adsetId,
        String searchPhrase,
        Boolean isAggregated,
        AdsPerformanceSummary summary
) {
}
