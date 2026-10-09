package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// TODO: Does this need to be stored in the database?
public record Proform(
        Long id,
        Long vendor_id,
        String vendor_name,
        String vendor_bank,
        String vendor_iban,
        String customer_name,
        Long vendor_order_id,
        List<ProformProduct> products,
        Long proforma_number,
        LocalDateTime created,
        LocalDateTime date_expire,
        BigDecimal net_value,
        BigDecimal gross_value,
        Integer status,
        Integer is_payed,
        LocalDateTime modified,
        Long mkt_order_id
) {
    public Proform {
        if (products == null) {
            products = new ArrayList<>();
        }
    }
}
