package ro.sellfluence.db;

public record ProductInfo(
        String pnk,
        String productCode,
        String name,
        boolean continueToSell,
        boolean retracted,
        String category,
        String messageKeyword
) {
}