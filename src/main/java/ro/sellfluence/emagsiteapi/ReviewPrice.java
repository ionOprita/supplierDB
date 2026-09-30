package ro.sellfluence.emagsiteapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record ReviewPrice(
        BigDecimal current,
        Boolean isMin,
        Boolean isMax,
        BigDecimal legal,
        String prefix,
        String suffix,
        Boolean isVisible,
        Discount discount,
        Currency currency,
        BigDecimal net,
        RecommendedRetailPrice recommendedRetailPrice,
        @JsonProperty("lowest_price_30_days")
        LowestPrice30Days lowestPrice30Days,
        BigDecimal initial
) {
    public record Discount(
            String type,
            BigDecimal absolute,
            BigDecimal percent,
            Boolean isSpecial,
            Boolean isVisible,
            Boolean isRestrictedFromView,
            Boolean isMax,
            String label,
            Boolean labeledAsDiscount
    ) {
    }

    public record Currency(Long id, Name name) {
        public record Name(
                @JsonProperty("default") String defaultName,
                String display
        ) {
        }
    }

    public record RecommendedRetailPrice(
            BigDecimal amount,
            Boolean isVisible,
            String label,
            String tooltip
    ) {
    }

    public record LowestPrice30Days(
            BigDecimal amount,
            Boolean isVisible,
            String tooltip,
            String info
    ) {
    }
}
