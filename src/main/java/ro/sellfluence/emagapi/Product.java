package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

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
    public Product {
        if (product_voucher_split == null) {
            product_voucher_split = new VoucherSplit[0];
        }
        if (details == null) {
            details = new String[0];
        }
        if (recycle_warranties == null) {
            recycle_warranties = new String[0];
        }
        if (attachments == null) {
            attachments = new Attachment[0];
        }
        sale_price = UsefulMethods.round(sale_price);
        original_price = UsefulMethods.round(original_price);
    }
}