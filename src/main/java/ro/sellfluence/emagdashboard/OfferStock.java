package ro.sellfluence.emagdashboard;

import java.time.LocalDate;
import java.util.List;

public record OfferStock(
        String category,
        String statusType,
        String statusName,
        String inactivationReason,
        String hotnessType,
        String hotnessName,
        String rrpBadgeColor,
        String rrpGuideline,
        java.math.BigDecimal rrpValue,
        Integer vatId,
        Boolean startDate,
        List<String> propertyTypes,
        LocalDate placeOrderToSupplierUntil,
        String invalidationReasonLabel
) {
}
