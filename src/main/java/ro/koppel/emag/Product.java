package ro.koppel.emag;

import java.math.BigDecimal;
import java.util.Arrays;

public class Product {
    public int id;
    public Integer product_id;
    public VoucherSplit[] product_voucher_split;

    public int status;
    public String part_number;
    public String part_number_key;
    public String created;
    public String modified;
    public String currency;
    public int quantity;
    public BigDecimal sale_price;

    public String[] details;

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
