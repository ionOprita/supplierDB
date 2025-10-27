package ro.sellfluence.db;

import ro.sellfluence.emagapi.Attachment;
import ro.sellfluence.emagapi.CancellationReason;
import ro.sellfluence.emagapi.Customer;
import ro.sellfluence.emagapi.Flag;
import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.emagapi.Voucher;
import ro.sellfluence.emagapi.VoucherSplit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;

import static java.sql.Statement.RETURN_GENERATED_KEYS;
import static ro.sellfluence.db.CustomerTable.insertOrUpdateCustomer;
import static ro.sellfluence.db.LockerDetailsTable.insertOrUpdateLockerDetails;
import static ro.sellfluence.support.UsefulMethods.toLocalDateTime;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class EmagOrder {

    private static final Scanner scanner = new Scanner(System.in);


    /**
     * Record for reporting the result of inserting an order.
     *
     * @param inserted true if a record was created, false otherwise.
     * @param surrogateId the ID of either the freshly created
     */
    private record InsertResult(boolean inserted, int surrogateId) {
    }

    static int addOrderResult(OrderResult order, Connection db, UUID vendorId, String vendorName) throws SQLException {
        if (order.customer() != null) {
            insertOrUpdateCustomer(db, order.customer());
        }
        if (order.details() != null && order.details().locker_id() != null) {
            insertOrUpdateLockerDetails(db, order.details());
        }
        var orderInserted = insertOrder(db, order, vendorId);
        order.products().stream()
                .filter(product -> product.attachments() != null && product.attachments().size() > 1)
                .forEach(product -> {
                            System.out.printf("Product %s in order %s of vendor %s has attachments.", product.part_number_key(), order.id(), order.vendor_name());
                            reportIssue(order);
                        }
                );
        if (orderInserted.inserted) {
            insertOrderDependents(db, order, orderInserted.surrogateId);
        } else {
            var oldOrder = selectWholeOrderResult(db, orderInserted.surrogateId, vendorName);
            var hasDifferences = oldOrder.reportUnhandledDifferences(order);
            if (hasDifferences) {
                System.out.printf("There are changes in order %s of vendor %s that are not handled yet.", order.id(), order.vendor_name());
                reportIssue(order);
            }
            if (order.modified() != null) {
                if ((oldOrder.modified() == null) || order.modified().isAfter(oldOrder.modified())) {
                    updateTimestamp(db, orderInserted.surrogateId, "modified", order.modified());
                } else if (order.modified().isBefore(oldOrder.modified())) {
                    System.out.printf("%s:%s -> %s:%s Modified date changed to an older date, from %s to %s.%n", oldOrder.vendor_name(), oldOrder.id(), order.vendor_name(), order.id(), oldOrder.modified(), order.modified());
                }
            }
            if (oldOrder.status() != order.status()) {
                System.out.printf("Update status for order %s, was %d will be %d%n.", order.id(), oldOrder.status(), order.status());
                updateInt(db, orderInserted.surrogateId, "status", order.status());
            }
            if (!Objects.equals(oldOrder.is_complete(), order.is_complete())) {
                updateInt(db, orderInserted.surrogateId, "is_complete", order.status());
            }
            if (!Objects.equals(oldOrder.late_shipment(), order.late_shipment())) {
                updateInt(db, orderInserted.surrogateId, "late_shipment", order.late_shipment());
            }
            if (!Objects.equals(oldOrder.payment_status(), order.payment_status())) {
                updateInt(db, orderInserted.surrogateId, "payment_status", order.payment_status());
            }
            if (!Objects.equals(oldOrder.reason_cancellation(), order.reason_cancellation())) {
                updateCancellationReason(db, orderInserted.surrogateId, order.reason_cancellation());
            }
            if (!Objects.equals(order.maximum_date_for_shipment(), oldOrder.maximum_date_for_shipment())) {
                updateTimestamp(db, orderInserted.surrogateId, "maximum_date_for_shipment", order.maximum_date_for_shipment());
            }
            if (!Objects.equals(oldOrder.finalization_date(), order.finalization_date())) {
                updateTimestamp(db, orderInserted.surrogateId, "finalization_date", order.finalization_date());
            }
            if (!Objects.equals(oldOrder.cashed_co(), order.cashed_co())) {
                updateNumeric(db, orderInserted.surrogateId, "cashed_co", order.cashed_co());
            }
            if (!Objects.equals(oldOrder.cashed_cod(), order.cashed_cod())) {
                updateNumeric(db, orderInserted.surrogateId, "cashed_cod", order.cashed_cod());
            }
            if (!Objects.equals(oldOrder.refunded_amount(), order.refunded_amount())) {
                updateNumeric(db, orderInserted.surrogateId, "refunded_amount", order.refunded_amount());
            }
            if (!Objects.equals(oldOrder.refund_status(), order.refund_status())) {
                updateString(db, orderInserted.surrogateId, "refund_status", order.refund_status());
            }
            if (!Objects.equals(oldOrder.delivery_mode(), order.delivery_mode())) {
                updateString(db, orderInserted.surrogateId, "delivery_mode", order.delivery_mode());
            }
            if (!Objects.equals(oldOrder.delivery_payment_mode(), order.delivery_payment_mode())) {
                updateString(db, orderInserted.surrogateId, "delivery_payment_mode", order.delivery_payment_mode());
            }
            if (!Objects.equals(oldOrder.payment_mode(), order.payment_mode())) {
                updateString(db, orderInserted.surrogateId, "payment_mode", order.payment_mode());
            }
            if (!Objects.equals(oldOrder.payment_mode_id(), order.payment_mode_id())) {
                updateInt(db, orderInserted.surrogateId, "payment_mode_id", order.payment_mode_id());
            }
            if (!Objects.equals(oldOrder.detailed_payment_method(), order.detailed_payment_method())) {
                updateString(db, orderInserted.surrogateId, "detailed_payment_method", order.detailed_payment_method());
            }
            updateOrderDependents(db, order, oldOrder, orderInserted.surrogateId);
        }
        return 0;
    }

    /**
     * Insert an order if possible.
     *
     * @param db the database.
     * @param or the order to insert.
     * @param vendorId the vendor to which this order belongs.
     * @return both the status of the operation and if that is true also the surrogate ID generated by the database.
     * @throws SQLException if anything goes wrong.
     */
    private static InsertResult insertOrder(Connection db, OrderResult or, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO emag_order (
                    vendor_id,
                    id,
                    status,
                    is_complete,
                    type,
                    payment_mode,
                    payment_mode_id,
                    delivery_payment_mode,
                    delivery_mode,
                    observation,
                    details_id,
                    date,
                    payment_status,
                    cashed_co,
                    cashed_cod,
                    shipping_tax,
                    customer_id,
                    is_storno,
                    cancellation_reason,
                    refunded_amount,
                    refund_status,
                    maximum_date_for_shipment,
                    finalization_date,
                    parent_id,
                    detailed_payment_method,
                    proforms,
                    cancellation_request,
                    has_editable_products,
                    late_shipment,
                    emag_club,
                    weekend_delivery,
                    created,
                    modified,
                    cancellation_reason_text
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id, vendor_id, status) DO NOTHING
                """, RETURN_GENERATED_KEYS)) {
            s.setObject(1, vendorId);
            s.setString(2, or.id());
            s.setInt(3, or.status());
            s.setInt(4, or.is_complete());
            s.setInt(5, or.type());
            s.setString(6, or.payment_mode());
            s.setInt(7, or.payment_mode_id());
            s.setString(8, or.delivery_payment_mode());
            s.setString(9, or.delivery_mode());
            s.setString(10, or.observation());
            s.setString(11, or.details().locker_id());
            s.setTimestamp(12, toTimestamp(or.date()));
            s.setInt(13, or.payment_status());
            s.setBigDecimal(14, or.cashed_co());
            s.setBigDecimal(15, or.cashed_cod());
            s.setBigDecimal(16, or.shipping_tax());
            s.setObject(17, or.customer() != null ? or.customer().id() : null);
            s.setBoolean(18, or.is_storno());
            s.setObject(19, or.reason_cancellation() == null ? null : or.reason_cancellation().id());
            s.setBigDecimal(20, or.refunded_amount());
            s.setString(21, or.refund_status());
            s.setTimestamp(22, toTimestamp(or.maximum_date_for_shipment()));
            s.setTimestamp(23, toTimestamp(or.finalization_date()));
            s.setString(24, or.parent_id());
            s.setString(25, or.detailed_payment_method());
            s.setString(26, String.join("", or.proforms()));
            s.setString(27, or.cancellation_request());
            s.setInt(28, or.has_editable_products());
            s.setObject(29, or.late_shipment());
            s.setInt(30, or.emag_club());
            s.setInt(31, or.weekend_delivery());
            s.setTimestamp(32, toTimestamp(or.created()));
            s.setTimestamp(33, toTimestamp(or.modified()));
            s.setString(34, or.reason_cancellation() == null ? null : or.reason_cancellation().name());
            int insertedRows = s.executeUpdate();
            if (insertedRows == 0) {
                var surrogateId = selectSurrogateId(db, or.id(), or.vendor_name(), or.status());
                return new InsertResult(false, surrogateId);
            } else if (insertedRows == 1) {
                try (var generatedKeys = s.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int surrogateId = generatedKeys.getInt("surrogate_id");
                        return new InsertResult(true, surrogateId);
                    } else {
                        throw new SQLException("Creating order failed, no surrogate ID obtained.");
                    }
                }
            } else {
                throw new RuntimeException("Unexpected insertion of more than one row! " + or);
            }
        }
    }

    private static OrderResult selectWholeOrderResult(Connection db, int surrogateId, String vendorName) throws SQLException {
        var customer = selectCustomerByOrderId(db, surrogateId);
        var productIds = selectProductIdByOrderId(db, surrogateId);
        var products = productIds.stream().map(productId -> {
            List<VoucherSplit> voucherSplits;
            try {
                voucherSplits = selectVoucherSplitsByProductId(db, productId);
                try {
                    return selectProduct(db, productId, surrogateId, voucherSplits, new ArrayList<>());
                } catch (SQLException e) {
                    throw new RuntimeException("Error retrieving product with productId " + productId, e);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error retrieving the voucher splits of the product with the productId %s.".formatted(productId), e);
            }
        }).toList();
        var voucherSplits = selectVoucherSplitsByOrderId(db, surrogateId);
        List<Attachment> attachments = selectAttachmentsByOrderId(db, surrogateId);
        List<Voucher> vouchers = selectVouchersByOrderId(db, surrogateId);
        var enforcedVendorCourierAccounts = selectEnforcedVendorCourierAccountsByOrderId(db, surrogateId);
        var flags = selectFlagsByOrderId(db, surrogateId);
        return selectOrder(db,
                surrogateId,
                vendorName,
                customer,
                voucherSplits,
                products,
                attachments,
                vouchers,
                enforcedVendorCourierAccounts,
                flags);
    }

    private static Customer selectCustomerByOrderId(Connection db, int surrogateId) throws SQLException {
        Customer customer = null;
        try (var s = db.prepareStatement("""
                SELECT * FROM customer AS c
                INNER JOIN emag_order as o
                ON c.id = o.customer_id
                WHERE o.surrogate_id = ?
                """)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    customer = new Customer(rs.getInt("id"),
                            rs.getInt("mkt_id"),
                            rs.getString("name"),
                            rs.getString("email"),
                            rs.getString("company"),
                            rs.getString("gender"),
                            rs.getString("code"),
                            rs.getString("registration_number"),
                            rs.getString("bank"),
                            rs.getString("iban"),
                            rs.getString("fax"),
                            rs.getInt("legal_entity"),
                            rs.getInt("is_vat_payer"),
                            rs.getString("phone_1"),
                            rs.getString("phone_2"),
                            rs.getString("phone_3"),
                            rs.getString("billing_name"),
                            rs.getString("billing_phone"),
                            rs.getString("billing_country"),
                            rs.getString("billing_suburb"),
                            rs.getString("billing_city"),
                            rs.getString("billing_locality_id"),
                            rs.getString("billing_street"),
                            rs.getString("billing_postal_code"),
                            rs.getString("liable_person"),
                            rs.getString("shipping_country"),
                            rs.getString("shipping_suburb"),
                            rs.getString("shipping_city"),
                            rs.getString("shipping_locality_id"),
                            rs.getString("shipping_street"),
                            rs.getString("shipping_postal_code"),
                            rs.getString("shipping_contact"),
                            rs.getString("shipping_phone"),
                            toLocalDateTime(rs.getTimestamp("created")),
                            toLocalDateTime(rs.getTimestamp("modified")));
                }
            }
        }
        return customer;
    }

    private static List<Attachment> selectAttachmentsByOrderId(Connection db, int surrogateId) throws SQLException {
        String query = "SELECT name, url, type, force_download, visibility FROM attachment WHERE emag_order_surrogate_id = ?";
        var list = new ArrayList<Attachment>();
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new Attachment(rs.getString("name"), rs.getString("url"), rs.getInt("type"), rs.getInt("force_download"), rs.getString("visibility")));
                }
            }
        }
        return list;
    }

    private static List<Flag> selectFlagsByOrderId(Connection db, int surrogateId) throws SQLException {
        var list = new ArrayList<Flag>();
        String query = "SELECT emag_order_surrogate_id, flag, value FROM flag WHERE emag_order_surrogate_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    list.add(new Flag(
                            rs.getString("flag"),
                            rs.getString("value")
                    ));
                } else {
                    return null; // or throw an exception if not found
                }
            }
        }
        return list;
    }

    private static List<String> selectEnforcedVendorCourierAccountsByOrderId(Connection db, int surrogateId) throws SQLException {
        var list = new ArrayList<String>();
        String query = "SELECT courier FROM enforced_vendor_courier_account WHERE emag_order_surrogate_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("courier"));
                }
            }
        }
        return list;
    }

    private static List<Voucher> selectVouchersByOrderId(Connection db, int surrogateId) throws SQLException {
        var list = new ArrayList<Voucher>();
        String query = """
                SELECT voucher_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date, id
                FROM voucher
                WHERE emag_order_surrogate_id = ?
                """;
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new Voucher(rs.getInt("voucher_id"),
                            rs.getString("modified"),
                            rs.getString("created"),
                            rs.getInt("status"),
                            rs.getBigDecimal("sale_price_vat"),
                            rs.getBigDecimal("sale_price"),
                            rs.getString("voucher_name"),
                            rs.getBigDecimal("vat"),
                            rs.getString("issue_date"),
                            rs.getString("id")));
                }
            }
        }
        return list;
    }

    private static List<VoucherSplit> selectVoucherSplitsByProductId(Connection db, int productId) throws SQLException {
        var list = new ArrayList<VoucherSplit>();
        try (var s = db.prepareStatement("""
                SELECT voucher_id, value, vat_value, vat, offered_by, voucher_name FROM voucher_split WHERE product_id = ?
                """)) {
            s.setInt(1, productId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new VoucherSplit(rs.getObject("voucher_id", Integer.class),
                            rs.getBigDecimal("value"),
                            rs.getBigDecimal("vat_value"),
                            rs.getString("vat"),
                            rs.getString("offered_by"),
                            rs.getString("voucher_name")));
                }
            }
        }
        return list;
    }

    private static List<VoucherSplit> selectVoucherSplitsByOrderId(Connection db, int surrogateId) throws SQLException {
        var list = new ArrayList<VoucherSplit>();
        try (var s = db.prepareStatement("""
                SELECT voucher_id, value, vat_value, vat, offered_by, voucher_name FROM order_voucher_split WHERE emag_order_surrogate_id = ?
                """)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new VoucherSplit(rs.getObject("voucher_id", Integer.class),
                            rs.getBigDecimal("value"),
                            rs.getBigDecimal("vat_value"),
                            rs.getString("vat"),
                            rs.getString("offered_by"),
                            rs.getString("voucher_name")));
                }
            }
        }
        return list;
    }

    /**
     * Read all product ID belonging to an order.
     *
     * @param db database
     * @param surrogateId the synthetic ID of the order
     * @return list of product IDs.
     * @throws SQLException on database error
     */
    private static List<Integer> selectProductIdByOrderId(Connection db, int surrogateId) throws SQLException {
        var list = new ArrayList<Integer>();
        try (var s = db.prepareStatement("""
                SELECT id
                FROM product_in_order
                WHERE emag_order_surrogate_id = ?
                """)) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("id"));
                }
            }
        }
        return list;

    }

    private static Product selectProduct(Connection db, int productId, int surrogateId, List<VoucherSplit> product_voucher_splits, List<Attachment> attachments) throws SQLException {
        Product product = null;
        try (var s = db.prepareStatement("""
                SELECT id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties, serial_numbers
                FROM product_in_order
                WHERE id = ? and emag_order_surrogate_id = ?
                """)) {
            s.setInt(1, productId);
            s.setInt(2, surrogateId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    product = new Product(rs.getInt("id"),
                            rs.getInt("product_id"),
                            rs.getInt("mkt_id"),
                            rs.getString("name"),
                            product_voucher_splits,
                            rs.getInt("status"),
                            rs.getString("ext_part_number"),
                            rs.getString("part_number"),
                            rs.getString("part_number_key"),
                            rs.getString("currency"),
                            rs.getString("vat"),
                            rs.getInt("retained_amount"),
                            rs.getInt("quantity"),
                            rs.getInt("initial_qty"),
                            rs.getInt("storno_qty"),
                            rs.getInt("reversible_vat_charging"),
                            rs.getBigDecimal("sale_price"),
                            rs.getBigDecimal("original_price"),
                            toLocalDateTime(rs.getTimestamp("created")),
                            toLocalDateTime(rs.getTimestamp("modified")),
                            Arrays.asList(rs.getString("details").split("\\n")),
                            Arrays.asList(rs.getString("recycle_warranties").split("\\n")),
                            attachments,
                            rs.getString("serial_numbers"));
                }
                if (rs.next()) {
                    throw new RuntimeException("More than one product with the same ID " + productId);
                }
            }
        }
        return product;
    }

    public static Map<Integer, List<Product>> selectAllProduct(Connection db) throws SQLException {
        Map<Integer, List<Product>> productsBySurrogate = new HashMap<>();
        try (var s = db.prepareStatement("""
                SELECT id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties, serial_numbers, emag_order_surrogate_id
                FROM product_in_order
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                   var product = new Product(rs.getInt("id"),
                            rs.getInt("product_id"),
                            rs.getInt("mkt_id"),
                            rs.getString("name"),
                            null,
                            rs.getInt("status"),
                            rs.getString("ext_part_number"),
                            rs.getString("part_number"),
                            rs.getString("part_number_key"),
                            rs.getString("currency"),
                            rs.getString("vat"),
                            rs.getInt("retained_amount"),
                            rs.getInt("quantity"),
                            rs.getInt("initial_qty"),
                            rs.getInt("storno_qty"),
                            rs.getInt("reversible_vat_charging"),
                            rs.getBigDecimal("sale_price"),
                            rs.getBigDecimal("original_price"),
                            toLocalDateTime(rs.getTimestamp("created")),
                            toLocalDateTime(rs.getTimestamp("modified")),
                            Arrays.asList(rs.getString("details").split("\\n")),
                            Arrays.asList(rs.getString("recycle_warranties").split("\\n")),
                            null,
                            rs.getString("serial_numbers"));
                    productsBySurrogate
                            .computeIfAbsent(
                                    rs.getInt("emag_order_surrogate_id"),
                                    k -> new ArrayList<>()
                            )
                            .add(product);

                }
            }
        }
        return productsBySurrogate;
    }

    public record ExtendedOrder(OrderResult order, UUID vendorId, int surrogateId) {

    }

    public static HashMap<String, List<ExtendedOrder>> selectAllOrders(Connection db, Map<Integer, List<Product>> allProducts, Map<UUID, String> allVendors) throws SQLException {
        var orders = new HashMap<String, List<ExtendedOrder>>();
        try (var s = db.prepareStatement("SELECT * FROM emag_order")) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var surrogateId = rs.getInt("surrogate_id");
                    var vendorId = rs.getObject("vendor_id", UUID.class);
                    var vendorName = allVendors.get(vendorId);
                    var products = allProducts.get(surrogateId);
                    List<VoucherSplit>
                            shipping_tax_voucher_split = null;
                    Customer
                            customer = null;
                    List<Attachment>
                            attachments = null;
                    List<Voucher>
                            vouchers = null;
                    List<Flag>
                            flags = null;
                    List<String>
                            enforcedVendorCourierAccounts = null;
                    var order = new OrderResult(
                            vendorName, rs.getString("id"), rs.getInt("status"), rs.getInt("is_complete"), rs.getInt("type"), rs.getString("payment_mode"),
                            rs.getInt("payment_mode_id"), rs.getString("delivery_payment_mode"), rs.getString("delivery_mode"), rs.getString("observation"),
                            new LockerDetails(rs.getString("details_id"), null, 0, null), // Assuming LockerDetails has a constructor that takes locker_id
                            toLocalDateTime(rs.getTimestamp("date")), rs.getInt("payment_status"), rs.getBigDecimal("cashed_co"), rs.getBigDecimal("cashed_cod"),
                            rs.getBigDecimal("shipping_tax"),
                            shipping_tax_voucher_split, customer, products, attachments, vouchers,
                            rs.getBoolean("is_storno"),
                            rs.getBigDecimal("refunded_amount"),
                            rs.getString("refund_status"),
                            toLocalDateTime(rs.getTimestamp("maximum_date_for_shipment")),
                            toLocalDateTime(rs.getTimestamp("finalization_date")),
                            rs.getString("parent_id"),
                            rs.getString("detailed_payment_method"),
                            Arrays.asList(rs.getString("proforms").split("\n")), // Split the string back into a list
                            rs.getString("cancellation_request"),
                            rs.getInt("has_editable_products"),
                            new CancellationReason(rs.getObject("cancellation_reason", Integer.class),
                                    rs.getString("cancellation_reason_text")),
                            rs.getObject("late_shipment", Integer.class), // Assuming late_shipment is a Timestamp
                            flags, rs.getInt("emag_club"),
                            rs.getInt("weekend_delivery"),
                            toLocalDateTime(rs.getTimestamp("created")),
                            toLocalDateTime(rs.getTimestamp("modified")),
                            enforcedVendorCourierAccounts
                    );
                    orders
                            .computeIfAbsent(
                                    order.id(),
                                    k -> new ArrayList<>()
                            )
                            .add(new ExtendedOrder(order, vendorId, surrogateId));
                }
            }
        }
        return orders;
    }

    private static OrderResult selectOrder(Connection db,
                                           int surrogateId,
                                           String vendorName,
                                           Customer customer,
                                           List<VoucherSplit> shipping_tax_voucher_split,
                                           List<Product> products,
                                           List<Attachment> attachments,
                                           List<Voucher> vouchers,
                                           List<String> enforcedVendorCourierAccounts,
                                           List<Flag> flags) throws SQLException {
        OrderResult order = null;
        try (var s = db.prepareStatement("SELECT * FROM emag_order WHERE surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    order = new OrderResult(
                            vendorName, rs.getString("id"), rs.getInt("status"), rs.getInt("is_complete"), rs.getInt("type"), rs.getString("payment_mode"),
                            rs.getInt("payment_mode_id"), rs.getString("delivery_payment_mode"), rs.getString("delivery_mode"), rs.getString("observation"),
                            new LockerDetails(rs.getString("details_id"), null, 0, null), // Assuming LockerDetails has a constructor that takes locker_id
                            toLocalDateTime(rs.getTimestamp("date")), rs.getInt("payment_status"), rs.getBigDecimal("cashed_co"), rs.getBigDecimal("cashed_cod"),
                            rs.getBigDecimal("shipping_tax"),
                            shipping_tax_voucher_split, customer, products, attachments, vouchers,
                            rs.getBoolean("is_storno"),
                            rs.getBigDecimal("refunded_amount"),
                            rs.getString("refund_status"),
                            toLocalDateTime(rs.getTimestamp("maximum_date_for_shipment")),
                            toLocalDateTime(rs.getTimestamp("finalization_date")),
                            rs.getString("parent_id"),
                            rs.getString("detailed_payment_method"),
                            Arrays.asList(rs.getString("proforms").split("\n")), // Split the string back into a list
                            rs.getString("cancellation_request"),
                            rs.getInt("has_editable_products"),
                            new CancellationReason(rs.getObject("cancellation_reason", Integer.class),
                                    rs.getString("cancellation_reason_text")),
                            rs.getObject("late_shipment", Integer.class), // Assuming late_shipment is a Timestamp
                            flags, rs.getInt("emag_club"),
                            rs.getInt("weekend_delivery"),
                            toLocalDateTime(rs.getTimestamp("created")),
                            toLocalDateTime(rs.getTimestamp("modified")),
                            enforcedVendorCourierAccounts
                    );
                }
            }
        }
        return order;
    }

    /**
     * Find the surrogate ID for a given (orderId, vendorName, status).
     *
     * @param db the database.
     * @param orderId the ID from emag.
     * @param vendorName the name of the vendor.
     * @param status the status.
     * @return The surrogate ID or null.
     * @throws SQLException if anything goes wrong.
     */
    private static Integer selectSurrogateId(Connection db, String orderId, String vendorName, int status) throws SQLException {
        Integer result = null;
        try (var s = db.prepareStatement("SELECT surrogate_id FROM emag_order AS o INNER JOIN vendor AS v ON o.vendor_id = v.id WHERE o.id = ? AND v.vendor_name = ? AND o.status = ?")) {
            s.setString(1, orderId);
            s.setString(2, vendorName);
            s.setInt(3, status);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    result = rs.getInt(1);
                }
                if (rs.next()) {
                    throw new RuntimeException("Found two orders with ID=%s, vendor=%s and status=%d.".formatted(orderId, vendorName, status));
                }
            }
        }
        return result;
    }

    private static void reportIssue(OrderResult order) {
        System.out.println("The above message indicates that for some changes, there is no code to handle them.");
        System.out.println("A fix to the program is needed. You need to report this error to the developer.");
        System.out.printf("Please report the vendor '%s' and order ID '%s'.%n", order.vendor_name(), order.id());
        System.out.println("If you want, you can continue with the program or wait until a fix is provided.");
        System.out.println("Please type the word “Yes” if you want the program to continue and fix the problem some other time. Type “No” if you want the program to stop and wait until you receive a fixed version of the program.");
        System.out.println("If you type anything else, the program will stop now.");
        String input = scanner.nextLine().trim().toLowerCase();
        boolean isYes = input.equals("yes");
        System.out.println("You entered: " + (isYes ? "YES, and the program will continue" : "NO, thus the program will terminate now."));
        if (!isYes) System.exit(1);
    }

    /**
     * Update a numeric field in the emag_order table.
     *
     * @param db database connection.
     * @param surrogateId row id.
     * @param field name of the field.
     * @param newValue value to store.
     * @return 1 if the row was updated or 0 if no row matched the surrogateId.
     * @throws SQLException on database errors.
     */
    private static int updateNumeric(Connection db, int surrogateId, final String field, BigDecimal newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE surrogate_id= ?")) {
            s.setObject(1, newValue);
            s.setInt(2, surrogateId);
            return s.executeUpdate();
        }
    }

    /**
     * Update an integer field in the emag_order table.
     *
     * @param db database connection.
     * @param surrogateId row id.
     * @param field name of the field.
     * @param newValue value to store.
     * @return 1 if the row was updated or 0 if no row matched the surrogateId.
     * @throws SQLException on database errors.
     */
    private static int updateInt(Connection db, int surrogateId, final String field, int newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE surrogate_id= ?")) {
            s.setInt(1, newValue);
            s.setInt(2, surrogateId);
            return s.executeUpdate();
        }
    }

    /**
     * Update a string field in the emag_order table.
     *
     * @param db database connection.
     * @param surrogateId row id.
     * @param field name of the field.
     * @param newValue value to store.
     * @return 1 if the row was updated or 0 if no row matched the surrogateId.
     * @throws SQLException on database errors.
     */
    private static int updateString(Connection db, int surrogateId, final String field, String newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE surrogate_id= ?")) {
            s.setString(1, newValue);
            s.setInt(2, surrogateId);
            return s.executeUpdate();
        }
    }

    /**
     * Update a timestamp field in the emag_order table.
     *
     * @param db database connection.
     * @param surrogateId row id.
     * @param field name of the field.
     * @param newValue value to store.
     * @return 1 if the row was updated or 0 if no row matched the surrogateId.
     * @throws SQLException on database errors.
     */
    private static int updateTimestamp(Connection db, int surrogateId, String field, LocalDateTime newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE surrogate_id= ?")) {
            s.setTimestamp(1, toTimestamp(newValue));
            s.setInt(2, surrogateId);
            return s.executeUpdate();
        }
    }

    /**
     * Update the cancellation reason in the emag_order table.
     * This updates both cancellation_reason and cancellation_reason_text.
     *
     * @param db database connection.
     * @param surrogateId row id.
     * @param newValue value to store.
     * @return 1 if the row was updated or 0 if no row matched the surrogateId.
     * @throws SQLException on database errors.
     */
    private static int updateCancellationReason(Connection db, int surrogateId, CancellationReason newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET cancellation_reason = ?, cancellation_reason_text = ?  WHERE surrogate_id = ?")) {
            s.setInt(1, newValue.id());
            s.setString(2, newValue.name());
            s.setInt(3, surrogateId);
            return s.executeUpdate();
        }
    }

    /**
     * Update order products, flags, attachments and vouchers.
     *
     * @param db database connection.
     * @param order order with the up-to-date values.
     * @param oldOrder order as it was found in the database.
     * @param surrogateId row id.
     * @throws SQLException on database errors.
     */
    private static void updateOrderDependents(Connection db, OrderResult order, OrderResult oldOrder, int surrogateId) throws SQLException {
        if (!Objects.equals(oldOrder.flags(), order.flags())) {
            updateFlags(db, order, surrogateId);
        }
        if (!Objects.equals(oldOrder.products(), order.products())) {
            updateProducts(db, order, surrogateId);
        }
        if (!Objects.equals(oldOrder.attachments(), order.attachments())) {
            updateAttachments(db, order, surrogateId);
        }
        if (!Objects.equals(oldOrder.vouchers(), order.vouchers())) {
            updateVouchers(db, order, surrogateId);
        }
        if (!Objects.equals(oldOrder.shipping_tax_voucher_split(), order.shipping_tax_voucher_split())) {
            updateOrderVoucherSplits(db, order, surrogateId);
        }
    }

    private static void insertOrderDependents(Connection db, OrderResult order, int surrogateId) throws SQLException {
        insertProducts(db, order, surrogateId);
        if (order.shipping_tax_voucher_split() != null) {
            for (var voucherSplit : order.shipping_tax_voucher_split()) {
                insertOrderVoucherSplit(db, voucherSplit, surrogateId);
            }
        }
        insertAttachments(db, order, surrogateId);
        insertVouchers(db, order, surrogateId);
        insertFlags(db, order, surrogateId);
        if (order.enforced_vendor_courier_accounts() != null) {
            for (String enforced_vendor_courier_account : order.enforced_vendor_courier_accounts()) {
                insertEnforcedVendorCourierAccount(db, enforced_vendor_courier_account, surrogateId);
            }
        }
    }

    private static int insertEnforcedVendorCourierAccount(Connection db, String enforcedVendorCourierAccount, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO enforced_vendor_courier_account (emag_order_surrogate_id, courier) VALUES (?, ?) ON CONFLICT(emag_order_surrogate_id, courier)")) {
            s.setInt(1, surrogateId);
            s.setString(2, enforcedVendorCourierAccount);
            return s.executeUpdate();
        }
    }

    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, int surrogateId, int productId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO voucher_split (voucher_id, emag_order_surrogate_id, product_id, value, vat_value, vat, offered_by, voucher_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id, product_id, emag_order_surrogate_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id());
            s.setInt(2, surrogateId);
            s.setInt(3, productId);
            s.setBigDecimal(4, voucherSplit.value());
            s.setBigDecimal(5, voucherSplit.vat_value());
            s.setString(6, voucherSplit.vat());
            s.setString(7, voucherSplit.offered_by());
            s.setString(8, voucherSplit.voucher_name());
            return s.executeUpdate();
        }
    }

    private static int insertOrderVoucherSplit(Connection conn, VoucherSplit voucherSplit, int surrogateId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO order_voucher_split (voucher_id, emag_order_surrogate_id, value, vat_value, vat, offered_by, voucher_name) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id, emag_order_surrogate_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id());
            s.setInt(2, surrogateId);
            s.setBigDecimal(3, voucherSplit.value());
            s.setBigDecimal(4, voucherSplit.vat_value());
            s.setString(5, voucherSplit.vat());
            s.setString(6, voucherSplit.offered_by());
            s.setString(7, voucherSplit.voucher_name());
            return s.executeUpdate();
        }
    }

    private static int insertProductInOrder(Connection db, Product product, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO product_in_order (id, emag_order_surrogate_id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties, serial_numbers) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id, emag_order_surrogate_id) DO NOTHING")) {
            s.setInt(1, product.id());
            s.setInt(2, surrogateId);
            s.setInt(3, product.product_id());
            s.setObject(4, product.mkt_id());
            s.setString(5, product.name());
            s.setInt(6, product.status());
            s.setString(7, product.ext_part_number());
            s.setString(8, product.part_number());
            s.setString(9, product.part_number_key());
            s.setString(10, product.currency());
            s.setString(11, product.vat());
            s.setInt(12, product.retained_amount());
            s.setInt(13, product.quantity());
            s.setInt(14, product.initial_qty());
            s.setInt(15, product.storno_qty());
            s.setInt(16, product.reversible_vat_charging());
            s.setBigDecimal(17, product.sale_price());
            s.setBigDecimal(18, product.original_price());
            s.setTimestamp(19, toTimestamp(product.created()));
            s.setTimestamp(20, toTimestamp(product.modified()));
            s.setString(21, String.join("\n", product.details()));
            s.setString(22, String.join("\n", product.recycle_warranties()));
            s.setString(23, product.serial_numbers());
            return s.executeUpdate();
        }
    }

    private static void insertVouchers(Connection db, OrderResult order, int surrogateId) throws SQLException {
        if (order.vouchers() != null) {
            for (Voucher voucher : order.vouchers()) {
                insertVoucher(db, voucher, surrogateId);
            }
        }
    }

    private static int insertVoucher(Connection db, Voucher voucher, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO voucher (voucher_id, emag_order_surrogate_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id, emag_order_surrogate_id) DO NOTHING")) {
            s.setInt(1, voucher.voucher_id());
            s.setInt(2, surrogateId);
            s.setString(3, voucher.modified());
            s.setString(4, voucher.created());
            s.setInt(5, voucher.status());
            s.setBigDecimal(6, voucher.sale_price_vat());
            s.setBigDecimal(7, voucher.sale_price());
            s.setString(8, voucher.voucher_name());
            s.setBigDecimal(9, voucher.vat());
            s.setString(10, voucher.issue_date());
            s.setString(11, voucher.id());
            return s.executeUpdate();
        }
    }

    private static void insertAttachments(Connection db, OrderResult order, int surrogateId) throws SQLException {
        if (order.attachments() != null) {
            for (var attachment : order.attachments()) {
                insertAttachment(db, attachment, surrogateId);
            }
        }
    }

    private static int insertAttachment(Connection db, Attachment attachment, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO attachment (emag_order_surrogate_id, name, url, type, force_download, visibility) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(emag_order_surrogate_id, url) DO NOTHING")) {
            s.setInt(1, surrogateId);
            s.setString(2, attachment.name());
            s.setString(3, attachment.url());
            s.setInt(4, attachment.type());
            s.setInt(5, attachment.force_download());
            s.setString(6, attachment.visibility());
            return s.executeUpdate();
        }
    }

    private static void insertProducts(Connection db, OrderResult order, int surrogateId) throws SQLException {
        if (order.products() != null) {
            for (var product : order.products()) {
                insertProductInOrder(db, product, surrogateId);
                insertVoucherSplits(db, surrogateId, product);
            }
        }
    }

    private static int deleteProducts(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM product_in_order WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static void updateProducts(Connection db, OrderResult order, int surrogateId) throws SQLException {
        deleteVoucherSplits(db, surrogateId);
        deleteProducts(db, surrogateId);
        if (order.products() != null) {
            for (var product : order.products()) {
                insertProductInOrder(db, product, surrogateId);
                insertVoucherSplits(db, surrogateId, product);
            }
        }
    }

    private static void insertVoucherSplits(Connection db, int surrogateId, Product product) throws SQLException {
        for (var voucherSplit : product.product_voucher_split()) {
            insertVoucherSplit(db, voucherSplit, surrogateId, product.id());
        }
    }

    private static int deleteVoucherSplits(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM voucher_split WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static int deleteOrderVoucherSplits(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM order_voucher_split WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static void insertFlags(Connection db, OrderResult order, int surrogateId) throws SQLException {
        if (order.flags() != null) {
            for (Flag flag : order.flags()) {
                insertFlag(db, flag, surrogateId);
            }
        }
    }

    private static int insertFlag(Connection db, Flag flag, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO flag (emag_order_surrogate_id, flag, value) VALUES (?, ?, ?) ON CONFLICT(emag_order_surrogate_id, flag) DO NOTHING")) {
            s.setInt(1, surrogateId);
            s.setString(2, flag.flag());
            s.setString(3, flag.value());
            return s.executeUpdate();
        }
    }

    private static int deleteFlags(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM flag WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static int deleteAttachments(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM attachment WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static int deleteVouchers(Connection db, int surrogateId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM voucher WHERE emag_order_surrogate_id = ?")) {
            s.setInt(1, surrogateId);
            return s.executeUpdate();
        }
    }

    private static void updateFlags(Connection db, OrderResult order, int surrogateId) throws SQLException {
        deleteFlags(db, surrogateId);
        insertFlags(db, order, surrogateId);
    }

    private static void updateAttachments(Connection db, OrderResult order, int surrogateId) throws SQLException {
        deleteAttachments(db, surrogateId);
        insertAttachments(db, order, surrogateId);
    }

    private static void updateVouchers(Connection db, OrderResult order, int surrogateId) throws SQLException {
        deleteVouchers(db, surrogateId);
        insertVouchers(db, order, surrogateId);
    }

    private static void updateOrderVoucherSplits(Connection db, OrderResult order, int surrogateId) throws SQLException {
        deleteOrderVoucherSplits(db, surrogateId);
        for (VoucherSplit voucherSplit : order.shipping_tax_voucher_split()) {
            insertOrderVoucherSplit(db, voucherSplit, surrogateId);
        }
    }
}