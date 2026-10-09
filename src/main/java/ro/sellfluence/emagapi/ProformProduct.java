package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// TODO: Does this need to be stored in the database?
public record ProformProduct(
        Long id,
        Long vendor_proform_id,
        Long vendor_order_product_id,
        Long vendor_product_id,
        String vendor_product_ext_name,
        Integer vendor_order_product_quantity,
        BigDecimal vendor_order_product_sale_price,
        BigDecimal vendor_order_product_vat_rate,
        LocalDateTime created,
        LocalDateTime modified
) {
}
