package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

public class OrderResult {
    public String vendor_name;
    public String id;
    public int status;
    public Integer is_complete;
    public Integer type;
    public String payment_mode;
    public int payment_mode_id;
    public String delivery_payment_mode;
    public String delivery_mode;
    public String observation;
    public LockerDetails details;
    public LocalDateTime date;
    public Integer payment_status;
    public BigDecimal cashed_co;
    public BigDecimal cashed_cod;
    public BigDecimal shipping_tax;
    public VoucherSplit[] shipping_tax_voucher_split;

    public Customer customer;

    public Product[] products;
    public Attachment[] attachments;
    public Voucher[] vouchers;
    public boolean is_storno;
    public Integer cancellation_reason;

    /**
     * Return the delivery mode as either 'curier' or 'easybox'
     *
     * @return converted string.
     */
    public String getDeliveryMode() {
        return switch (delivery_mode) {
            case "courier" -> "curier";
            case "pickup" -> "easybox";
            case "Livrare 6H" -> "curier";
            default -> throw new IllegalArgumentException("Unknown delivery_mode = " + delivery_mode + "in order " + id);
        };
    }

    /**
     * Report whether the customer is a company.
     *
     * @return true if it is a company.
     */
    public boolean isCompany() {
        return Integer.valueOf(1).equals(customer.legal_entity);
    }

    @Override
    public String toString() {
        return "OrderResult{" +
                "id='" + id + '\'' +
                ", status=" + status +
                ", is_complete=" + is_complete +
                ", type=" + type +
                ", payment_mode_id=" + payment_mode_id +
                ", delivery_payment_mode='" + delivery_payment_mode + '\'' +
                ", delivery_mode='" + delivery_mode + '\'' +
                ", details=" + details +
                ", date='" + date + '\'' +
                ", payment_status=" + payment_status +
                ", cashed_co=" + cashed_co +
                ", cashed_cod=" + cashed_cod +
                ", shipping_tax=" + shipping_tax +
                ", shipping_tax_voucher_split=" + Arrays.toString(shipping_tax_voucher_split) +
                ", customer=" + customer +
                ", products=" + Arrays.toString(products) +
                ", attachments=" + Arrays.toString(attachments) +
                ", vouchers=" + Arrays.toString(vouchers) +
                ", is_storno=" + is_storno +
                ", cancellation_reason=" + cancellation_reason +
                '}';
    }
}
