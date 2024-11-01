package ro.sellfluence.emagapi;

import java.math.BigDecimal;

public record VoucherSplit (
   Integer voucher_id,
   BigDecimal value,
   BigDecimal vat_value,
   //TODO: Added field
   String vat,
   String offered_by
){}
