package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static ro.sellfluence.emagapi.LockerDetails.ldlEquals;
import static ro.sellfluence.emagapi.VoucherSplit.vslEquals;
import static ro.sellfluence.support.UsefulMethods.isEmpty;

public record Product(
        int id,
        Integer product_id,
        Integer mkt_id,
        String name,
        List<VoucherSplit> product_voucher_split,
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
        List<String> details,
        List<String> recycle_warranties,
        List<Attachment> attachments,
        String serial_numbers
) {
    public Product {
        if (product_voucher_split == null) {
            product_voucher_split = new ArrayList<>();
        }
        if (isEmpty(details)) {
            details = new ArrayList<>();
        }
        if (isEmpty(recycle_warranties)) {
            recycle_warranties = new ArrayList<>();
        }
        if (attachments == null) {
            attachments = new ArrayList<>();
        }
        sale_price = UsefulMethods.round(sale_price);
        original_price = UsefulMethods.round(original_price);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Product product)) return false;
        return id == product.id
               && status == product.status
               && quantity == product.quantity
               && storno_qty == product.storno_qty
               && initial_qty == product.initial_qty
               && retained_amount == product.retained_amount
               && reversible_vat_charging == product.reversible_vat_charging
               && Objects.equals(vat, product.vat)
               && Objects.equals(name, product.name)
               && Objects.equals(mkt_id, product.mkt_id)
               && Objects.equals(currency, product.currency)
               && product_id.equals(product.product_id)
               && Objects.equals(part_number, product.part_number)
               && slEquals(details,product.details)
               && Objects.equals(sale_price, product.sale_price)
               && Objects.equals(created, product.created)
               && Objects.equals(ext_part_number, product.ext_part_number)
               && Objects.equals(part_number_key, product.part_number_key)
               && Objects.equals(modified, product.modified)
               && Objects.equals(original_price, product.original_price)
               && Objects.equals(attachments, product.attachments)
               && slEquals(recycle_warranties, product.recycle_warranties)
               && vslEquals(product_voucher_split, product.product_voucher_split)
               && Objects.equals(serial_numbers, product.serial_numbers);
    }

    public static boolean slEquals(List<String> l1, List<String> l2) {
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

    public static boolean plEquals(List<Product> l1, List<Product> l2) {
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
        int result = id;
        result = 31 * result + product_id.hashCode();
        result = 31 * result + Objects.hashCode(mkt_id);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(product_voucher_split);
        result = 31 * result + status;
        result = 31 * result + Objects.hashCode(ext_part_number);
        result = 31 * result + Objects.hashCode(part_number);
        result = 31 * result + Objects.hashCode(part_number_key);
        result = 31 * result + Objects.hashCode(currency);
        result = 31 * result + Objects.hashCode(vat);
        result = 31 * result + retained_amount;
        result = 31 * result + quantity;
        result = 31 * result + initial_qty;
        result = 31 * result + storno_qty;
        result = 31 * result + reversible_vat_charging;
        result = 31 * result + Objects.hashCode(sale_price);
        result = 31 * result + Objects.hashCode(original_price);
        result = 31 * result + Objects.hashCode(created);
        result = 31 * result + Objects.hashCode(modified);
        result = 31 * result + Objects.hashCode(details);
        result = 31 * result + Objects.hashCode(recycle_warranties);
        result = 31 * result + Objects.hashCode(attachments);
        result = 31 * result + Objects.hashCode(serial_numbers);
        return result;
    }
}