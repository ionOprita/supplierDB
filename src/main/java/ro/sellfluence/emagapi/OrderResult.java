package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResult(
        String vendor_name,
        String id,
        int status,
        Integer is_complete,
        Integer type,
        String payment_mode,
        int payment_mode_id,
        String delivery_payment_mode,
        String delivery_mode,
        String observation,
        LockerDetails details,
        LocalDateTime date,
        Integer payment_status,
        BigDecimal cashed_co,
        BigDecimal cashed_cod,
        BigDecimal shipping_tax,
        VoucherSplit[] shipping_tax_voucher_split,
        Customer customer,
        Product[] products,
        Attachment[] attachments,
        Voucher[] vouchers,
        boolean is_storno,
        Integer cancellation_reason,
        BigDecimal refunded_amount,
        String refund_status,
        LocalDateTime maximum_date_for_shipment,
        LocalDateTime finalization_date,
        String parent_id,
        String detailed_payment_method,
        String[] proforms,
        String cancellation_request,
        int has_editable_products,
        CancellationReason reason_cancellation,
        Integer late_shipment,
        Flag[] flags,
        int emag_club,
        int weekend_delivery
) {

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
        return Integer.valueOf(1).equals(customer.legal_entity());
    }
}