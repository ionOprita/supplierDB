package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;

public record VoucherSplit (
   Integer voucher_id,
   BigDecimal value,
   BigDecimal vat_value,
   String vat,
   String offered_by
){
    public VoucherSplit {
        value = UsefulMethods.round(value);
        vat_value = UsefulMethods.round(vat_value);
    }
}
