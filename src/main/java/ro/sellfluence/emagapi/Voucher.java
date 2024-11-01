package ro.sellfluence.emagapi;

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
    //TODO: Additional fields
    String id
){}
