package ro.sellfluence.db;

import ch.claudio.db.DB;
import org.jetbrains.annotations.Nullable;
import ro.sellfluence.emagapi.AWB;
import ro.sellfluence.emagapi.Attachment;
import ro.sellfluence.emagapi.CancellationReason;
import ro.sellfluence.emagapi.Customer;
import ro.sellfluence.emagapi.Flag;
import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.emagapi.RequestHistory;
import ro.sellfluence.emagapi.ReturnedProduct;
import ro.sellfluence.emagapi.StatusHistory;
import ro.sellfluence.emagapi.StatusRequest;
import ro.sellfluence.emagapi.Voucher;
import ro.sellfluence.emagapi.VoucherSplit;
import ro.sellfluence.sheetSupport.Conversions;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.math.RoundingMode.HALF_EVEN;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

public class EmagMirrorDB {

    private static final Map<String, EmagMirrorDB> openDatabases = new HashMap<>();
    private static final Logger logger = Logger.getLogger(EmagMirrorDB.class.getName());

    private final DB database;

    private EmagMirrorDB(DB database) {
        this.database = database;
    }

    public static EmagMirrorDB getEmagMirrorDB(String alias) throws SQLException, IOException {
        var mirrorDB = openDatabases.get(alias);
        if (mirrorDB == null) {
            var db = new DB(alias);
            db.prepareDB(EmagMirrorDBVersion1::version1,
                    EmagMirrorDBVersion2::version2,
                    EmagMirrorDBVersion3::version3,
                    EmagMirrorDBVersion4::version4,
                    EmagMirrorDBVersion5::version5,
                    EmagMirrorDBVersion6::version6,
                    EmagMirrorDBVersion7::version7);
            mirrorDB = new EmagMirrorDB(db);
            openDatabases.put(alias, mirrorDB);
        }
        return mirrorDB;
    }

    /**
     * Add or update the order in the database.
     *
     * @param order as received from eMAG.
     * @throws SQLException if something goes wrong.
     */
    public void addOrder(OrderResult order) throws SQLException {
        var vendorName = order.vendor_name();
        var vendorId = getOrAddVendor(vendorName);
        database.writeTX(db -> {
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
            if (orderInserted == 1) {
                insertOrderDependents(db, order, vendorId);
            } else {
                var oldOrder = selectWholeOrderResult(db, order.id(), vendorId, vendorName);
                var hasDifferences = oldOrder.reportUnhandledDifferences(order);
                if (hasDifferences) {
                    reportIssue(order);
                }
                if (order.modified() != null) {
                    if ((oldOrder.modified() == null) || order.modified().isAfter(oldOrder.modified())) {
                        updateTimestamp(db, order.id(), vendorId, "modified", order.modified());
                    } else if (order.modified().isBefore(oldOrder.modified())) {
                        System.out.printf("%s:%s -> %s:%s Modified date changed to an older date, from %s to %s%n", oldOrder.vendor_name(), oldOrder.id(), order.vendor_name(), order.id(), oldOrder.modified(), order.modified());
                    }
                }
                if (oldOrder.status() != order.status()) {
                    updateInt(db, order.id(), vendorId, "status", order.status());
                }
                if (!Objects.equals(oldOrder.is_complete(), order.is_complete())) {
                    updateInt(db, order.id(), vendorId, "is_complete", order.status());
                }
                if (!Objects.equals(oldOrder.late_shipment(), order.late_shipment())) {
                    updateInt(db, order.id(), vendorId, "late_shipment", order.late_shipment());
                }
                if (!Objects.equals(oldOrder.payment_status(), order.payment_mode())) {
                    updateInt(db, order.id(), vendorId, "payment_status", order.payment_status());
                }
                if (!Objects.equals(oldOrder.reason_cancellation(), order.reason_cancellation())) {
                    updateCancellationReason(db, order.id(), vendorId, order.reason_cancellation());
                }
                if (!Objects.equals(order.maximum_date_for_shipment(), oldOrder.maximum_date_for_shipment())) {
                    updateTimestamp(db, order.id(), vendorId, "maximum_date_for_shipment", order.maximum_date_for_shipment());
                }
                if (!Objects.equals(oldOrder.finalization_date(), order.finalization_date())) {
                    updateTimestamp(db, order.id(), vendorId, "finalization_date", order.finalization_date());
                }
                if (!Objects.equals(oldOrder.cashed_co(), order.cashed_co())) {
                    updateNumeric(db, order.id(), vendorId, "cashed_co", order.cashed_co());
                }
                if (!Objects.equals(oldOrder.cashed_cod(), order.cashed_cod())) {
                    updateNumeric(db, order.id(), vendorId, "cashed_cod", order.cashed_cod());
                }
                if (!Objects.equals(oldOrder.refunded_amount(), order.refunded_amount())) {
                    updateNumeric(db, order.id(), vendorId, "refunded_amount", order.refunded_amount());
                }
                if (!Objects.equals(oldOrder.refund_status(), order.refund_status())) {
                    updateString(db, order.id(), vendorId, "refund_status", order.refund_status());
                }
                updateOrderDependents(db, order, oldOrder, vendorId);
            }
            return 0;
        });
    }

    private static final Scanner scanner = new Scanner(System.in);

    private void reportIssue(OrderResult order) {
        System.out.println("The above message indicates that for some changes, there is no code to handle them.");
        System.out.println("A fix to the program is needed. You need to report this error to the developer.");
        System.out.printf("Please report the vendor '%s' and order ID '%s'.%n", order.vendor_name(), order.id());
        System.out.println("If you want, you can continue with the program, or wait until a fix is provided.");
        System.out.println("Please type the word \"Yes\" if you want the program to continue and fix the problem some other time. Type \"No\" if you want the program to stop and wait until you receive a fixed version of the program.");
        System.out.println("If you type anything else, the program will stop now.");
        String input = scanner.nextLine().trim().toLowerCase();
        boolean isYes = input.equals("yes");
        System.out.println("You entered: " + (isYes ? "YES and the program will continue" : "NO thus the program will terminate now."));
        if (!isYes) System.exit(1);
    }

    private static int updateNumeric(Connection db, String orderId, UUID vendorId, final String field, BigDecimal newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE vendor_id = ? AND id= ?")) {
            s.setObject(1, newValue);
            s.setObject(2, vendorId);
            s.setString(3, orderId);
            return s.executeUpdate();
        }
    }

    private static int updateInt(Connection db, String orderId, UUID vendorId, final String field, int newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE vendor_id = ? AND id= ?")) {
            s.setInt(1, newValue);
            s.setObject(2, vendorId);
            s.setString(3, orderId);
            return s.executeUpdate();
        }
    }

    private static int updateString(Connection db, String orderId, UUID vendorId, final String field, String newValue) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE vendor_id = ? AND id= ?")) {
            s.setString(1, newValue);
            s.setObject(2, vendorId);
            s.setString(3, orderId);
            return s.executeUpdate();
        }
    }

    private static int updateTimestamp(Connection db, String orderId, UUID vendorId, String field, LocalDateTime date) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET " + field + " =? WHERE vendor_id = ? AND id= ?")) {
            s.setTimestamp(1, toTimestamp(date));
            s.setObject(2, vendorId);
            s.setString(3, orderId);
            return s.executeUpdate();
        }
    }

    private static int updateCancellationReason(Connection db, String orderId, UUID vendorId, CancellationReason reason) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_order SET cancellation_reason = ?, cancellation_reason_text = ?  WHERE vendor_id = ? AND id= ?")) {
            s.setInt(1, reason.id());
            s.setString(2, reason.name());
            s.setObject(3, vendorId);
            s.setString(4, orderId);
            return s.executeUpdate();
        }
    }

    private void updateOrderDependents(Connection db, OrderResult order, OrderResult oldOrder, UUID vendorId) throws SQLException {
        if (!Objects.equals(oldOrder.flags(), order.flags())) {
            updateFlags(db, order, vendorId);
        }
        if (!Objects.equals(oldOrder.products(), order.products())) {
            updateProducts(db, order, vendorId);
        }
        if (!Objects.equals(oldOrder.attachments(), order.attachments())) {
            updateAttachments(db, order, vendorId);
        }
        if (!Objects.equals(oldOrder.vouchers(), order.vouchers())) {
            updateVouchers(db, order, vendorId);
        }

    }

    private static void insertOrderDependents(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        insertProducts(db, order, vendorId);
        if (order.shipping_tax_voucher_split() != null) {
            for (var voucherSplit : order.shipping_tax_voucher_split()) {
                insertVoucherSplit(db, voucherSplit, order.id(), vendorId);
            }
        }
        insertAttachments(db, order, vendorId);
        insertVouchers(db, order, vendorId);
        insertFlags(db, order, vendorId);
        if (order.enforced_vendor_courier_accounts() != null) {
            for (String enforced_vendor_courier_account : order.enforced_vendor_courier_accounts()) {
                insertEnforcedVendorCourierAccount(db, enforced_vendor_courier_account, order.id(), vendorId);
            }
        }
    }

    private static void insertVouchers(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        if (order.vouchers() != null) {
            for (Voucher voucher : order.vouchers()) {
                insertVoucher(db, voucher, order.id(), vendorId);
            }
        }
    }

    private static void insertAttachments(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        if (order.attachments() != null) {
            for (var attachment : order.attachments()) {
                insertAttachment(db, attachment, order.id(), vendorId);
            }
        }
    }

    private static void insertProducts(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        if (order.products() != null) {
            for (var product : order.products()) {
                insertProduct(db, product, order.id(), vendorId);
                insertVoucherSplits(db, order, vendorId, product);
            }
        }
    }

    private static int deleteProducts(Connection db, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM product_in_order WHERE vendor_id = ? AND order_id= ?")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            return s.executeUpdate();
        }
    }

    private static void updateProducts(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        deleteVoucherSplits(db, order.id(), vendorId);
        deleteProducts(db, order.id(), vendorId);
        if (order.products() != null) {
            for (var product : order.products()) {
                insertProduct(db, product, order.id(), vendorId);
                insertVoucherSplits(db, order, vendorId, product);
            }
        }
    }

    private static void insertVoucherSplits(Connection db, OrderResult order, UUID vendorId, Product product) throws SQLException {
        for (var voucherSplit : product.product_voucher_split()) {
            insertVoucherSplit(db, voucherSplit, order.id(), vendorId, product.id());
        }
    }

    private static int deleteVoucherSplits(Connection db, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM voucher_split WHERE vendor_id = ? AND order_id= ?")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            return s.executeUpdate();
        }
    }

    private static void insertFlags(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        if (order.flags() != null) {
            for (Flag flag : order.flags()) {
                insertFlag(db, flag, order.id(), vendorId);
            }
        }
    }

    private static int deleteFlags(Connection db, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM flag WHERE vendor_id = ? AND order_id= ?")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            return s.executeUpdate();
        }
    }

    private static int deleteAttachments(Connection db, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM attachment WHERE vendor_id = ? AND order_id= ?")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            return s.executeUpdate();
        }
    }

    private static int deleteVouchers(Connection db, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM voucher WHERE vendor_id = ? AND order_id= ?")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            return s.executeUpdate();
        }
    }

    private static void updateFlags(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        deleteFlags(db, order.id(), vendorId);
        insertFlags(db, order, vendorId);
    }
    private static void updateAttachments(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        deleteAttachments(db, order.id(), vendorId);
        insertAttachments(db, order, vendorId);
    }
    private static void updateVouchers(Connection db, OrderResult order, UUID vendorId) throws SQLException {
        deleteVouchers(db, order.id(), vendorId);
        insertVouchers(db, order, vendorId);
    }


    public OrderResult readOrder(String orderId, String vendorName) throws SQLException {
        return database.readTX(db -> {
            var vendorId = selectVendorIdByName(db, vendorName);
            return selectWholeOrderResult(db, orderId, vendorId, vendorName);
        });
    }

    private OrderResult selectWholeOrderResult(Connection db, String orderId, UUID vendorId, String vendorName) throws SQLException {
        var customer = selectCustomerByOrderId(db, orderId, vendorId);
        var productIds = selectProductIdByOrderId(db, orderId, vendorId);
        var products = productIds.stream().map(productId -> {
            List<VoucherSplit> voucherSplits;
            try {
                voucherSplits = selectVoucherSplitsByProductId(db, productId);
                try {
                    return selectProduct(db, productId, voucherSplits, new ArrayList<>());
                } catch (SQLException e) {
                    throw new RuntimeException("Error retrieving product with productId " + productId, e);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Error retrieving voucher_splits with product productId " + productId, e);
            }
        }).toList();
        var voucherSplits = selectVoucherSplitsByOrderId(db, orderId, vendorId);
        List<Attachment> attachments = selectAttachmentsByOrderId(db, orderId, vendorId);
        List<Voucher> vouchers = selectVouchersByOrderId(db, orderId, vendorId);
        var enforcedVendorCourierAccounts = selectEnforcedVendorCourierAccountsByOrderId(db, orderId, vendorId);
        var flags = selectFlagsByOrderId(db, orderId, vendorId);
        return selectOrder(db,
                orderId,
                vendorId,
                vendorName,
                customer,
                voucherSplits,
                products,
                attachments,
                vouchers,
                enforcedVendorCourierAccounts,
                flags);
    }


    private static void insertOrUpdateLockerDetails(Connection db, final LockerDetails details) throws SQLException {
        var added = insertLockerDetails(db, details);
        if (added == 0) {
            var current = selectLockerDetails(db, details.locker_id());
            if (!details.equals(current)) {
                logger.log(FINE, () -> "LockerDetails differs:%n old: %s%n new: %s%n".formatted(current, details));
                updateLockerDetails(db, details);
            }
        }
    }

    /**
     * Insert a customer into the database.
     * If there is already a customer by the same ID the record is updated if the data differ and the new receord has
     * a more recent modified date.
     *
     * @param db database
     * @param customer new customer record
     * @throws SQLException in case of malfunction
     */
    private static void insertOrUpdateCustomer(Connection db, final Customer customer) throws SQLException {
        var added = insertCustomer(db, customer);
        if (added == 0) {
            var current = selectCustomer(db, customer.id());
            if (!customer.sameExceptForDate(current) && customer.modified().isAfter(current.modified())) {
                logger.log(FINE, () -> "Updating customer:%n old: %s%n new: %s%n".formatted(current, customer));
                updateCustomer(db, customer);
            }
        }
    }

    private void insertOrUpdateOrder(OrderResult order, Connection db, UUID vendorId) throws SQLException {
        var ordersAdded = insertOrder(db, order, vendorId);
        if (ordersAdded == 0) {
/*TODO
            var current = selectOrder(db, order.id(), vendorId);
            if (!current.equals(order)) {
                logger.log(INFO, () -> "Order differs:%n old: %s%nnew: %s%n".formatted(current, order));
                updateOrder(db, order, vendorId);
            }
*/
        }
    }

    public void addRMA(RMAResult rmaResult) throws SQLException {
        database.writeTX(db -> {
            var emagId = rmaResult.emag_id();
            insertRMAResult(db, rmaResult);
            if (rmaResult.products() != null) {
                for (var product : rmaResult.products()) {
                    insertReturnedProduct(db, product, emagId);
                }
            }
            if (rmaResult.awbs() != null) {
                for (var awb : rmaResult.awbs()) {
                    insertAWB(db, awb, emagId);
                }
            }
            if (rmaResult.request_history() != null) {
                for (var requestHistory : rmaResult.request_history()) {
                    insertRequestHistory(db, requestHistory, emagId);
                }
            }
            if (rmaResult.status_history() != null) {
                for (var statusHistory : rmaResult.status_history()) {
                    var historyUUID = UUID.randomUUID();
                    insertStatusHistory(db, statusHistory, emagId, historyUUID);
                    if (statusHistory.requests() != null) {
                        for (var statusRequest : statusHistory.requests()) {
                            insertStatusRequest(db, statusRequest, historyUUID);
                        }
                    }
                }
            }
            return 0;
        });
    }

    public void addProduct(ProductInfo productInfo) throws SQLException {
        database.writeTX(db -> insertProduct(db, productInfo));
    }

    public void addEmagLog(String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        database.writeTX(db -> {
            var insertedRows = insertEmagLog(db, account, date,fetchTime, error);
            if (insertedRows == 0) {
                updateEmagLog(db, account, date,fetchTime, error);
            }
            return "";
        });
    }

    public Optional<EmagFetchLog> getFetchStatus(String account, LocalDate date) throws SQLException {
        return database.readTX(db -> Optional.ofNullable(getEmagLog(db, account, date)));
    }

    /**
     * Read database information and prepare them for inclusion in the spreadsheet.
     * Only orders with status finalized or returned matching the year are provided.
     *
     * @param year Return only orders where the date has this as year value.
     * @return list of rows containing a list of cell groups. Each cell group is a list of cells.
     * @throws SQLException on database error
     */
    public List<List<List<Object>>> readForSheet(int year) throws SQLException {
        return database.readTX(db -> {
            List<List<List<Object>>> rows = new ArrayList<>();
            try (var s = db.prepareStatement(
                    //language=sql
                    """
                            SELECT
                              o.date,
                              o.id,
                              o.status,
                              pi.name,
                              p.quantity,
                              p.sale_price,
                              o.delivery_mode,
                              c.name,
                              c.shipping_phone,
                              c.billing_name,
                              c.billing_phone,
                              c.code,
                              o.observation,
                              v.vendor_name,
                              v.isFBE,
                              pi.message_keyword
                            FROM emag_order as o
                            LEFT JOIN customer as c
                            ON o.customer_id = c.id
                            LEFT JOIN vendor as v
                            ON o.vendor_id = v.id
                            LEFT JOIN product_in_order as p
                            ON p.order_id = o.id AND p.vendor_id = o.vendor_id
                            LEFT JOIN product as pi
                            ON p.part_number_key = pi.emag_pnk
                            WHERE EXTRACT(YEAR FROM o.date) = ? AND (o.status = 4 OR o.status = 5)
                            ORDER BY o.date
                            """)) {
                s.setInt(1, year);
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
// 2024-10-17 16:49:51
                        var priceWithoutVAT = rs.getBigDecimal(6);
                        var priceWithVAT = priceWithoutVAT.multiply(BigDecimal.valueOf(1.19)); // TODO: Proper handling of VAT required
                        String customerName = rs.getString(8);
                        boolean isFBE = rs.getBoolean(15);
                        var row = List.of(
                                // Group 0
                                Stream.of(toLocalDateTime(rs.getTimestamp(1)).format(Conversions.isoLikeLocalDateTime), // creation date
                                        rs.getString(2), // id
                                        Conversions.statusToString(rs.getInt(3)) // status
                                ).map(Object.class::cast).toList(),
                                // Group 1
                                Stream.of(rs.getString(4), // Product name
                                        rs.getInt(5), // quantity
                                        priceWithoutVAT.setScale(2, HALF_EVEN), priceWithVAT.setScale(2, HALF_EVEN)).map(Object.class::cast).toList(),
                                // Group 2
                                Stream.of(rs.getString(7), // deliver mode
                                        customerName, // customerName
                                        customerName, // customer shipment name
                                        rs.getString(9) // customer shipping phone
                                ).map(Object.class::cast).toList(),
                                // Group 3
                                Stream.of(rs.getString(10), // customer billing name
                                        rs.getString(11) // customer billing phone
                                ).map(Object.class::cast).toList(),
                                // Group 4
                                Stream.of(rs.getString(12), // company code
                                        rs.getString(13) // observation
                                ).map(Object.class::cast).toList(),
                                // Group 5
                                Stream.of(rs.getString(14), // company name
                                        Conversions.booleanToFBE(isFBE) // platform
                                ).map(Object.class::cast).toList(),
                                // Group 6
                                Stream.of(isFBE, isFBE, isFBE, false).map(Object.class::cast).toList(),
                                // Group 7
                                Stream.of(rs.getString(16)).map(Object.class::cast).toList());
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    /**
     * Read database information and prepare them for inclusion in the spreadsheet.
     *
     * @return list of rows containing a list of cell groups. Each cell group is a list of cells.
     * @throws SQLException on database error
     */
    public List<List<Object>> readForComparisonApp() throws SQLException {
        return database.readTX(db -> {
            List<List<Object>> rows = new ArrayList<>();
            try (var s = db.prepareStatement(
                    //language=sql
                    """
                            SELECT
                              o.id,
                              v.vendor_name,
                              v.isFBE,
                              p.part_number_key,
                              o.date,
                              o.status,
                              o.type,
                              pi.name,
                              p.quantity,
                              p.sale_price,
                              o.delivery_mode,
                              c.name,
                              c.shipping_phone,
                              c.billing_name,
                              c.billing_phone,
                              c.code,
                              o.observation,
                              pi.message_keyword
                            FROM emag_order as o
                            LEFT JOIN customer as c
                            ON o.customer_id = c.id
                            LEFT JOIN vendor as v
                            ON o.vendor_id = v.id
                            LEFT JOIN product_in_order as p
                            ON p.order_id = o.id AND p.vendor_id = o.vendor_id
                            LEFT JOIN product as pi
                            ON p.part_number_key = pi.emag_pnk
                            """)) {
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
// 2024-10-17 16:49:51
                        var priceWithoutVAT = rs.getBigDecimal(6);
                        var priceWithVAT = priceWithoutVAT.multiply(BigDecimal.valueOf(1.19)); // TODO: Proper handling of VAT required
                        String customerName = rs.getString(8);
                        var row = Arrays.<Object>asList(rs.getString(1), // id
                                rs.getString(2), // company name
                                rs.getBoolean(3), // platform
                                rs.getString(4), // PNK
                                toLocalDateTime(rs.getTimestamp(5)), // creation date
                                rs.getInt(6), // status
                                rs.getInt(7) // type
                                        /*
                                        // Group 0
                                        Stream.of(
                                                toLocalDateTime(rs.getTimestamp(1)).format(formatDate), // creation date
                                                statusToString(rs.getInt(3)) // status
                                        ).map(Object.class::cast).toList(),
                                        // Group 1
                                        Stream.of(
                                                rs.getString(4),
                                                rs.getInt(5), // quantity
                                                priceWithoutVAT.setScale(2, HALF_EVEN),
                                                priceWithVAT.setScale(2, HALF_EVEN)
                                        ).map(Object.class::cast).toList(),
                                        // Group 2
                                        Stream.of(
                                                rs.getString(7), // deliver mode
                                                customerName, // customerName
                                                customerName, // customer shipment name
                                                rs.getString(9) // customer shipping phone
                                        ).map(Object.class::cast).toList(),
                                        // Group 3
                                        Stream.of(
                                                rs.getString(10), // customer billing name
                                                rs.getString(11) // customer billing phone
                                        ).map(Object.class::cast).toList(),
                                        // Group 4
                                        Stream.of(
                                                rs.getString(12), // company code
                                                rs.getString(13) // observation
                                        ).map(Object.class::cast).toList(),
                                        // Group 5
                                        Stream.of(
                                        ).map(Object.class::cast).toList(),
                                        // Group 6
                                        Stream.of(
                                                rs.getBoolean(2),
                                                rs.getBoolean(2),
                                                rs.getBoolean(2),
                                                false
                                        ).map(Object.class::cast).toList(),
                                        // Group 7
                                        Stream.of(
                                                rs.getString(16)
                                        ).map(Object.class::cast).toList()

                                        */);
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    private String modelToString(String pnk) {
        return "PNK:" + pnk;
    }

    /**
     * Map the vendor name found in the eMAG order to a UUID we can use in the database.
     * It will add an entry whenever a new vendor name appears.
     *
     * @param name of vendor.
     * @return UUID associated with the vendor.
     * @throws SQLException
     */
    private UUID getOrAddVendor(String name) throws SQLException {
        return database.writeTX(db -> {
            UUID id = selectVendorIdByName(db, name);
            if (id == null) {
                id = UUID.randomUUID();
                try (var s = db.prepareStatement("INSERT INTO vendor (id, vendor_name, isfbe) VALUES (?,?,?)")) {
                    s.setObject(1, id);
                    s.setString(2, name);
                    s.setBoolean(3, name.contains("FBE")); //TODO: Is this ok?
                    s.executeUpdate();
                }
            }
            return id;
        });
    }

    private static @Nullable UUID selectVendorIdByName(Connection db, String name) throws SQLException {
        UUID id = null;
        try (var s = db.prepareStatement("SELECT id FROM vendor WHERE vendor_name=?")) {
            s.setString(1, name);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    id = rs.getObject(1, UUID.class);
                }
                if (rs.next()) {
                    logger.log(SEVERE, "More than one vendor with the same name ???");
                }
            }
        }
        return id;
    }

    private static int insertAttachment(Connection db, Attachment attachment, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO attachment (order_id, vendor_id, name, url, type, force_download, visibility) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(order_id, vendor_id, url) DO NOTHING")) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            s.setString(3, attachment.name());
            s.setString(4, attachment.url());
            s.setInt(5, attachment.type());
            s.setInt(6, attachment.force_download());
            s.setString(7, attachment.visibility());
            return s.executeUpdate();
        }
    }

    private List<Attachment> selectAttachmentsByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        String query = "SELECT name, url, type, force_download, visibility FROM attachment WHERE order_id = ? AND vendor_id = ?";
        var list = new ArrayList<Attachment>();
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new Attachment(rs.getString("name"), rs.getString("url"), rs.getInt("type"), rs.getInt("force_download"), rs.getString("visibility")));
                }
            }
        }
        return list;
    }


/*TODO
    private static Attachment selectAttachment(Connection db, String orderId, UUID vendorId, String url) throws SQLException {
        String query = "SELECT name, url, type, force_download, visibility FROM attachment WHERE order_id = ? AND vendor_id = ? AND url = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            s.setString(3, url);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new Attachment(
                            rs.getString("name"),
                            rs.getString("url"),
                            rs.getInt("type"),
                            rs.getInt("force_download"),
                            rs.getString("visibility")
                    );
                } else {
                    return null; // or throw an exception if not found
                }
            }
        }
    }
*/

    private static int updateAttachment(Connection db, Attachment attachment, String orderId, UUID vendorId, String url) throws SQLException {
        String query = "UPDATE attachment SET name = ?, type = ?, force_download = ?, visibility = ? WHERE order_id = ? AND vendor_id = ? AND url = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, attachment.name());
            s.setInt(2, attachment.type());
            s.setInt(3, attachment.force_download());
            s.setString(4, attachment.visibility());
            s.setString(5, orderId);
            s.setObject(6, vendorId);
            s.setString(7, url);
            return s.executeUpdate();
        }
    }

    private static int insertFlag(Connection db, Flag flag, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO flag (vendor_id, order_id, flag, value) VALUES (?, ?, ?, ?) ON CONFLICT(order_id, vendor_id, flag) DO NOTHING")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            s.setString(3, flag.flag());
            s.setString(4, flag.value());
            return s.executeUpdate();
        }
    }

    private List<Flag> selectFlagsByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        var list = new ArrayList<Flag>();
        String query = "SELECT vendor_id, order_id, flag, value FROM flag WHERE order_id = ? AND vendor_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
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

    private static int updateFlag(Connection db, Flag flag, String orderId, UUID vendorId) throws SQLException {
        String query = "UPDATE flag SET value = ? WHERE order_id = ? AND vendor_id = ? AND flag = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, flag.value());
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setString(4, flag.flag());
            return s.executeUpdate();
        }
    }

    private static int insertEnforcedVendorCourierAccount(Connection db, String enforcedVendorCourierAccount, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO enforced_vendor_courier_account (vendor_id, order_id, courier) VALUES (?, ?, ?) ON CONFLICT(order_id, vendor_id, courier)")) {
            s.setObject(1, vendorId);
            s.setString(2, orderId);
            s.setString(3, enforcedVendorCourierAccount);
            return s.executeUpdate();
        }
    }

    private static List<String> selectEnforcedVendorCourierAccountsByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        var list = new ArrayList<String>();
        String query = "SELECT courier FROM enforced_vendor_courier_account WHERE order_id = ? AND vendor_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("courier"));
                }
            }
        }
        return list;
    }

    private static int updateEnforcedVendorCourierAccount(Connection db, String enforcedVendorCourierAccount, String orderId, UUID vendorId) throws SQLException {
        String query = "UPDATE enforced_vendor_courier_account SET courier = ? WHERE order_id = ? AND vendor_id = ? AND courier = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, enforcedVendorCourierAccount);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setString(4, enforcedVendorCourierAccount); // Assuming you want to update the same courier
            return s.executeUpdate();
        }
    }

    private static int insertVoucher(Connection db, Voucher voucher, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO voucher (voucher_id, order_id, vendor_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucher.voucher_id());
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setString(4, voucher.modified());
            s.setString(5, voucher.created());
            s.setInt(6, voucher.status());
            s.setBigDecimal(7, voucher.sale_price_vat());
            s.setBigDecimal(8, voucher.sale_price());
            s.setString(9, voucher.voucher_name());
            s.setBigDecimal(10, voucher.vat());
            s.setString(11, voucher.issue_date());
            s.setString(12, voucher.id());
            return s.executeUpdate();
        }
    }

    private List<Voucher> selectVouchersByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        var list = new ArrayList<Voucher>();
        String query = """
                SELECT voucher_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date, id
                FROM voucher
                WHERE order_id = ? AND vendor_id = ?
                """;
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
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

    /*TODO
        private static Voucher selectVoucher(Connection db, int voucherId) throws SQLException {
        }
    */
    private static int updateVoucher(Connection db, Voucher voucher, String orderId, UUID vendorId) throws SQLException {
        String query = "UPDATE voucher SET order_id = ?, vendor_id = ?, modified = ?, created = ?, status = ?, sale_price_vat = ?, sale_price = ?, voucher_name = ?, vat = ?, issue_date = ?, id = ? WHERE voucher_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            s.setString(3, voucher.modified());
            s.setString(4, voucher.created());
            s.setInt(5, voucher.status());
            s.setBigDecimal(6, voucher.sale_price_vat());
            s.setBigDecimal(7, voucher.sale_price());
            s.setString(8, voucher.voucher_name());
            s.setBigDecimal(9, voucher.vat());
            s.setString(10, voucher.issue_date());
            s.setString(11, voucher.id());
            s.setInt(12, voucher.voucher_id());
            return s.executeUpdate();
        }
    }

    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO voucher_split (voucher_id, order_id, vendor_id, value, vat_value) VALUES (?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id());
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setBigDecimal(4, voucherSplit.value());
            s.setBigDecimal(5, voucherSplit.vat_value());
            return s.executeUpdate();
        }
    }

    private List<VoucherSplit> selectVoucherSplitsByProductId(Connection db, int productId) throws SQLException {
        var list = new ArrayList<VoucherSplit>();
        try (var s = db.prepareStatement("""
                SELECT voucher_id, value, vat_value, vat, offered_by FROM voucher_split WHERE product_id = ?
                """)) {
            s.setInt(1, productId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new VoucherSplit(rs.getObject("voucher_id", Integer.class),
                            rs.getBigDecimal("value"),
                            rs.getBigDecimal("vat_value"),
                            rs.getString("vat"),
                            rs.getString("offered_by")));
                }
            }
        }
        return list;
    }

    private List<VoucherSplit> selectVoucherSplitsByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        var list = new ArrayList<VoucherSplit>();
        try (var s = db.prepareStatement("""
                SELECT voucher_id, value, vat_value, vat, offered_by FROM voucher_split WHERE order_id = ? AND vendor_id = ?
                """)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new VoucherSplit(rs.getObject("voucher_id", Integer.class),
                            rs.getBigDecimal("value"),
                            rs.getBigDecimal("vat_value"),
                            rs.getString("vat"),
                            rs.getString("offered_by")));
                }
            }
        }
        return list;
    }


    /*TODO
        private static int updateVoucherSplit(Connection conn, VoucherSplit voucherSplit) throws SQLException {
            String query = "UPDATE voucher_split SET order_id = ?, vendor_id = ?, value = ?, vat_value = ? WHERE voucher_id = ?";
            try (var s = conn.prepareStatement(query)) {
                s.setString(1, voucherSplit.order_id());
                s.setObject(2, voucherSplit.vendor_id());
                s.setBigDecimal(3, voucherSplit.value());
                s.setBigDecimal(4, voucherSplit.vat_value());
                s.setInt(5, voucherSplit.voucher_id());
                return s.executeUpdate();
            }
        }
        */
    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId, int productId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO voucher_split (voucher_id, order_id, vendor_id, product_id, value, vat_value, vat, offered_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id());
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setInt(4, productId);
            s.setBigDecimal(5, voucherSplit.value());
            s.setBigDecimal(6, voucherSplit.vat_value());
            s.setString(7, voucherSplit.vat());
            s.setString(8, voucherSplit.offered_by());
            return s.executeUpdate();
        }
    }

/*TODO
    private static VoucherSplit selectVoucherSplit(Connection conn, int voucherId) throws SQLException {
        String query = "SELECT voucher_id, order_id, vendor_id, product_id, value, vat_value, vat, offered_by FROM voucher_split WHERE voucher_id = ?";
        try (var s = conn.prepareStatement(query)) {
            s.setInt(1, voucherId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new VoucherSplit(
                            rs.getInt("voucher_id"),
                            rs.getString("order_id"),
                            rs.getObject("vendor_id", UUID.class),
                            rs.getInt("product_id"),
                            rs.getBigDecimal("value"),
                            rs.getBigDecimal("vat_value"),
                            rs.getString("vat"),
                            rs.getString("offered_by")
                    );
                } else {
                    return null; // or throw an exception if preferred
                }
            }
        }
    }
*/


    private static int updateVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId, int productId) throws SQLException {
        String query = "UPDATE voucher_split SET order_id = ?, vendor_id = ?, product_id = ?, value = ?, vat_value = ?, vat = ?, offered_by = ? WHERE voucher_id = ?";
        try (var s = conn.prepareStatement(query)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            s.setInt(3, productId);
            s.setBigDecimal(4, voucherSplit.value());
            s.setBigDecimal(5, voucherSplit.vat_value());
            s.setString(6, voucherSplit.vat());
            s.setString(7, voucherSplit.offered_by());
            s.setInt(8, voucherSplit.voucher_id());
            return s.executeUpdate();
        }
    }

    private static int insertProduct(Connection db, Product product, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO product_in_order (id, order_id, vendor_id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING")) {
            s.setInt(1, product.id());
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setInt(4, product.product_id());
            s.setInt(5, product.mkt_id());
            s.setString(6, product.name());
            s.setInt(7, product.status());
            s.setString(8, product.ext_part_number());
            s.setString(9, product.part_number());
            s.setString(10, product.part_number_key());
            s.setString(11, product.currency());
            s.setString(12, product.vat());
            s.setInt(13, product.retained_amount());
            s.setInt(14, product.quantity());
            s.setInt(15, product.initial_qty());
            s.setInt(16, product.storno_qty());
            s.setInt(17, product.reversible_vat_charging());
            s.setBigDecimal(18, product.sale_price());
            s.setBigDecimal(19, product.original_price());
            s.setTimestamp(20, toTimestamp(product.created()));
            s.setTimestamp(21, toTimestamp(product.modified()));
            s.setString(22, String.join("\n", product.details()));
            s.setString(23, String.join("\n", product.recycle_warranties()));
            return s.executeUpdate();
        }
    }

    /**
     * Read all product id belonging to an order.
     *
     * @param db database
     * @param orderId id of the order
     * @param vendorId vendor
     * @return list of product IDs.
     * @throws SQLException on database error
     */
    private List<Integer> selectProductIdByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        var list = new ArrayList<Integer>();
        try (var s = db.prepareStatement("""
                SELECT id
                FROM product_in_order
                WHERE order_id = ? AND vendor_id = ?
                """)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("id"));
                }
            }
        }
        return list;

    }

    private Product selectProduct(Connection db, int productId, List<VoucherSplit> product_voucher_splits, List<Attachment> attachments) throws SQLException {
        Product product = null;
        try (var s = db.prepareStatement("""
                SELECT id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties
                FROM product_in_order
                WHERE id = ?
                """)) {
            s.setInt(1, productId);
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
                            attachments);
                }
                if (rs.next()) {
                    throw new RuntimeException("More than one product with same id " + productId);
                }
            }
        }
        return product;
    }

/*TODO
    private static Product selectProduct(Connection db, int id, String orderId, UUID vendorId) throws SQLException {
        String query = "SELECT id, order_id, vendor_id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties FROM product_in_order WHERE id = ? AND order_id = ? AND vendor_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new Product(
                            rs.getInt("id"),
                            rs.getString("order_id"),
                            rs.getObject("vendor_id", UUID.class),
                            rs.getInt("product_id"),
                            rs.getInt("mkt_id"),
                            rs.getString("name"),
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
                            List.of(rs.getString("details").split("\\n")),
                            List.of(rs.getString("recycle_warranties").split("\\n"))
                    );
                } else {
                    return null;
                }
            }
        }
    }
*/

    private static int updateProduct(Connection db, Product product, String orderId, UUID vendorId) throws SQLException {
        String query = "UPDATE product_in_order SET product_id = ?, mkt_id = ?, name = ?, status = ?, ext_part_number = ?, part_number = ?, part_number_key = ?, currency = ?, vat = ?, retained_amount = ?, quantity = ?, initial_qty = ?, storno_qty = ?, reversible_vat_charging = ?, sale_price = ?, original_price = ?, created = ?, modified = ?, details = ?, recycle_warranties = ? WHERE id = ? AND order_id = ? AND vendor_id = ?";
        try (var s = db.prepareStatement(query)) {
            s.setInt(1, product.product_id());
            s.setInt(2, product.mkt_id());
            s.setString(3, product.name());
            s.setInt(4, product.status());
            s.setString(5, product.ext_part_number());
            s.setString(6, product.part_number());
            s.setString(7, product.part_number_key());
            s.setString(8, product.currency());
            s.setString(9, product.vat());
            s.setInt(10, product.retained_amount());
            s.setInt(11, product.quantity());
            s.setInt(12, product.initial_qty());
            s.setInt(13, product.storno_qty());
            s.setInt(14, product.reversible_vat_charging());
            s.setBigDecimal(15, product.sale_price());
            s.setBigDecimal(16, product.original_price());
            s.setTimestamp(17, toTimestamp(product.created()));
            s.setTimestamp(18, toTimestamp(product.modified()));
            s.setString(19, String.join("\n", product.details()));
            s.setString(20, String.join("\n", product.recycle_warranties()));
            s.setInt(21, product.id());
            s.setString(22, orderId);
            s.setObject(23, vendorId);
            return s.executeUpdate();
        }
    }

    private static int insertCustomer(Connection db, Customer c) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO customer (
                id, mkt_id, name, email, company, gender, code, registration_number, bank, iban, fax, legal_entity,
                is_vat_payer, phone_1, phone_2, phone_3, billing_name, billing_phone, billing_country, billing_suburb,
                billing_city, billing_locality_id, billing_street, billing_postal_code, liable_person, shipping_country,
                shipping_suburb, shipping_city, shipping_locality_id, shipping_street,
                shipping_postal_code, shipping_contact, shipping_phone, created, modified
                )
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, c.id());
            s.setInt(2, c.mkt_id());
            s.setString(3, c.name());
            s.setString(4, c.email());
            s.setString(5, c.company());
            s.setString(6, c.gender());
            s.setString(7, c.code());
            s.setString(8, c.registration_number());
            s.setString(9, c.bank());
            s.setString(10, c.iban());
            s.setString(11, c.fax());
            s.setInt(12, c.legal_entity());
            s.setInt(13, c.is_vat_payer());
            s.setString(14, c.phone_1());
            s.setString(15, c.phone_2());
            s.setString(16, c.phone_3());
            s.setString(17, c.billing_name());
            s.setString(18, c.billing_phone());
            s.setString(19, c.billing_country());
            s.setString(20, c.billing_suburb());
            s.setString(21, c.billing_city());
            s.setString(22, c.billing_locality_id());
            s.setString(23, c.billing_street());
            s.setString(24, c.billing_postal_code());
            s.setString(25, c.liable_person());
            s.setString(26, c.shipping_country());
            s.setString(27, c.shipping_suburb());
            s.setString(28, c.shipping_city());
            s.setString(29, c.shipping_locality_id());
            s.setString(30, c.shipping_street());
            s.setString(31, c.shipping_postal_code());
            s.setString(32, c.shipping_contact());
            s.setString(33, c.shipping_phone());
            s.setTimestamp(34, toTimestamp(c.created()));
            s.setTimestamp(35, toTimestamp(c.modified()));
            return s.executeUpdate();
        }
    }

    private static Customer selectCustomer(Connection db, int customerId) throws SQLException {
        Customer customer = null;
        try (var s = db.prepareStatement("SELECT * FROM customer WHERE id = ?")) {
            s.setInt(1, customerId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    customer = new Customer(customerId, rs.getInt("mkt_id"), rs.getString("name"), rs.getString("email"), rs.getString("company"), rs.getString("gender"), rs.getString("code"), rs.getString("registration_number"), rs.getString("bank"), rs.getString("iban"), rs.getString("fax"), rs.getInt("legal_entity"), rs.getInt("is_vat_payer"), rs.getString("phone_1"), rs.getString("phone_2"), rs.getString("phone_3"), rs.getString("billing_name"), rs.getString("billing_phone"), rs.getString("billing_country"), rs.getString("billing_suburb"), rs.getString("billing_city"), rs.getString("billing_locality_id"), rs.getString("billing_street"), rs.getString("billing_postal_code"), rs.getString("liable_person"), rs.getString("shipping_country"), rs.getString("shipping_suburb"), rs.getString("shipping_city"), rs.getString("shipping_locality_id"), rs.getString("shipping_street"), rs.getString("shipping_postal_code"), rs.getString("shipping_contact"), rs.getString("shipping_phone"), toLocalDateTime(rs.getTimestamp("created")), toLocalDateTime(rs.getTimestamp("modified")));
                }
            }
        }
        return customer;
    }

    private static Customer selectCustomerByOrderId(Connection db, String orderId, UUID vendorId) throws SQLException {
        Customer customer = null;
        try (var s = db.prepareStatement("""
                SELECT * FROM customer AS c
                INNER JOIN emag_order as o
                ON c.id = o.customer_id
                WHERE o.id = ? AND o.vendor_id = ?
                """)) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    customer = new Customer(rs.getInt("id"), rs.getInt("mkt_id"), rs.getString("name"), rs.getString("email"), rs.getString("company"), rs.getString("gender"), rs.getString("code"), rs.getString("registration_number"), rs.getString("bank"), rs.getString("iban"), rs.getString("fax"), rs.getInt("legal_entity"), rs.getInt("is_vat_payer"), rs.getString("phone_1"), rs.getString("phone_2"), rs.getString("phone_3"), rs.getString("billing_name"), rs.getString("billing_phone"), rs.getString("billing_country"), rs.getString("billing_suburb"), rs.getString("billing_city"), rs.getString("billing_locality_id"), rs.getString("billing_street"), rs.getString("billing_postal_code"), rs.getString("liable_person"), rs.getString("shipping_country"), rs.getString("shipping_suburb"), rs.getString("shipping_city"), rs.getString("shipping_locality_id"), rs.getString("shipping_street"), rs.getString("shipping_postal_code"), rs.getString("shipping_contact"), rs.getString("shipping_phone"), toLocalDateTime(rs.getTimestamp("created")), toLocalDateTime(rs.getTimestamp("modified")));
                }
            }
        }
        return customer;
    }


    private static int updateCustomer(Connection db, Customer c) throws SQLException {
        try (var s = db.prepareStatement("UPDATE customer SET mkt_id = ?, name = ?, email = ?, company = ?, gender = ?, code = ?, registration_number = ?, bank = ?, iban = ?, fax = ?, legal_entity = ?, is_vat_payer = ?, phone_1 = ?, phone_2 = ?, phone_3 = ?, billing_name = ?, billing_phone = ?, billing_country = ?, billing_suburb = ?, billing_city = ?, billing_locality_id = ?, billing_street = ?, billing_postal_code = ?, liable_person = ?, shipping_country = ?, shipping_suburb = ?, shipping_city = ?, shipping_locality_id = ?, shipping_street = ?, shipping_postal_code = ?, shipping_contact = ?, shipping_phone = ?, modified = ? WHERE id = ?")) {
            s.setInt(1, c.mkt_id());
            s.setString(2, c.name());
            s.setString(3, c.email());
            s.setString(4, c.company());
            s.setString(5, c.gender());
            s.setString(6, c.code());
            s.setString(7, c.registration_number());
            s.setString(8, c.bank());
            s.setString(9, c.iban());
            s.setString(10, c.fax());
            s.setInt(11, c.legal_entity());
            s.setInt(12, c.is_vat_payer());
            s.setString(13, c.phone_1());
            s.setString(14, c.phone_2());
            s.setString(15, c.phone_3());
            s.setString(16, c.billing_name());
            s.setString(17, c.billing_phone());
            s.setString(18, c.billing_country());
            s.setString(19, c.billing_suburb());
            s.setString(20, c.billing_city());
            s.setString(21, c.billing_locality_id());
            s.setString(22, c.billing_street());
            s.setString(23, c.billing_postal_code());
            s.setString(24, c.liable_person());
            s.setString(25, c.shipping_country());
            s.setString(26, c.shipping_suburb());
            s.setString(27, c.shipping_city());
            s.setString(28, c.shipping_locality_id());
            s.setString(29, c.shipping_street());
            s.setString(30, c.shipping_postal_code());
            s.setString(31, c.shipping_contact());
            s.setString(32, c.shipping_phone());
            s.setTimestamp(33, toTimestamp(c.modified()));
            s.setInt(34, c.id());
            return s.executeUpdate();
        }
    }

    private static int insertLockerDetails(Connection db, LockerDetails ld) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO locker_details (locker_id, locker_name, locker_delivery_eligible, courier_external_office_id) VALUES (?,?,?,?) ON CONFLICT(locker_id) DO NOTHING")) {
            s.setString(1, ld.locker_id());
            s.setString(2, ld.locker_name());
            s.setInt(3, ld.locker_delivery_eligible());
            s.setString(4, ld.courier_external_office_id());
            return s.executeUpdate();
        }
    }

    private static LockerDetails selectLockerDetails(Connection db, String lockerId) throws SQLException {
        LockerDetails lockerDetails = null;
        try (var s = db.prepareStatement("SELECT * FROM locker_details WHERE locker_id = ?")) {
            s.setString(1, lockerId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    lockerDetails = new LockerDetails(rs.getString("locker_id"), rs.getString("locker_name"), rs.getInt("locker_delivery_eligible"), rs.getString("courier_external_office_id"));
                }
            }
        }
        return lockerDetails;
    }

    private static int updateLockerDetails(Connection db, LockerDetails ld) throws SQLException {
        try (var s = db.prepareStatement("UPDATE locker_details SET locker_name = ?, locker_delivery_eligible = ?, courier_external_office_id = ? WHERE locker_id = ?")) {
            s.setString(1, ld.locker_name());
            s.setInt(2, ld.locker_delivery_eligible());
            s.setString(3, ld.courier_external_office_id());
            s.setString(4, ld.locker_id());
            return s.executeUpdate();
        }
    }

    private static int insertOrder(Connection db, OrderResult or, UUID vendorId) throws SQLException {
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id, vendor_id) DO NOTHING
                """)) {
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
            return s.executeUpdate();
        }
    }

    private static OrderResult selectOrder(Connection db,
                                           String orderId,
                                           UUID vendorId,
                                           String vendorName,
                                           Customer customer,
                                           List<VoucherSplit> shipping_tax_voucher_split,
                                           List<Product> products,
                                           List<Attachment> attachments,
                                           List<Voucher> vouchers,
                                           List<String> enforcedVendorCourierAccounts,
                                           List<Flag> flags) throws SQLException {
        OrderResult order = null;
        try (var s = db.prepareStatement("SELECT * FROM emag_order WHERE id = ? AND vendor_id = ?")) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    order = new OrderResult(
                            vendorName, orderId, rs.getInt("status"), rs.getInt("is_complete"), rs.getInt("type"), rs.getString("payment_mode"),
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

    private static int updateOrder(Connection db, OrderResult or, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE emag_order SET
                    status = ?,
                    is_complete = ?,
                    type = ?,
                    payment_mode = ?,
                    payment_mode_id = ?,
                    delivery_payment_mode = ?,
                    delivery_mode = ?,
                    observation = ?,
                    details_id = ?,
                    date = ?,
                    payment_status = ?,
                    cashed_co = ?,
                    cashed_cod = ?,
                    shipping_tax = ?,
                    customer_id = ?,
                    is_storno = ?,
                    cancellation_reason = ?,
                    refunded_amount = ?,
                    refund_status = ?,
                    maximum_date_for_shipment = ?,
                    finalization_date = ?,
                    parent_id = ?,
                    detailed_payment_method = ?,
                    proforms = ?,
                    cancellation_request = ?,
                    has_editable_products = ?,
                    late_shipment = ?,
                    emag_club = ?,
                    weekend_delivery = ?,
                    modified = ?
                WHERE id = ? AND vendor_id = ?
                """)) {
            s.setInt(1, or.status());
            s.setInt(2, or.is_complete());
            s.setInt(3, or.type());
            s.setString(4, or.payment_mode());
            s.setInt(5, or.payment_mode_id());
            s.setString(6, or.delivery_payment_mode());
            s.setString(7, or.delivery_mode());
            s.setString(8, or.observation());
            s.setString(9, or.details().locker_id());
            s.setTimestamp(10, toTimestamp(or.date()));
            s.setInt(11, or.payment_status());
            s.setBigDecimal(12, or.cashed_co());
            s.setBigDecimal(13, or.cashed_cod());
            s.setBigDecimal(14, or.shipping_tax());
            s.setObject(15, or.customer() != null ? or.customer().id() : null);
            s.setBoolean(16, or.is_storno());
            s.setObject(17, or.reason_cancellation() == null ? null : or.reason_cancellation().id());
            s.setBigDecimal(18, or.refunded_amount());
            s.setString(19, or.refund_status());
            s.setTimestamp(20, toTimestamp(or.maximum_date_for_shipment()));
            s.setTimestamp(21, toTimestamp(or.finalization_date()));
            s.setString(22, or.parent_id());
            s.setString(23, or.detailed_payment_method());
            s.setString(24, String.join("", or.proforms()));
            s.setString(25, or.cancellation_request());
            s.setInt(26, or.has_editable_products());
            s.setObject(27, or.late_shipment());
            s.setInt(28, or.emag_club());
            s.setInt(29, or.weekend_delivery());
            s.setTimestamp(30, toTimestamp(or.modified()));
            s.setString(31, or.id());
            s.setObject(32, vendorId);
            return s.executeUpdate();
        }
    }

    private static int insertAWB(Connection db, AWB awb, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO awb (
                reservation_id,
                emag_id
                ) VALUES (?, ?) ON CONFLICT(reservation_id) DO NOTHING""")) {
            s.setInt(1, awb.reservation_id());
            s.setInt(2, emagId);
            return s.executeUpdate();
        }
    }

/*TODO
    private static AWB selectAWB(Connection db, int reservationId) throws SQLException {
        AWB awb = null;
        try (var s = db.prepareStatement("SELECT * FROM awb WHERE reservation_id = ?")) {
            s.setInt(1, reservationId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    awb = new AWB(
                            rs.getInt("reservation_id"),
                            rs.getInt("emag_id")
                    );
                }
            }
        }
        return awb;
    }
*/

    private static int updateAWB(Connection db, AWB awb, int emagId) throws SQLException {
        try (var s = db.prepareStatement("UPDATE awb SET emag_id = ? WHERE reservation_id = ?")) {
            s.setInt(1, emagId);
            s.setInt(2, awb.reservation_id());
            return s.executeUpdate();
        }
    }

    private static int insertRequestHistory(Connection db, RequestHistory requestHistory, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO request_history (
                emag_id,
                id,
                req_user,
                action,
                action_type,
                source,
                date
                ) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, emagId);
            s.setLong(2,requestHistory.id());
            s.setString(3,requestHistory.user());
            s.setString(4,requestHistory.action());
            s.setString(5,requestHistory.action_type());
            s.setString(6,requestHistory.source());
            s.setTimestamp(7,toTimestamp(requestHistory.date()));
            return s.executeUpdate();
        }
    }

    private static int insertStatusHistory(Connection db, StatusHistory statusHistory, int emagId, UUID uuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_history (uuid, code, event_date, emag_id) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO NOTHING")) {
            s.setObject(1, uuid);
            s.setString(2, statusHistory.code());
            s.setTimestamp(3, statusHistory.event_date() == null ? null : toTimestamp(statusHistory.event_date()));
            s.setInt(4, emagId);
            return s.executeUpdate();
        }
    }

/*TODO
    private static StatusHistory selectStatusHistory(Connection db, UUID uuid) throws SQLException {
        try (var s = db.prepareStatement("SELECT uuid, code, event_date, emag_id FROM status_history WHERE uuid = ?")) {
            s.setObject(1, uuid);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new StatusHistory(
                            rs.getString("code"),
                            toLocalDateTime(rs.getTimestamp("event_date"))
                    );
                }
                return null;
            }
        }
    }
*/

    private static int updateStatusHistory(Connection db, StatusHistory statusHistory, UUID uuid) throws SQLException {
        try (var s = db.prepareStatement("UPDATE status_history SET code = ?, event_date = ? WHERE uuid = ?")) {
            s.setString(1, statusHistory.code());
            s.setTimestamp(2, statusHistory.event_date() == null ? null : toTimestamp(statusHistory.event_date()));
            s.setObject(3, uuid);
            return s.executeUpdate();
        }
    }


    private static int insertStatusRequest(Connection db, StatusRequest statusRequest, UUID statusHistoryUuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_request (amount, created, refund_type, refund_status, rma_id, status_date, status_history_uuid) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setBigDecimal(1, statusRequest.amount());
            s.setTimestamp(2, toTimestamp(statusRequest.created()));
            s.setString(3, statusRequest.refund_type());
            s.setString(4, statusRequest.refund_status());
            s.setString(5, statusRequest.rma_id());
            s.setTimestamp(6, toTimestamp(statusRequest.status_date()));
            s.setObject(7, statusHistoryUuid);
            return s.executeUpdate();
        }
    }

    private static StatusRequest selectStatusRequest(Connection db, UUID statusHistoryUuid) throws SQLException {
        try (var s = db.prepareStatement("SELECT amount, created, refund_type, refund_status, rma_id, status_date, status_history_uuid FROM status_request WHERE status_history_uuid = ?")) {
            s.setObject(1, statusHistoryUuid);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new StatusRequest(rs.getBigDecimal("amount"), toLocalDateTime(rs.getTimestamp("created")), rs.getString("refund_type"), rs.getString("refund_status"), rs.getString("rma_id"), toLocalDateTime(rs.getTimestamp("status_date")));
                }
                return null;
            }
        }
    }

    private static int updateStatusRequest(Connection db, StatusRequest statusRequest, UUID statusHistoryUuid) throws SQLException {
        try (var s = db.prepareStatement("UPDATE status_request SET amount = ?, created = ?, refund_type = ?, refund_status = ?, rma_id = ?, status_date = ? WHERE status_history_uuid = ?")) {
            s.setBigDecimal(1, statusRequest.amount());
            s.setTimestamp(2, toTimestamp(statusRequest.created()));
            s.setString(3, statusRequest.refund_type());
            s.setString(4, statusRequest.refund_status());
            s.setString(5, statusRequest.rma_id());
            s.setTimestamp(6, toTimestamp(statusRequest.status_date()));
            s.setObject(7, statusHistoryUuid);
            return s.executeUpdate();
        }
    }

    private static int insertReturnedProduct(Connection db, ReturnedProduct returnedProduct, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO emag_returned_products (
                id,
                product_emag_id,
                product_id, quantity,
                product_name,
                return_reason,
                observations,
                diagnostic,
                reject_reason,
                retained_amount,
                emag_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, returnedProduct.id());
            s.setInt(2, returnedProduct.product_emag_id());
            s.setInt(3, returnedProduct.product_id());
            s.setInt(4, returnedProduct.quantity());
            s.setString(5, returnedProduct.product_name());
            s.setInt(6, returnedProduct.return_reason());
            s.setString(7, returnedProduct.observations());
            s.setString(8, returnedProduct.diagnostic());
            s.setObject(9, returnedProduct.reject_reason());
            s.setInt(10, returnedProduct.retained_amount());
            s.setInt(11, emagId);
            return s.executeUpdate();
        }
    }

/*TODO
    private static ReturnedProduct selectReturnedProduct(Connection db, int id) throws SQLException {
        try (var s = db.prepareStatement("""
            SELECT id, product_emag_id, product_id, quantity, product_name, return_reason, 
            observations, diagnostic, reject_reason, retained_amount, emag_id 
            FROM emag_returned_products WHERE id = ?""")) {
            s.setInt(1, id);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new ReturnedProduct(
                            rs.getInt("id"),
                            rs.getInt("product_emag_id"),
                            rs.getInt("product_id"),
                            rs.getInt("quantity"),
                            rs.getString("product_name"),
                            rs.getInt("return_reason"),
                            rs.getString("observations"),
                            rs.getString("diagnostic"),
                            rs.getString("reject_reason"),
                            rs.getInt("retained_amount")
                    );
                }
                return null;
            }
        }
    }
*/

    private static int updateReturnedProduct(Connection db, ReturnedProduct returnedProduct, int id, int emagId) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE emag_returned_products
                SET product_emag_id = ?, product_id = ?, quantity = ?, product_name = ?,
                return_reason = ?, observations = ?, diagnostic = ?, reject_reason = ?,
                retained_amount = ?, emag_id = ?
                WHERE id = ?""")) {
            s.setInt(1, returnedProduct.product_emag_id());
            s.setInt(2, returnedProduct.product_id());
            s.setInt(3, returnedProduct.quantity());
            s.setString(4, returnedProduct.product_name());
            s.setInt(5, returnedProduct.return_reason());
            s.setString(6, returnedProduct.observations());
            s.setString(7, returnedProduct.diagnostic());
            s.setObject(8, returnedProduct.reject_reason());
            s.setInt(9, returnedProduct.retained_amount());
            s.setInt(10, emagId);
            s.setInt(11, id);
            return s.executeUpdate();
        }
    }

    private static int insertRMAResult(Connection db, RMAResult rmaResult) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO rma_result (
                is_full_fbe,
                emag_id,
                return_parent_id,
                order_id,
                type,
                is_club,
                is_fast,
                customer_name,
                customer_company,
                customer_phone,
                pickup_country,
                pickup_suburb,
                pickup_city,
                pickup_address,
                pickup_zipcode,
                pickup_locality_id,
                pickup_method,
                customer_account_iban,
                customer_account_bank,
                customer_account_beneficiary,
                replacement_product_emag_id,
                replacement_product_id,
                replacement_product_name,
                replacement_product_quantity,
                observations,
                request_status,
                return_type,
                return_reason,
                date,
                maximum_finalization_date,
                first_pickup_date,
                estimated_product_pickup,
                estimated_product_reception,
                return_tax_value,
                swap,
                return_address_snapshot,
                locker_hash,
                locker_pin,
                locker_pin_interval_end,
                return_address_id,
                country,
                address_type,
                request_status_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(emag_id) DO NOTHING""")) {
            s.setInt(1, rmaResult.is_full_fbe());
            s.setInt(2, rmaResult.emag_id());
            s.setObject(3, rmaResult.return_parent_id());
            s.setString(4, rmaResult.order_id());
            s.setInt(5, rmaResult.type());
            s.setInt(6, rmaResult.is_club());
            s.setInt(7, rmaResult.is_fast());
            s.setString(8, rmaResult.customer_name());
            s.setString(9, rmaResult.customer_company());
            s.setString(10, rmaResult.customer_phone());
            s.setString(11, rmaResult.pickup_country());
            s.setString(12, rmaResult.pickup_suburb());
            s.setString(13, rmaResult.pickup_city());
            s.setString(14, rmaResult.pickup_address());
            s.setString(15, rmaResult.pickup_zipcode());
            s.setInt(16, rmaResult.pickup_locality_id());
            s.setInt(17, rmaResult.pickup_method());
            s.setString(18, rmaResult.customer_account_iban());
            s.setString(19, rmaResult.customer_account_bank());
            s.setString(20, rmaResult.customer_account_beneficiary());
            s.setObject(21, rmaResult.replacement_product_emag_id());
            s.setObject(22, rmaResult.replacement_product_id());
            s.setString(23, rmaResult.replacement_product_name());
            s.setObject(24, rmaResult.replacement_product_quantity());
            s.setString(25, rmaResult.observations());
            s.setInt(26, rmaResult.request_status());
            s.setInt(27, rmaResult.return_type());
            s.setInt(28, rmaResult.return_reason());
            s.setTimestamp(29, toTimestamp(rmaResult.date()));
            s.setTimestamp(30, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().maximum_finalization_date()));
            s.setTimestamp(31, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().first_pickup_date()));
            s.setTimestamp(32, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_pickup()));
            s.setTimestamp(33, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_reception()));
            s.setString(34, rmaResult.return_tax_value());
            s.setString(35, rmaResult.swap());
            s.setString(36, rmaResult.return_address_snapshot());
            s.setString(37, rmaResult.locker() == null ? null : rmaResult.locker().locker_hash());
            s.setString(38, rmaResult.locker() == null ? null : rmaResult.locker().locker_pin());
            s.setTimestamp(39, rmaResult.locker() == null ? null : toTimestamp(rmaResult.locker().locker_pin_interval_end()));
            s.setObject(40, rmaResult.return_address_id());
            s.setString(41, rmaResult.country());
            s.setString(42, rmaResult.address_type());
            s.setObject(43, rmaResult.request_status_reason());
            return s.executeUpdate();
        }
    }

    /*TODO
        private static RMAResult selectRMAResult(Connection db, int emagId) throws SQLException {
            try (var s = db.prepareStatement("""
                SELECT * FROM rma_result WHERE emag_id = ?""")) {
                s.setInt(1, emagId);
                try (var rs = s.executeQuery()) {
                    if (rs.next()) {
                        return new RMAResult(
                                rs.getInt("is_full_fbe"),
                                rs.getInt("emag_id"),
                                rs.getObject("return_parent_id", Integer.class),
                                rs.getString("order_id"),
                                rs.getInt("type"),
                                rs.getInt("is_club"),
                                rs.getInt("is_fast"),
                                rs.getString("customer_name"),
                                rs.getString("customer_company"),
                                rs.getString("customer_phone"),
                                rs.getString("pickup_country"),
                                rs.getString("pickup_suburb"),
                                rs.getString("pickup_city"),
                                rs.getString("pickup_address"),
                                rs.getString("pickup_zipcode"),
                                rs.getInt("pickup_locality_id"),
                                rs.getInt("pickup_method"),
                                rs.getString("customer_account_iban"),
                                rs.getString("customer_account_bank"),
                                rs.getString("customer_account_beneficiary"),
                                rs.getObject("replacement_product_emag_id", Integer.class),
                                rs.getObject("replacement_product_id", Integer.class),
                                rs.getString("replacement_product_name"),
                                rs.getObject("replacement_product_quantity", Integer.class),
                                rs.getString("observations"),
                                rs.getInt("request_status"),
                                rs.getInt("return_type"),
                                rs.getInt("return_reason"),
                                toLocalDateTime(rs.getTimestamp("date")),
                                new ExtraInfo(
                                        toLocalDateTime(rs.getTimestamp("maximum_finalization_date")),
                                        toLocalDateTime(rs.getTimestamp("first_pickup_date")),
                                        toLocalDateTime(rs.getTimestamp("estimated_product_pickup")),
                                        toLocalDateTime(rs.getTimestamp("estimated_product_reception"))
                                ),
                                rs.getString("return_tax_value"),
                                rs.getString("swap"),
                                rs.getString("return_address_snapshot"),
                                Arrays.asList(rs.getString("request_history").split("\n")),
                                rs.getString("locker_hash") != null ? new Locker(
                                        rs.getString("locker_hash"),
                                        rs.getString("locker_pin"),
                                        toLocalDateTime(rs.getTimestamp("locker_pin_interval_end"))
                                ) : null,
                                rs.getObject("return_address_id", Integer.class),
                                rs.getString("country"),
                                rs.getString("address_type"),
                                rs.getObject("request_status_reason", Integer.class)
                        );
                    }
                    return null;
                }
            }
        }
    */
    private static int updateRMAResult(Connection db, RMAResult rmaResult) throws SQLException {
        try (var s = db.prepareStatement("""
                UPDATE rma_result SET
                is_full_fbe = ?, return_parent_id = ?, order_id = ?, type = ?, is_club = ?, is_fast = ?,
                customer_name = ?, customer_company = ?, customer_phone = ?, pickup_country = ?,
                pickup_suburb = ?, pickup_city = ?, pickup_address = ?, pickup_zipcode = ?,
                pickup_locality_id = ?, pickup_method = ?, customer_account_iban = ?,
                customer_account_bank = ?, customer_account_beneficiary = ?,
                replacement_product_emag_id = ?, replacement_product_id = ?,
                replacement_product_name = ?, replacement_product_quantity = ?,
                observations = ?, request_status = ?, return_type = ?, return_reason = ?,
                date = ?, maximum_finalization_date = ?, first_pickup_date = ?,
                estimated_product_pickup = ?, estimated_product_reception = ?,
                return_tax_value = ?, swap = ?, return_address_snapshot = ?,
                request_history = ?, locker_hash = ?, locker_pin = ?,
                locker_pin_interval_end = ?, return_address_id = ?, country = ?,
                address_type = ?, request_status_reason = ?
                WHERE emag_id = ?""")) {
            s.setInt(1, rmaResult.is_full_fbe());
            s.setObject(2, rmaResult.return_parent_id());
            s.setString(3, rmaResult.order_id());
            s.setInt(4, rmaResult.type());
            s.setInt(5, rmaResult.is_club());
            s.setInt(6, rmaResult.is_fast());
            s.setString(7, rmaResult.customer_name());
            s.setString(8, rmaResult.customer_company());
            s.setString(9, rmaResult.customer_phone());
            s.setString(10, rmaResult.pickup_country());
            s.setString(11, rmaResult.pickup_suburb());
            s.setString(12, rmaResult.pickup_city());
            s.setString(13, rmaResult.pickup_address());
            s.setString(14, rmaResult.pickup_zipcode());
            s.setInt(15, rmaResult.pickup_locality_id());
            s.setInt(16, rmaResult.pickup_method());
            s.setString(17, rmaResult.customer_account_iban());
            s.setString(18, rmaResult.customer_account_bank());
            s.setString(19, rmaResult.customer_account_beneficiary());
            s.setObject(20, rmaResult.replacement_product_emag_id());
            s.setObject(21, rmaResult.replacement_product_id());
            s.setString(22, rmaResult.replacement_product_name());
            s.setObject(23, rmaResult.replacement_product_quantity());
            s.setString(24, rmaResult.observations());
            s.setInt(25, rmaResult.request_status());
            s.setInt(26, rmaResult.return_type());
            s.setInt(27, rmaResult.return_reason());
            s.setTimestamp(28, toTimestamp(rmaResult.date()));
            s.setTimestamp(29, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().maximum_finalization_date()));
            s.setTimestamp(30, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().first_pickup_date()));
            s.setTimestamp(31, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_pickup()));
            s.setTimestamp(32, rmaResult.extra_info() == null ? null : toTimestamp(rmaResult.extra_info().estimated_product_reception()));
            s.setString(33, rmaResult.return_tax_value());
            s.setString(34, rmaResult.swap());
            s.setString(35, rmaResult.return_address_snapshot());
            //s.setString(36, String.join("\n", rmaResult.request_history()));
            s.setString(37, rmaResult.locker() == null ? null : rmaResult.locker().locker_hash());
            s.setString(38, rmaResult.locker() == null ? null : rmaResult.locker().locker_pin());
            s.setTimestamp(39, rmaResult.locker() == null ? null : toTimestamp(rmaResult.locker().locker_pin_interval_end()));
            s.setObject(40, rmaResult.return_address_id());
            s.setString(41, rmaResult.country());
            s.setString(42, rmaResult.address_type());
            s.setObject(43, rmaResult.request_status_reason());
            s.setInt(44, rmaResult.emag_id());
            return s.executeUpdate();
        }
    }

    /**
     * Insert a product in the table that records our information about a product and associates our name and
     * category with the PNK used by emag.
     *
     * @param db database
     * @param productInfo record mapping to column
     * @return 1 or 0 depending on whether the insertion was successful or not.
     * @throws SQLException if anything bad happens.
     */
    private static int insertProduct(Connection db, ProductInfo productInfo) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO product (id, emag_pnk, name, category, message_keyword) VALUES (?, ?, ?, ?, ?) ON CONFLICT(emag_pnk) DO NOTHING")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, productInfo.pnk());
            s.setString(3, productInfo.name());
            s.setString(4, productInfo.category());
            s.setString(5, productInfo.messageKeyword());
            return s.executeUpdate();
        }
    }

    private static ProductInfo selectProduct(Connection db, String emagPnk) throws SQLException {
        try (var s = db.prepareStatement("SELECT id, emag_pnk, name, category, message_keyword FROM product WHERE emag_pnk = ?")) {
            s.setString(1, emagPnk);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new ProductInfo(rs.getString("emag_pnk"), rs.getString("name"), rs.getString("category"), rs.getString("message_keyword"));
                }
                return null;
            }
        }
    }

    private static int updateProduct(Connection db, ProductInfo productInfo, String emagPnk) throws SQLException {
        try (var s = db.prepareStatement("UPDATE product SET name = ?, category = ?, message_keyword = ? WHERE emag_pnk = ?")) {
            s.setString(1, productInfo.name());
            s.setString(2, productInfo.category());
            s.setString(3, productInfo.messageKeyword());
            s.setString(4, emagPnk);
            return s.executeUpdate();
        }
    }

    private static int insertEmagLog(Connection db, String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO emag_fetch_log (emag_login, date, fetch_time, error) VALUES (?, ?, ?, ?) ON CONFLICT(emag_login, date) DO NOTHING")) {
            s.setString(1, account);
            s.setDate(2, toDate(date));
            s.setTimestamp(3, toTimestamp(fetchTime));
            s.setString(4, error);
            return s.executeUpdate();
        }
    }

    private static int updateEmagLog(Connection db, String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_fetch_log SET fetch_time=?, error=? WHERE emag_login=? AND date=?")) {
            s.setTimestamp(1, toTimestamp(fetchTime));
            s.setString(2, error);
            s.setString(3, account);
            s.setDate(4, toDate(date));
            return s.executeUpdate();
        }
    }

    /**
     * Get all entries from emag_fetch_log which overlap with the given range.
     *
     * @param db database
     * @param account emag account
     * @param date of day needed.
     */
    private static EmagFetchLog getEmagLog(Connection db, String account, LocalDate date) throws SQLException {
        try (var s = db.prepareStatement("SELECT fetch_time, error FROM emag_fetch_log WHERE emag_login = ? AND date = ?")) {
            s.setString(1, account);
            s.setDate(2, toDate(date));
            try (var rs = s.executeQuery()) {
                EmagFetchLog fetchLog = null;
                if (rs.next()) {
                    fetchLog = new EmagFetchLog(account, date, toLocalDateTime(rs.getTimestamp(1)), rs.getString(2));
                }
                if (rs.next()) {
                    throw new RuntimeException("Unexpected second entry in emag_fetch_log for %s on %s".formatted(account, date));
                }
                return fetchLog;
            }
        }
    }

    private static Date toDate(LocalDate localDate) {
        return localDate == null ? null : Date.valueOf(localDate);
    }
    private static Timestamp toTimestamp(LocalDateTime localDateTime) {
        return localDateTime == null ? null : Timestamp.valueOf(localDateTime);
    }

    private static Timestamp toTimestamp(LocalDate localDate) {
        return localDate == null ? null : Timestamp.valueOf(localDate.atStartOfDay());
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
