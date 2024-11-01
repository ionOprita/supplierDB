package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Product(
        int id,
        Integer product_id,
        Integer mkt_id,
        String name,
        VoucherSplit[] product_voucher_split,
        int status,
        String ext_part_number,
        String part_number,
        String part_number_key,
        String currency,
        String vat,
        int retained_amount,
        int quantity,
        int initial_qty,
        int storno_qty,
        int reversible_vat_charging,
        BigDecimal sale_price,
        BigDecimal original_price,
        LocalDateTime created,
        LocalDateTime modified,
        String[] details,
        String[] recycle_warranties,
        Attachment[] attachments
) {
}