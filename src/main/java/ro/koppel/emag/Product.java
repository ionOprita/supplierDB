package ro.koppel.emag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

public class Product {
    public int id;
    public Integer product_id;
    public Integer mkt_id;
    public String name;
    public VoucherSplit[] product_voucher_split;
    public int status;
    public String ext_part_number;
    public String part_number;
    public String part_number_key;
    public String currency;
    public String vat;
    public int retained_amount;
    public int quantity;
    public int initial_qty;
    public int storno_qty;
    public int reversible_vat_charging;
    public BigDecimal sale_price;
    public BigDecimal original_price;
    public LocalDateTime created;
    public LocalDateTime modified;
    public String[] details;
    public String[] recycle_warranties;
    public Attachment[] attachments;

    @Override
    public String toString() {
        return "Product{" +
                "id=" + id +
                ", product_id=" + product_id +
                ", product_voucher_split=" + Arrays.toString(product_voucher_split) +
                ", status=" + status +
                ", part_number='" + part_number + '\'' +
                ", part_number_key='" + part_number_key + '\'' +
                ", created='" + created + '\'' +
                ", modified='" + modified + '\'' +
                ", currency='" + currency + '\'' +
                ", quantity=" + quantity +
                ", sale_price=" + sale_price +
                ", details=" + Arrays.toString(details) +
                '}';
    }
}
