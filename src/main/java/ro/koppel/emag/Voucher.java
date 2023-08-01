package ro.koppel.emag;

import java.math.BigDecimal;

public class Voucher {
    public Integer voucher_id;
    public String modified;
    public String created;

    public Integer status;
    public BigDecimal sale_price_vat;
    public BigDecimal sale_price;
    public String voucher_name;
    public BigDecimal vat;
    public String issue_date;

    @Override
    public String toString() {
        return "Voucher{" +
                "voucher_id=" + voucher_id +
                ", modified='" + modified + '\'' +
                ", created='" + created + '\'' +
                ", status=" + status +
                ", sale_price_vat=" + sale_price_vat +
                ", sale_price=" + sale_price +
                ", voucher_name='" + voucher_name + '\'' +
                ", vat=" + vat +
                ", issue_date='" + issue_date + '\'' +
                '}';
    }
}
