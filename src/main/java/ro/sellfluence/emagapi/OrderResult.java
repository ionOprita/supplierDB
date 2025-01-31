package ro.sellfluence.emagapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

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
        //Integer cancellation_reason,
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
        int weekend_delivery,
        LocalDateTime created,
        LocalDateTime modified,
        String[] enforced_vendor_courier_accounts
) {

    /**
     * Return the delivery mode as either 'curier' or 'easybox'
     *
     * @return converted string.
     */
    public String getDeliveryMode() {
        return switch (delivery_mode) {
            case "courier", "Livrare 6H" -> "curier";
            case "pickup" -> "easybox";
            default ->
                    throw new IllegalArgumentException("Unknown delivery_mode = " + delivery_mode + "in order " + id);
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

    /**
     * Helper method to see, what kind of differences there are between an existing order and
     * a newly read order.
     * The output is written in a way that it assumes the other instance to be the newer one.
     * It will log some stuff and return a boolean, which gives a hint whether the orders are equal or not.
     *
     * @param other Another OrderResult.
     * @return true if differences are detected. It can return false even if there are differences, but they are in parts not examined.
     */
    public boolean findDifferences(OrderResult other) {
        boolean hasDifference = false;

        if (status != other.status) {
            System.out.printf("Status changed from %s to %s%n", status, other.status);
            hasDifference = true;
        }
        if (is_storno != other.is_storno) {
            System.out.printf("Storno changed from %b to %b%n", is_storno, other.is_storno);
            hasDifference = true;
        }
        if (!Objects.equals(is_complete, other.is_complete)) {
            System.out.printf("Completion status changed from %d to %d%n", is_complete, other.is_complete);
            hasDifference = true;
        }
        if (!Objects.equals(type, other.type)) {
            System.out.printf("Type changed from %s to %s%n", type, other.type);
            hasDifference = true;
        }
        if (!Objects.equals(payment_mode, other.payment_mode)) {
            System.out.printf("Payment mode changed from %s to %s%n", payment_mode, other.payment_mode);
            hasDifference = true;
        }
        if (payment_mode_id != other.payment_mode_id) {
            System.out.printf("Payment mode ID changed from %s to %s%n", payment_mode_id, other.payment_mode_id);
            hasDifference = true;
        }
        if (!Objects.equals(delivery_payment_mode, other.delivery_payment_mode)) {
            System.out.printf("Delivery payment mode changed from %s to %s%n", delivery_payment_mode, other.delivery_payment_mode);
            hasDifference = true;
        }
        if (!Objects.equals(delivery_mode, other.delivery_mode)) {
            System.out.printf("Delivery mode changed from %s to %s%n", delivery_mode, other.delivery_mode);
            hasDifference = true;
        }
        if (!Objects.equals(observation, other.observation)) {
            System.out.printf("Observation changed from %s to %s%n", observation, other.observation);
            hasDifference = true;
        }
        if (!Objects.equals(details, other.details)) {
            System.out.printf("Locker details changed from %s to %s%n", details, other.details);
            hasDifference = true;
        }
        if (!Objects.equals(date, other.date)) {
            System.out.printf("Modification date changed from %s to %s%n", date, other.date);
            hasDifference = true;
        }
        if (!Objects.equals(created, other.created)) {
            System.out.printf("Modification date changed from %s to %s%n", created, other.created);
            hasDifference = true;
        }
        if (!Objects.equals(modified, other.modified)) {
            System.out.printf("Modification date changed from %s to %s%n", modified, other.modified);
            hasDifference = true;
        }
        if (!Objects.equals(payment_status, other.payment_status)) {
            System.out.printf("Payment status changed from %s to %s%n", payment_status, other.payment_status);
            hasDifference = true;
        }
        if (!Objects.equals(cashed_co, other.cashed_co)) {
            System.out.printf("Cashed CO changed from %s to %s%n", cashed_co, other.cashed_co);
            hasDifference = true;
        }
        if (!Objects.equals(cashed_cod, other.cashed_cod)) {
            System.out.printf("Cashed COD changed from %s to %s%n", cashed_cod, other.cashed_cod);
            hasDifference = true;
        }
        if (!Objects.equals(shipping_tax, other.shipping_tax)) {
            System.out.printf("Shipping tax changed from %s to %s%n", shipping_tax, other.shipping_tax);
            hasDifference = true;
        }
        if (!Arrays.equals(shipping_tax_voucher_split, other.shipping_tax_voucher_split)) {
            System.out.println("Shipping tax voucher split changed.");
            hasDifference = true;
        }
        if (!Arrays.equals(products, other.products)) {
            System.out.println("Products changed.");
            hasDifference = true;
        }
        if (!Arrays.equals(attachments, other.attachments)) {
            System.out.println("Attachments changed.");
            hasDifference = true;
        }
        if (!Arrays.equals(vouchers, other.vouchers)) {
            System.out.println("Vouchers changed.");
            hasDifference = true;
        }
        if (!Objects.equals(reason_cancellation, other.reason_cancellation)) {
            System.out.printf("Cancellation reason changed from %s to %s%n", reason_cancellation, other.reason_cancellation);
            hasDifference = true;
        }
        if (!Objects.equals(refunded_amount, other.refunded_amount)) {
            System.out.printf("The refunded amount changed from %s to %s%n", refunded_amount, other.refunded_amount);
            hasDifference = true;
        }
        if (!Objects.equals(refund_status, other.refund_status)) {
            System.out.printf("Refund status changed from %s to %s%n", refund_status, other.refund_status);
            hasDifference = true;
        }
        if (!Objects.equals(maximum_date_for_shipment, other.maximum_date_for_shipment)) {
            System.out.printf("The maximum date for shipment changed from %s to %s%n", maximum_date_for_shipment, other.maximum_date_for_shipment);
            hasDifference = true;
        }
        if (!Objects.equals(finalization_date, other.finalization_date)) {
            System.out.printf("Finalization date changed from %s to %s%n", finalization_date, other.finalization_date);
            hasDifference = true;
        }
        if (!Objects.equals(parent_id, other.parent_id)) {
            System.out.printf("Parent ID changed from %s to %s%n", parent_id, other.parent_id);
            hasDifference = true;
        }
        if (!Objects.equals(detailed_payment_method, other.detailed_payment_method)) {
            System.out.printf("Detailed payment method changed from %s to %s%n", detailed_payment_method, other.detailed_payment_method);
            hasDifference = true;
        }
        if (!Arrays.equals(proforms, other.proforms)) {
            System.out.println("Proforms changed.");
            hasDifference = true;
        }
        if (!Objects.equals(cancellation_request, other.cancellation_request)) {
            System.out.printf("Cancellation request changed from %s to %s%n", cancellation_request, other.cancellation_request);
            hasDifference = true;
        }
        if (has_editable_products != other.has_editable_products) {
            System.out.printf("Value of has_editable_products changed from %s to %s.%n", has_editable_products, other.has_editable_products);
            hasDifference = true;
        }
        if (!Objects.equals(late_shipment, other.late_shipment)) {
            System.out.printf("Late shipment changed from %s to %s%n", late_shipment, other.late_shipment);
            hasDifference = true;
        }
        if (!Arrays.equals(flags, other.flags)) {
            System.out.println("Flags changed.");
            hasDifference = true;
        }
        if (emag_club != other.emag_club) {
            System.out.printf("Emag club changed from %s to %s%n", emag_club, other.emag_club);
            hasDifference = true;
        }
        if (weekend_delivery != other.weekend_delivery) {
            System.out.printf("Weekend delivery changed from %s to %s%n", weekend_delivery, other.weekend_delivery);
            hasDifference = true;
        }
        if (!Arrays.equals(enforced_vendor_courier_accounts, other.enforced_vendor_courier_accounts)) {
            System.out.println("Enforced vendor courier accounts changed.");
            hasDifference = true;
        }


        return hasDifference;
    }
}