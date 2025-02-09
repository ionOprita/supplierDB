package ro.sellfluence.emagapi;

import ro.sellfluence.support.UsefulMethods;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static ro.sellfluence.emagapi.Product.plEquals;
import static ro.sellfluence.support.UsefulMethods.bdEquals;
import static ro.sellfluence.support.UsefulMethods.isEmpty;
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

/*
    public OrderResult withStatus(int newStatus) {
        return new OrderResult(
                vendor_name, id, newStatus, is_complete, type, payment_mode, payment_mode_id, delivery_payment_mode,
                delivery_mode, observation, details, date, payment_status, cashed_co, cashed_cod, shipping_tax,
                shipping_tax_voucher_split, customer, products, attachments, vouchers, is_storno, refunded_amount,
                refund_status, maximum_date_for_shipment, finalization_date, parent_id, detailed_payment_method,
                proforms, cancellation_request, has_editable_products, reason_cancellation, late_shipment, flags,
                emag_club, weekend_delivery, created, modified, enforced_vendor_courier_accounts
        );
    }

    public OrderResult withIsComplete(Integer newIsComplete) {
        return new OrderResult(
                vendor_name, id, status, newIsComplete, type, payment_mode, payment_mode_id, delivery_payment_mode,
                delivery_mode, observation, details, date, payment_status, cashed_co, cashed_cod, shipping_tax,
                shipping_tax_voucher_split, customer, products, attachments, vouchers, is_storno, refunded_amount,
                refund_status, maximum_date_for_shipment, finalization_date, parent_id, detailed_payment_method,
                proforms, cancellation_request, has_editable_products, reason_cancellation, late_shipment, flags,
                emag_club, weekend_delivery, created, modified, enforced_vendor_courier_accounts
        );
    }

    public OrderResult withPaymentMode(String newPaymentMode) {
        return new OrderResult(
                vendor_name, id, status, is_complete, type, newPaymentMode, payment_mode_id, delivery_payment_mode,
                delivery_mode, observation, details, date, payment_status, cashed_co, cashed_cod, shipping_tax,
                shipping_tax_voucher_split, customer, products, attachments, vouchers, is_storno, refunded_amount,
                refund_status, maximum_date_for_shipment, finalization_date, parent_id, detailed_payment_method,
                proforms, cancellation_request, has_editable_products, reason_cancellation, late_shipment, flags,
                emag_club, weekend_delivery, created, modified, enforced_vendor_courier_accounts
        );
    }
*/

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
     * @return null if no differences are detected otherwise a new Order.
     */
    public OrderResult findDifferencesAndModify(OrderResult other) {
        boolean hasDifference = false;
        Builder builder = null;
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
        if (other.modified != null)
            if (other.modified.isAfter(modified)) {
                if (builder == null) builder = toBuilder();
                builder.modified(other.modified);
                hasDifference = true;
            } else {
                System.out.printf("%s:%s -> %s:%s Modified date changed to an older date, from %s to %s%n", vendor_name, id, other.vendor_name, other.id, modified, other.modified);
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
            if (late_shipment == null) {
                if (builder == null) builder = toBuilder();
                builder.late_shipment(other.late_shipment);
                hasDifference = true;
            } else {
                System.out.printf("%s:%s -> %s:%s Late shipment changed from %s to %s%n", vendor_name, id, other.vendor_name, other.id, late_shipment, other.late_shipment);
            }
            hasDifference = true;
        }
        if (!Objects.equals(flags, other.flags)) {
            System.out.printf("%s:%s -> %s:%s Flags differ:%n old: %s%n new: %s%n", vendor_name, id, other.vendor_name, other.id, flags, other.flags);
            var oldElements = flags.stream().map(Flag::flag).collect(Collectors.toSet());
            var newElements = other.flags.stream().map(Flag::flag).collect(Collectors.toSet());
            var addedFlagNames = newElements;
            addedFlagNames.removeAll(oldElements);
            var addedFlags = other.flags.stream().filter(it -> addedFlagNames.contains(it.flag())).toList();
            var removedFlagNames = oldElements;
            removedFlagNames.removeAll(newElements);
            var removedFlags = flags.stream().filter(it -> removedFlagNames.contains(it.flag())).toList();
            var potentiallyChangedFlags = oldElements.stream().filter(newElements::contains).collect(Collectors.toSet());
            var changedFlagNames = potentiallyChangedFlags.stream()
                    .filter(flagName -> {
                        var oldValue = flags.stream().filter(it -> flagName.equals(it.flag())).findFirst().get().value();
                        var newValue = other.flags.stream().filter(it -> flagName.equals(it.flag())).findFirst().get().value();
                        return !Objects.equals(oldValue,newValue);
                    }).collect(Collectors.toSet());
            var changedFlagsOld = flags.stream().filter(it -> changedFlagNames.contains(it.flag())).toList();
            var changedFlagsNew = other.flags.stream().filter(it -> changedFlagNames.contains(it.flag())).toList();

            if (!addedFlags.isEmpty()) System.out.printf("%s:%s -> %s:%s Added Flags: %s%n", vendor_name, id, other.vendor_name, other.id, addedFlags);
            if (!removedFlags.isEmpty()) System.out.printf("%s:%s -> %s:%s Removed Flags: %s%n", vendor_name, id, other.vendor_name, other.id, removedFlags);
            if (!changedFlagNames.isEmpty()) System.out.printf("%s:%s -> %s:%s Changed Flags: %s -> %s%n", vendor_name, id, other.vendor_name, other.id, changedFlagsOld, changedFlagsNew);
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
        return hasDifference ? builder.build() : null;
    }


    /**
     * Returns a new builder initialized with the current OrderResult values.
     */
    public Builder toBuilder() {
        return new Builder()
                .vendor_name(vendor_name)
                .id(id)
                .status(status)
                .is_complete(is_complete)
                .type(type)
                .payment_mode(payment_mode)
                .payment_mode_id(payment_mode_id)
                .delivery_payment_mode(delivery_payment_mode)
                .delivery_mode(delivery_mode)
                .observation(observation)
                .details(details)
                .date(date)
                .payment_status(payment_status)
                .cashed_co(cashed_co)
                .cashed_cod(cashed_cod)
                .shipping_tax(shipping_tax)
                .shipping_tax_voucher_split(shipping_tax_voucher_split)
                .customer(customer)
                .products(products)
                .attachments(attachments)
                .vouchers(vouchers)
                .is_storno(is_storno)
                .refunded_amount(refunded_amount)
                .refund_status(refund_status)
                .maximum_date_for_shipment(maximum_date_for_shipment)
                .finalization_date(finalization_date)
                .parent_id(parent_id)
                .detailed_payment_method(detailed_payment_method)
                .proforms(proforms)
                .cancellation_request(cancellation_request)
                .has_editable_products(has_editable_products)
                .reason_cancellation(reason_cancellation)
                .late_shipment(late_shipment)
                .flags(flags)
                .emag_club(emag_club)
                .weekend_delivery(weekend_delivery)
                .created(created)
                .modified(modified)
                .enforced_vendor_courier_accounts(enforced_vendor_courier_accounts);
    }

    /**
     * Builder class for OrderResult.
     */
    public static class Builder {
        private String vendor_name;
        private String id;
        private int status;
        private Integer is_complete;
        private Integer type;
        private String payment_mode;
        private int payment_mode_id;
        private String delivery_payment_mode;
        private String delivery_mode;
        private String observation;
        private LockerDetails details;
        private LocalDateTime date;
        private Integer payment_status;
        private BigDecimal cashed_co;
        private BigDecimal cashed_cod;
        private BigDecimal shipping_tax;
        private List<VoucherSplit> shipping_tax_voucher_split;
        private Customer customer;
        private List<Product> products;
        private List<Attachment> attachments;
        private List<Voucher> vouchers;
        private boolean is_storno;
        private BigDecimal refunded_amount;
        private String refund_status;
        private LocalDateTime maximum_date_for_shipment;
        private LocalDateTime finalization_date;
        private String parent_id;
        private String detailed_payment_method;
        private List<String> proforms;
        private String cancellation_request;
        private int has_editable_products;
        private CancellationReason reason_cancellation;
        private Integer late_shipment;
        private List<Flag> flags;
        private int emag_club;
        private int weekend_delivery;
        private LocalDateTime created;
        private LocalDateTime modified;
        private List<String> enforced_vendor_courier_accounts;

        // Fluent setter methods for each field
        public Builder vendor_name(String vendor_name) {
            this.vendor_name = vendor_name;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder is_complete(Integer is_complete) {
            this.is_complete = is_complete;
            return this;
        }

        public Builder type(Integer type) {
            this.type = type;
            return this;
        }

        public Builder payment_mode(String payment_mode) {
            this.payment_mode = payment_mode;
            return this;
        }

        public Builder payment_mode_id(int payment_mode_id) {
            this.payment_mode_id = payment_mode_id;
            return this;
        }

        public Builder delivery_payment_mode(String delivery_payment_mode) {
            this.delivery_payment_mode = delivery_payment_mode;
            return this;
        }

        public Builder delivery_mode(String delivery_mode) {
            this.delivery_mode = delivery_mode;
            return this;
        }

        public Builder observation(String observation) {
            this.observation = observation;
            return this;
        }

        public Builder details(LockerDetails details) {
            this.details = details;
            return this;
        }

        public Builder date(LocalDateTime date) {
            this.date = date;
            return this;
        }

        public Builder payment_status(Integer payment_status) {
            this.payment_status = payment_status;
            return this;
        }

        public Builder cashed_co(BigDecimal cashed_co) {
            this.cashed_co = cashed_co;
            return this;
        }

        public Builder cashed_cod(BigDecimal cashed_cod) {
            this.cashed_cod = cashed_cod;
            return this;
        }

        public Builder shipping_tax(BigDecimal shipping_tax) {
            this.shipping_tax = shipping_tax;
            return this;
        }

        public Builder shipping_tax_voucher_split(List<VoucherSplit> shipping_tax_voucher_split) {
            this.shipping_tax_voucher_split = shipping_tax_voucher_split;
            return this;
        }

        public Builder customer(Customer customer) {
            this.customer = customer;
            return this;
        }

        public Builder products(List<Product> products) {
            this.products = products;
            return this;
        }

        public Builder attachments(List<Attachment> attachments) {
            this.attachments = attachments;
            return this;
        }

        public Builder vouchers(List<Voucher> vouchers) {
            this.vouchers = vouchers;
            return this;
        }

        public Builder is_storno(boolean is_storno) {
            this.is_storno = is_storno;
            return this;
        }

        public Builder refunded_amount(BigDecimal refunded_amount) {
            this.refunded_amount = refunded_amount;
            return this;
        }

        public Builder refund_status(String refund_status) {
            this.refund_status = refund_status;
            return this;
        }

        public Builder maximum_date_for_shipment(LocalDateTime maximum_date_for_shipment) {
            this.maximum_date_for_shipment = maximum_date_for_shipment;
            return this;
        }

        public Builder finalization_date(LocalDateTime finalization_date) {
            this.finalization_date = finalization_date;
            return this;
        }

        public Builder parent_id(String parent_id) {
            this.parent_id = parent_id;
            return this;
        }

        public Builder detailed_payment_method(String detailed_payment_method) {
            this.detailed_payment_method = detailed_payment_method;
            return this;
        }

        public Builder proforms(List<String> proforms) {
            this.proforms = proforms;
            return this;
        }

        public Builder cancellation_request(String cancellation_request) {
            this.cancellation_request = cancellation_request;
            return this;
        }

        public Builder has_editable_products(int has_editable_products) {
            this.has_editable_products = has_editable_products;
            return this;
        }

        public Builder reason_cancellation(CancellationReason reason_cancellation) {
            this.reason_cancellation = reason_cancellation;
            return this;
        }

        public Builder late_shipment(Integer late_shipment) {
            this.late_shipment = late_shipment;
            return this;
        }

        public Builder flags(List<Flag> flags) {
            this.flags = flags;
            return this;
        }

        public Builder emag_club(int emag_club) {
            this.emag_club = emag_club;
            return this;
        }

        public Builder weekend_delivery(int weekend_delivery) {
            this.weekend_delivery = weekend_delivery;
            return this;
        }

        public Builder created(LocalDateTime created) {
            this.created = created;
            return this;
        }

        public Builder modified(LocalDateTime modified) {
            this.modified = modified;
            return this;
        }

        public Builder enforced_vendor_courier_accounts(List<String> enforced_vendor_courier_accounts) {
            this.enforced_vendor_courier_accounts = enforced_vendor_courier_accounts;
            return this;
        }

        /**
         * Builds and returns an immutable OrderResult.
         */
        public OrderResult build() {
            return new OrderResult(
                    vendor_name,
                    id,
                    status,
                    is_complete,
                    type,
                    payment_mode,
                    payment_mode_id,
                    delivery_payment_mode,
                    delivery_mode,
                    observation,
                    details,
                    date,
                    payment_status,
                    cashed_co,
                    cashed_cod,
                    shipping_tax,
                    shipping_tax_voucher_split,
                    customer,
                    products,
                    attachments,
                    vouchers,
                    is_storno,
                    refunded_amount,
                    refund_status,
                    maximum_date_for_shipment,
                    finalization_date,
                    parent_id,
                    detailed_payment_method,
                    proforms,
                    cancellation_request,
                    has_editable_products,
                    reason_cancellation,
                    late_shipment,
                    flags,
                    emag_club,
                    weekend_delivery,
                    created,
                    modified,
                    enforced_vendor_courier_accounts
            );
        }
    }

}