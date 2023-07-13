package ro.koppel.emag;

import java.math.BigDecimal;

public class VoucherSplit {
    public Integer voucher_id;
    public BigDecimal value;
    public BigDecimal vat_value;

    @Override
    public String toString() {
        return "ShippingTaxVoucher{" + "voucher_id=" + voucher_id + ", value=" + value + ", vat_value=" + vat_value + '}';
    }
}
