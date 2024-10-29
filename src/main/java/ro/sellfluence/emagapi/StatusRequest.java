package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StatusRequest(
    BigDecimal amount,
    LocalDateTime created,
    String refund_type,
    String refund_status,
    String rma_id,
    LocalDateTime status_date
) {}
