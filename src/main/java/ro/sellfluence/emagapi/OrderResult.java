package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ro.sellfluence.emagapi.Product.plEquals;
import static ro.sellfluence.support.UsefulMethods.bdEquals;
import static ro.sellfluence.support.UsefulMethods.round;

//TODO: Normalize the order after reading from JSON or from DB.
// Null arrays become empty arrays
// Some arrays with a single empty String become empty arrays.

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
        List<VoucherSplit> shipping_tax_voucher_split,
        Customer customer,
        List<Product> products,
        List<Attachment> attachments,
        List<Voucher> vouchers,
        boolean is_storno,
        BigDecimal refunded_amount,
        String refund_status,
        LocalDateTime maximum_date_for_shipment,
        LocalDateTime finalization_date,
        String parent_id,
        String detailed_payment_method,
        List<String> proforms,
        String cancellation_request,
        int has_editable_products,
        CancellationReason reason_cancellation,
        Integer late_shipment,
        List<Flag> flags,
        int emag_club,
        int weekend_delivery,
        LocalDateTime created,
        LocalDateTime modified,
        List<String> enforced_vendor_courier_accounts
) {
    public OrderResult {
        // Normalize Lists and BigDecimals
        if (products == null) {
            products = new ArrayList<>();
        }
        if (attachments == null) {
            attachments = new ArrayList<>();
        }
        if (vouchers == null) {
            vouchers = new ArrayList<>();
        }
        if (proforms == null) {
            proforms = new ArrayList<>();
        }
        if (flags == null) {
            flags = new ArrayList<>();
        }
        if (enforced_vendor_courier_accounts == null) {
            enforced_vendor_courier_accounts = new ArrayList<>();
        }
        if (reason_cancellation!=null && reason_cancellation.id() == null) {
            reason_cancellation = null;
        }
        cashed_co = round(cashed_co);
        cashed_cod = round(cashed_cod);
        shipping_tax = round(shipping_tax);
        refunded_amount = round(refunded_amount);
    }

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
            System.out.printf("%s:%s -> %s:%s Status changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, status, other.status);
            hasDifference = true;
        }
        if (is_storno != other.is_storno) {
            System.out.printf("%s:%s -> %s:%s Storno changed from %b to %b%n", vendor_name, id, other.vendor_name, other.id, is_storno, other.is_storno);
            hasDifference = true;
        }
        if (!Objects.equals(is_complete, other.is_complete)) {
            System.out.printf("%s:%s -> %s:%s Completion status changed from %d to %d%n", vendor_name, id, other.vendor_name, other.id, is_complete, other.is_complete);
            hasDifference = true;
        }
        if (!Objects.equals(type, other.type)) {
            System.out.printf("%s:%s -> %s:%s Type changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, type, other.type);
            hasDifference = true;
        }
        if (!Objects.equals(payment_mode, other.payment_mode)) {
            System.out.printf("%s:%s -> %s:%s Payment mode changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, payment_mode, other.payment_mode);
            hasDifference = true;
        }
        if (payment_mode_id != other.payment_mode_id) {
            System.out.printf("%s:%s -> %s:%s Payment mode ID changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, payment_mode_id, other.payment_mode_id);
            hasDifference = true;
        }
        if (!Objects.equals(delivery_payment_mode, other.delivery_payment_mode)) {
            System.out.printf("%s:%s -> %s:%s Delivery payment mode changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, delivery_payment_mode, other.delivery_payment_mode);
            hasDifference = true;
        }
        if (!Objects.equals(delivery_mode, other.delivery_mode)) {
            System.out.printf("%s:%s -> %s:%s Delivery mode changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, delivery_mode, other.delivery_mode);
            hasDifference = true;
        }
        if (!Objects.equals(observation, other.observation)) {
            System.out.printf("%s:%s -> %s:%s Observation changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, observation, other.observation);
            hasDifference = true;
        }
        if (!Objects.equals(date, other.date)) {
            System.out.printf("%s:%s -> %s:%s Modification date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, date, other.date);
            hasDifference = true;
        }
        if (!Objects.equals(created, other.created)) {
            System.out.printf("%s:%s -> %s:%s Created date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, created, other.created);
            hasDifference = true;
        }
        if (!Objects.equals(modified, other.modified)) {
            System.out.printf("%s:%s -> %s:%s Modified date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, modified, other.modified);
            hasDifference = true;
        }
        if (!Objects.equals(payment_status, other.payment_status)) {
            System.out.printf("%s:%s -> %s:%s Payment status changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, payment_status, other.payment_status);
            hasDifference = true;
        }
        if (!bdEquals(cashed_co, other.cashed_co)) {
            System.out.printf("%s:%s -> %s:%s Cashed CO changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, cashed_co, other.cashed_co);
            hasDifference = true;
        }
        if (!bdEquals(cashed_cod, other.cashed_cod)) {
            System.out.printf("%s:%s -> %s:%s Cashed COD changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, cashed_cod, other.cashed_cod);
            hasDifference = true;
        }
        if (!bdEquals(shipping_tax, other.shipping_tax)) {
            System.out.printf("%s:%s -> %s:%s Shipping tax changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, shipping_tax, other.shipping_tax);
            hasDifference = true;
        }
        if (!shipping_tax_voucher_split.equals(other.shipping_tax_voucher_split)) {
            System.out.printf(
                    "%s:%s -> %s:%s Shipping tax voucher split changed.%n old: %s%n new: %s%n",
                    vendor_name, id, other.vendor_name, other.id, shipping_tax_voucher_split, other.shipping_tax_voucher_split
            );
            hasDifference = true;
        }
        if (!plEquals(products, other.products)) {
            System.out.printf(
                    "%s:%s -> %s:%s Products changed.%n old: %s%n new: %s%n",
                    vendor_name, id, other.vendor_name, other.id, products, other.products
            );
            hasDifference = true;
        }
        if (!Objects.equals(attachments, other.attachments)) {
            System.out.printf(
                    "%s:%s -> %s:%s Attachments changed.%n old: %s%n new: %s%n",
                    vendor_name, id, other.vendor_name, other.id, attachments, other.attachments
            );
            hasDifference = true;
        }
        if (!Objects.equals(vouchers, other.vouchers)) {
            System.out.printf(
                    "%s:%s -> %s:%s Vouchers changed.%n old: %s%n new: %s%n",
                    vendor_name, id, other.vendor_name, other.id, vouchers, other.vouchers
            );
            hasDifference = true;
        }
        if (!Objects.equals(reason_cancellation, other.reason_cancellation)) {
            System.out.printf("%s:%s -> %s:%s Cancellation reason changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, reason_cancellation, other.reason_cancellation);
            hasDifference = true;
        }
        if (!bdEquals(refunded_amount, other.refunded_amount)) {
            System.out.printf("%s:%s -> %s:%s The refunded amount changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, refunded_amount, other.refunded_amount);
            hasDifference = true;
        }
        if (!Objects.equals(refund_status, other.refund_status)) {
            System.out.printf("%s:%s -> %s:%s Refund status changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, refund_status, other.refund_status);
            hasDifference = true;
        }
        if (!Objects.equals(maximum_date_for_shipment, other.maximum_date_for_shipment)) {
            System.out.printf("%s:%s -> %s:%s The maximum date for shipment changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, maximum_date_for_shipment, other.maximum_date_for_shipment);
            hasDifference = true;
        }
        if (!Objects.equals(finalization_date, other.finalization_date)) {
            System.out.printf("%s:%s -> %s:%s Finalization date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, finalization_date, other.finalization_date);
            hasDifference = true;
        }
        if (!Objects.equals(parent_id, other.parent_id)) {
            System.out.printf("%s:%s -> %s:%s Parent ID changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, parent_id, other.parent_id);
            hasDifference = true;
        }
        if (!Objects.equals(detailed_payment_method, other.detailed_payment_method)) {
            System.out.printf("%s:%s -> %s:%s Detailed payment method changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, detailed_payment_method, other.detailed_payment_method);
            hasDifference = true;
        }
        if (!Objects.equals(proforms, other.proforms)) {
            System.out.printf("%s:%s -> %s:%s Proforms changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, proforms, other.proforms);
            hasDifference = true;
        }
        if (!Objects.equals(cancellation_request, other.cancellation_request)) {
            System.out.printf("%s:%s -> %s:%s Cancellation request changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, cancellation_request, other.cancellation_request);
            hasDifference = true;
        }
        if (has_editable_products != other.has_editable_products) {
            System.out.printf("%s:%s -> %s:%s Value of has_editable_products changed from %s to %s.%n", vendor_name, id, other.vendor_name, other.id, has_editable_products, other.has_editable_products);
            hasDifference = true;
        }
        if (!Objects.equals(late_shipment, other.late_shipment)) {
            System.out.printf("%s:%s -> %s:%s Late shipment changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, late_shipment, other.late_shipment);
            hasDifference = true;
        }
        if (!Objects.equals(flags, other.flags)) {
            System.out.printf("%s:%s -> %s:%s Flags changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, flags, other.flags);
            hasDifference = true;
        }
        if (emag_club != other.emag_club) {
            System.out.printf("%s:%s -> %s:%s Emag club changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, emag_club, other.emag_club);
            hasDifference = true;
        }
        if (weekend_delivery != other.weekend_delivery) {
            System.out.printf("%s:%s -> %s:%s Weekend delivery changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, weekend_delivery, other.weekend_delivery);
            hasDifference = true;
        }
        if (!Objects.equals(enforced_vendor_courier_accounts, other.enforced_vendor_courier_accounts)) {
            System.out.println("Enforced vendor courier accounts changed.");
            hasDifference = true;
        }


        return hasDifference;
    }
}