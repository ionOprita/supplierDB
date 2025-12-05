package ro.sellfluence.emagapi;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.Boolean.TRUE;
import static ro.sellfluence.support.UsefulMethods.bdEquals;
import static ro.sellfluence.support.UsefulMethods.isEmpty;
import static ro.sellfluence.support.UsefulMethods.round;

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
        Boolean is_storno,
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
        List<String> enforced_vendor_courier_accounts,
        String currency
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
        if (isEmpty(proforms)) {
            proforms = new ArrayList<>();
        }
        if (flags == null) {
            flags = new ArrayList<>();
        }
        if (isEmpty(enforced_vendor_courier_accounts)) {
            enforced_vendor_courier_accounts = new ArrayList<>();
        }
        if (reason_cancellation != null && reason_cancellation.id() == null) {
            reason_cancellation = null;
        }
        cashed_co = round(cashed_co);
        cashed_cod = round(cashed_cod);
        shipping_tax = round(shipping_tax);
        refunded_amount = round(refunded_amount);
    }

    public static @NonNull String statusToString(final Integer status) {
        if (status == null) return "Unknown";
        return switch (status) {
            case 0 -> "Canceled";
            case 1 -> "New";
            case 2 -> "In progress";
            case 3 -> "Prepared";
            case 4 -> "Finalized";
            case 5 -> "Returned";
            default -> throw new IllegalStateException("Unexpected value: " + status);
        };
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
     * Helper method to see what kind of differences there are between an existing order and
     * a newly read order.
     * The output is written in a way that it assumes the other instance to be the newer one.
     * It will log some stuff and return a boolean, which gives a hint whether the orders are equal or not.
     *
     * @param other Another OrderResult.
     * @return false if no differences are detected otherwise true.
     */
    public boolean reportUnhandledDifferences(OrderResult other) {
        boolean hasDifference = false;
        if (TRUE.equals(is_storno) != TRUE.equals(other.is_storno)) {
            System.out.printf("%s:%s -> %s:%s Storno changed from %b to %b%n", vendor_name, id, other.vendor_name, other.id, is_storno, other.is_storno);
            hasDifference = true;
        }
        if (!Objects.equals(type, other.type)) {
            System.out.printf("%s:%s -> %s:%s Type changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, type, other.type);
            hasDifference = true;
        }
        if (!Objects.equals(observation, other.observation)) {
            System.out.printf("%s:%s -> %s:%s Observation changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, observation, other.observation);
            hasDifference = true;
        }
        if (!Objects.equals(date, other.date)) {
            ZoneId defaultZoneId = ZoneId.systemDefault();
            var dateChanged = false;
            if (date instanceof LocalDateTime a && other.date instanceof LocalDateTime b) {
                if (!a.atZone(defaultZoneId).toInstant().equals(b.atZone(defaultZoneId).toInstant())) {
                    dateChanged = true;
                }
            } else {
                dateChanged = true;
            }
            if (dateChanged) {
                System.out.printf("%s:%s -> %s:%s Date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, date, other.date);
                hasDifference = true;

            }
        }
        if (!Objects.equals(created, other.created)) {
            System.out.printf("%s:%s -> %s:%s Created date changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, created, other.created);
            hasDifference = true;
        }
        if (!bdEquals(shipping_tax, other.shipping_tax)) {
            System.out.printf("%s:%s -> %s:%s Shipping tax changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, shipping_tax, other.shipping_tax);
            hasDifference = true;
        }
        if (!Objects.equals(parent_id, other.parent_id)) {
            System.out.printf("%s:%s -> %s:%s Parent ID changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, parent_id, other.parent_id);
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