package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public record VoucherSplit(
        Integer voucher_id,
        BigDecimal value,
        BigDecimal vat_value,
        String vat,
        String offered_by,
        String voucher_name
) {
    public VoucherSplit {
        value = UsefulMethods.round(value);
        vat_value = UsefulMethods.round(vat_value);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VoucherSplit that)) return false;
        return Objects.equals(vat, that.vat)
               && Objects.equals(value, that.value)
               && Objects.equals(offered_by, that.offered_by)
               && voucher_id.equals(that.voucher_id)
               && Objects.equals(vat_value, that.vat_value)
               && Objects.equals(voucher_name, that.voucher_name);
    }

    public static boolean vslEquals(List<VoucherSplit> l1, List<VoucherSplit> l2) {
        if (l1==null&&l2==null) return true;
        if (l1==null||l2==null) return false;
        if (l1.size()!=l2.size()) return false;
        Iterator<?> i1 = l1.iterator();
        Iterator<?> i2 = l2.iterator();
        while (i1.hasNext()) {
            if (!Objects.equals(i1.next(), i2.next())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = voucher_id.hashCode();
        result = 31 * result + Objects.hashCode(value);
        result = 31 * result + Objects.hashCode(vat_value);
        result = 31 * result + Objects.hashCode(vat);
        result = 31 * result + Objects.hashCode(offered_by);
        result = 31 * result + Objects.hashCode(voucher_name);
        return result;
    }
}