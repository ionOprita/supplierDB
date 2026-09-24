package ro.sellfluence.emagdashboard;

import java.math.BigDecimal;

public record Measurements(
        BigDecimal weight,
        BigDecimal height,
        BigDecimal length,
        BigDecimal width
) {
}
