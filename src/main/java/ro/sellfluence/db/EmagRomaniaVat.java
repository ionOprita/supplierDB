package ro.sellfluence.db;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Converts eMAG Romania offer prices using verified platform VAT identifiers. */
final class EmagRomaniaVat {
    // Verified by authenticated POST https://marketplace-api.emag.ro/api-3/vat/read on 2026-11-04.
    // These are Romania platform identifiers, not percentages; unknown IDs have no default rate.
    private static final Map<Integer, BigDecimal> RATES = Map.of(
            5, BigDecimal.ZERO,
            9, new BigDecimal("0.21"),
            10, new BigDecimal("0.11")
    );

    private EmagRomaniaVat() {
    }

    static BigDecimal grossPrice(BigDecimal netPrice, Integer vatId) {
        if (netPrice == null || vatId == null) return null;
        var rate = RATES.get(vatId);
        if (rate == null) return null;
        return netPrice.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_EVEN);
    }
}
