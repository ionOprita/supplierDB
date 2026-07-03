package ro.sellfluence.emagapi;

import java.math.BigDecimal;

public record AdsAdset(
        Integer id,
        String name,
        String targeting,
        BigDecimal bid,
        String status
) {
}
