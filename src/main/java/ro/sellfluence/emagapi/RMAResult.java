package ro.sellfluence.emagapi;

import java.time.LocalDateTime;

public record RMAResult(
        int emag_id,
        int order_id,
        int type,
        LocalDateTime date,
        ReturnedProduct[] products,
        int request_status,
        int return_type,
        int return_reason,
        String observations
) {
}
