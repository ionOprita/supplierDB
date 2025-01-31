package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;

public record Voucher (
    Integer voucher_id,
    String modified,
    String created,
    Integer status,
    BigDecimal sale_price_vat,
    BigDecimal sale_price,
    String voucher_name,
    BigDecimal vat,
    String issue_date,
    String id
){
    public Voucher {
        sale_price_vat = UsefulMethods.round(sale_price_vat);
        sale_price = UsefulMethods.round(sale_price);
        vat = UsefulMethods.round(vat);
    }
}
