package ro.sellfluence.emagdashboard;

public record SupplyRecommendationData(
        Integer recommendedQtyReplenish,
        Integer replenishmentPeriod,
        Integer daysUntilOos,
        Integer estimatedDaysUntilOos,
        Integer daysInStockL35,
        Integer unitsSoldL35,
        Integer unitsForecastN35,
        Integer unitsInTransit,
        Integer stockInCurrentDay
) {
}
