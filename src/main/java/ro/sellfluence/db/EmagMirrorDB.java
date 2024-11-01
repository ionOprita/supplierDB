package ro.sellfluence.db;

import ch.claudio.db.DB;
import ro.sellfluence.emagapi.AWB;
import ro.sellfluence.emagapi.Attachment;
import ro.sellfluence.emagapi.Customer;
import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.emagapi.ReturnedProduct;
import ro.sellfluence.emagapi.StatusHistory;
import ro.sellfluence.emagapi.StatusRequest;
import ro.sellfluence.emagapi.Voucher;
import ro.sellfluence.emagapi.VoucherSplit;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.math.RoundingMode.HALF_EVEN;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class EmagMirrorDB {

    private static final Map<String, EmagMirrorDB> openDatabases = new HashMap<>();
    private static final Logger logger = Logger.getLogger(EmagMirrorDB.class.getName());

    private static final DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DB database;

    private EmagMirrorDB(DB database) {
        this.database = database;
    }

    public static EmagMirrorDB getEmagMirrorDB(String alias) throws SQLException, IOException {
        var mirrorDB = openDatabases.get(alias);
        if (mirrorDB == null) {
            var db = new DB(alias);
            db.prepareDB(EmagMirrorDBVersion1::version1);
            mirrorDB = new EmagMirrorDB(db);
            openDatabases.put(alias, mirrorDB);
        }
        return mirrorDB;
    }

    public void addOrder(OrderResult order) throws SQLException {
        var vendorName = order.vendor_name;
        var vendorId = getOrAddVendor(vendorName);
        database.writeTX(db -> {
                    if (order.customer != null) insertCustomer(db, order.customer);
                    if (order.details != null && order.details.locker_id != null) indertLockerDetails(db, order.details);
                    insertOrder(db, order, vendorId);
                    for (var product : order.products) {
                        insertProduct(db, product, order.id, vendorId);
                        for (var voucherSplit : product.product_voucher_split) {
                            insertVoucherSplit(db, voucherSplit, order.id, vendorId, product.id);
                        }
                    }
                    for (var voucherSplit : order.shipping_tax_voucher_split) {
                        insertVoucherSplit(db, voucherSplit, order.id, vendorId);
                    }
                    for (var attachment : order.attachments) {
                        if (attachment.order_id != null && !order.id.equals(attachment.order_id)) {
                            logger.log(WARNING, "Attachment order_id mismatch, order has " + order.id + " but attachment has " + attachment.order_id);
                        }
                        insertAttachment(db, attachment, order.id, vendorId);
                    }
                    for (Voucher voucher : order.vouchers) {
                        insertVoucher(db, voucher, order.id, vendorId);

                    }
                    return 0;
                }
        );
    }

    public void addRMA(RMAResult rmaResult) throws SQLException {
        database.writeTX(db -> {
            var emagId = rmaResult.emag_id();
            insertRMAResult(db, rmaResult);
            for (var product : rmaResult.products()) {
                insertReturnedProduct(db, product, emagId);
            }
            for (var awb : rmaResult.awbs()) {
                insertAWB(db, awb, emagId);
            }
            for (var statusHistory : rmaResult.status_history()) {
                var historyUUID = UUID.randomUUID();
                insertStatusHistory(db, statusHistory, emagId, historyUUID);
                for (var statusRequest : statusHistory.requests()) {
                    insertStatusRequest(db, statusRequest, historyUUID);
                }
            }
            return 0;
        });
    }

    public void addProduct(ProductInfo productInfo) throws SQLException {
        database.writeTX(db -> insertProduct(db, productInfo));
    }

    public void addEmagLog(String account, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime fetchStartTime, LocalDateTime fetchEndTime, String error) throws SQLException {
        database.writeTX(db -> {
                    var insertedRows = insertEmagLog(db, account, startTime, endTime, fetchStartTime, fetchEndTime, error);
                    if (insertedRows == 0) {
                        updateEmagLog(db, account, startTime, endTime, fetchStartTime, fetchEndTime, error);
                    }
                    return "";
                }
        );
    }

    public enum FetchStatus {
        yes,
        no,
        partially
    }

    public FetchStatus wasFetched(String account, LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        var fetches = database.readTX(db -> getEmagLog(db, account, startTime, endTime));
        if (fetches.isEmpty()) {
            return FetchStatus.no;
        } else if (fetches.size() == 1) {
            var fetch = fetches.getFirst();
            if (fetch.error() != null) {
                return FetchStatus.no;
            } else if (!(fetch.startTime().isAfter(startTime) || fetch.endTime().isBefore(endTime))) {
                return FetchStatus.yes;
            } else {
                return FetchStatus.partially;
            }
        } else {
            throw new RuntimeException("Not yet implemented");
        }
    }

    /**
     * Read database information and prepare them for inclusion in the spreadsheet.
     *
     * @return list of rows containing a list of cell groups. Each cell group is a list of cells.
     * @throws SQLException
     */
    public List<List<List<Object>>> readForSheet() throws SQLException {
        return database.readTX(db ->
                {
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
                                    ON p.order_id = o.id
                                    JOIN product as pi
                                    ON p.part_number_key = pi.emag_pnk
                                    WHERE o.status = 4 OR o.status = 5
                                    """
                    )) {
                        try (var rs = s.executeQuery()) {
                            while (rs.next()) {
// 2024-10-17 16:49:51
                                var priceWithoutVAT = rs.getBigDecimal(6);
                                var priceWithVAT = priceWithoutVAT.multiply(BigDecimal.valueOf(1.19)); // TODO: Proper handling of VAT required
                                String customerName = rs.getString(8);
                                boolean isFBE = rs.getBoolean(15);
                                var row = List.of(
                                        Stream.of(
                                                rs.getTimestamp(1).toLocalDateTime().format(formatDate), // creation date
                                                rs.getString(2), // id
                                                statusToString(rs.getInt(3)) // status
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(4),
                                                rs.getInt(5), // quantity
                                                priceWithoutVAT.setScale(2, HALF_EVEN),
                                                priceWithVAT.setScale(2, HALF_EVEN)
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(7), // deliver mode
                                                customerName, // customerName
                                                customerName, // customer shipment name
                                                rs.getString(9) // customer shipping phone
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(10), // customer billing name
                                                rs.getString(11) // customer billing phone
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(12), // company code
                                                rs.getString(13) // observation
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(14), // company name
                                                booleanToFBE(isFBE) // platform
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                isFBE,
                                                isFBE,
                                                isFBE,
                                                false
                                        ).map(Object.class::cast).toList(),
                                        Stream.of(
                                                rs.getString(16)
                                        ).map(Object.class::cast).toList()
                                );
                                rows.add(row);
                            }
                        }
                    }
                    return rows;
                }
        );
    }

    private String booleanToFBE(boolean isFBE) {
        return isFBE ? "eMAG FBE" : "eMAG NON-FBE";
    }

    private String modelToString(String pnk) {
        return "PNK:" + pnk;
    }

    private String statusToString(int status) {
        return switch (status) {
            case 4 -> "Finalizata";
            case 5 -> "Stornata";
            default -> "Status:" + status;
        };
    }

    private UUID getOrAddVendor(String name) throws SQLException {
        return database.writeTX(db -> {
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
                }
        );
    }

    private static int insertAttachment(Connection db, Attachment attachment, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO attachment (order_id, vendor_id, name, url, type, force_download) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(order_id, url) DO NOTHING")) {
            s.setString(1, orderId);
            s.setObject(2, vendorId);
            s.setString(3, attachment.name);
            s.setString(4, attachment.url);
            s.setInt(5, attachment.type);
            s.setInt(6, attachment.force_download);
            return s.executeUpdate();
        }
    }

    private static int insertVoucher(Connection db, Voucher voucher, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO voucher (voucher_id, order_id, vendor_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucher.voucher_id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setString(4, voucher.modified);
            s.setString(5, voucher.created);
            s.setInt(6, voucher.status);
            s.setBigDecimal(7, voucher.sale_price_vat);
            s.setBigDecimal(8, voucher.sale_price);
            s.setString(9, voucher.voucher_name);
            s.setBigDecimal(10, voucher.vat);
            s.setString(11, voucher.issue_date);
            return s.executeUpdate();
        }
    }

    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO voucher_split (voucher_id, order_id, vendor_id, value, vat_value) VALUES (?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setBigDecimal(4, voucherSplit.value);
            s.setBigDecimal(5, voucherSplit.vat_value);
            return s.executeUpdate();
        }
    }

    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId, int productId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO voucher_split (voucher_id, order_id, vendor_id, product_id, value, vat_value) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setInt(4, productId);
            s.setBigDecimal(5, voucherSplit.value);
            s.setBigDecimal(6, voucherSplit.vat_value);
            return s.executeUpdate();
        }
    }

    private static int insertProduct(Connection db, Product product, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO product_in_order (id, order_id, vendor_id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING")) {
            s.setInt(1, product.id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setInt(4, product.product_id);
            s.setInt(5, product.mkt_id);
            s.setString(6, product.name);
            s.setInt(7, product.status);
            s.setString(8, product.ext_part_number);
            s.setString(9, product.part_number);
            s.setString(10, product.part_number_key);
            s.setString(11, product.currency);
            s.setString(12, product.vat);
            s.setInt(13, product.retained_amount);
            s.setInt(14, product.quantity);
            s.setInt(15, product.initial_qty);
            s.setInt(16, product.storno_qty);
            s.setInt(17, product.reversible_vat_charging);
            s.setBigDecimal(18, product.sale_price);
            s.setBigDecimal(19, product.original_price);
            s.setTimestamp(20, Timestamp.valueOf(product.created));
            s.setTimestamp(21, Timestamp.valueOf(product.modified));
            s.setString(22, String.join("\n", product.details));
            s.setString(23, String.join("\n", product.recycle_warranties));
            return s.executeUpdate();
        }
    }

    private static int insertCustomer(Connection db, Customer c) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO customer (id, mkt_id, name, email, company, gender, code, registration_number, bank, iban, fax, legal_entity, is_vat_payer, phone_1, phone_2, phone_3, billing_name, billing_phone, billing_country, billing_suburb, billing_city, billing_locality_id, billing_street, billing_postal_code, liable_person, shipping_country, shipping_suburb, shipping_city, shipping_locality_id, shipping_street, shipping_postal_code, shipping_contact, shipping_phone, created, modified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            s.setInt(1, c.id);
            s.setInt(2, c.mkt_id);
            s.setString(3, c.name);
            s.setString(4, c.email);
            s.setString(5, c.company);
            s.setString(6, c.gender);
            s.setString(7, c.code);
            s.setString(8, c.registration_number);
            s.setString(9, c.bank);
            s.setString(10, c.iban);
            s.setString(11, c.fax);
            s.setInt(12, c.legal_entity);
            s.setInt(13, c.is_vat_payer);
            s.setString(14, c.phone_1);
            s.setString(15, c.phone_2);
            s.setString(16, c.phone_3);
            s.setString(17, c.billing_name);
            s.setString(18, c.billing_phone);
            s.setString(19, c.billing_country);
            s.setString(20, c.billing_suburb);
            s.setString(21, c.billing_city);
            s.setString(22, c.billing_locality_id);
            s.setString(23, c.billing_street);
            s.setString(24, c.billing_postal_code);
            s.setString(25, c.liable_person);
            s.setString(26, c.shipping_country);
            s.setString(27, c.shipping_suburb);
            s.setString(28, c.shipping_city);
            s.setString(29, c.shipping_locality_id);
            s.setString(30, c.shipping_street);
            s.setString(31, c.shipping_postal_code);
            s.setString(32, c.shipping_contact);
            s.setString(33, c.shipping_phone);
            s.setTimestamp(34, Timestamp.valueOf(c.created));
            s.setTimestamp(35, Timestamp.valueOf(c.modified));
            return s.executeUpdate();
        }
    }

    private static int indertLockerDetails(Connection db, LockerDetails ld) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO locker_details (locker_id, locker_name) VALUES (?,?) ON CONFLICT(locker_id) DO NOTHING")) {
            s.setString(1, ld.locker_id);
            s.setString(2, ld.locker_name);
            return s.executeUpdate();
        }
    }

    private static int insertOrder(Connection db, OrderResult or, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO emag_order (vendor_id, id, status, is_complete, type, payment_mode, payment_mode_id, delivery_payment_mode, delivery_mode, observation, details_id, date, payment_status, cashed_co, cashed_cod, shipping_tax, customer_id, is_storno, cancellation_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id, vendor_id) DO NOTHING")) {
            s.setObject(1, vendorId);
            s.setString(2, or.id);
            s.setInt(3, or.status);
            s.setInt(4, or.is_complete);
            s.setInt(5, or.type);
            s.setString(6, or.payment_mode);
            s.setInt(7, or.payment_mode_id);
            s.setString(8, or.delivery_payment_mode);
            s.setString(9, or.delivery_mode);
            s.setString(10, or.observation);
            s.setString(11, or.details.locker_id);
            s.setTimestamp(12, Timestamp.valueOf(or.date));
            s.setInt(13, or.payment_status);
            s.setBigDecimal(14, or.cashed_co);
            s.setBigDecimal(15, or.cashed_cod);
            s.setBigDecimal(16, or.shipping_tax);
            if (or.customer != null) s.setInt(17, or.customer.id);
            s.setBoolean(18, or.is_storno);
            s.setObject(19, or.cancellation_reason);
            return s.executeUpdate();
        }
    }

    private static int insertAWB(Connection db, AWB awb, int emagId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO awb (reservation_id, emag_id) VALUES (?, ?)")) {
            s.setInt(1, awb.reservation_id());
            s.setInt(2, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertStatusHistory(Connection db, StatusHistory statusHistory, int emagId, UUID uuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_history (uuid, code, emag_id) VALUES (?, ?, ?)")) {
            s.setObject(1, uuid);
            s.setString(2, statusHistory.code());
            s.setInt(3, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertStatusRequest(Connection db, StatusRequest statusRequest, UUID statusHistoryUuid) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO status_request (amount, created, refund_type, refund_status, rma_id, status_date, status_history_uuid) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setBigDecimal(1, statusRequest.amount());
            s.setTimestamp(2, Timestamp.valueOf(statusRequest.created()));
            s.setString(3, statusRequest.refund_type());
            s.setString(4, statusRequest.refund_status());
            s.setString(5, statusRequest.rma_id());
            s.setTimestamp(6, Timestamp.valueOf(statusRequest.status_date()));
            s.setObject(7, statusHistoryUuid);
            return s.executeUpdate();
        }
    }

    private static int insertReturnedProduct(Connection db, ReturnedProduct returnedProduct, int emagId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO emag_returned_products (id, product_emag_id, product_id, quantity, product_name, return_reason, observations, diagnostic, reject_reason, retained_amount, emag_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setInt(1, returnedProduct.id());
            s.setInt(2, returnedProduct.product_emag_id());
            s.setInt(3, returnedProduct.product_id());
            s.setInt(4, returnedProduct.quantity());
            s.setString(5, returnedProduct.product_name());
            s.setInt(6, returnedProduct.return_reason());
            s.setString(7, returnedProduct.observations());
            s.setObject(8, returnedProduct.diagnostic());
            s.setInt(9, returnedProduct.reject_reason());
            s.setInt(10, returnedProduct.retained_amount());
            s.setInt(11, emagId);
            return s.executeUpdate();
        }
    }

    private static int insertRMAResult(Connection db, RMAResult rmaResult) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO rma_result (is_full_fbe, emag_id, return_parent_id, order_id, type, is_club, is_fast, customer_name, customer_company, customer_phone, pickup_country, pickup_suburb, pickup_city, pickup_address, pickup_zipcode, pickup_locality_id, pickup_method, customer_account_iban, customer_account_bank, customer_account_beneficiary, replacement_product_emag_id, replacement_product_id, replacement_product_name, replacement_product_quantity, observations, request_status, return_type, return_reason, date, extra_info, return_tax_value, swap, return_address_snapshot, request_history, locker) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            s.setTimestamp(29, Timestamp.valueOf(rmaResult.date()));
            s.setString(30, rmaResult.extra_info());
            s.setString(31, rmaResult.return_tax_value());
            s.setString(32, rmaResult.swap());
            s.setString(33, rmaResult.return_address_snapshot());
            s.setString(34, String.join("\n", rmaResult.request_history()));
            s.setString(35, String.join("\n", rmaResult.locker()));
            return s.executeUpdate();
        }
    }

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

    private static int insertEmagLog(Connection db, String account, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime fetchStartTime, LocalDateTime fetchEndTime, String error) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO emag_fetch_log (emag_login, order_start, order_end, fetch_start, fetch_end, error) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(emag_login, order_start, order_end) DO NOTHING")) {
            s.setString(1, account);
            s.setTimestamp(2, Timestamp.valueOf(startTime));
            s.setTimestamp(3, Timestamp.valueOf(endTime));
            s.setTimestamp(4, Timestamp.valueOf(fetchStartTime));
            s.setTimestamp(5, Timestamp.valueOf(fetchEndTime));
            s.setString(6, error);
            return s.executeUpdate();
        }
    }

    private static int updateEmagLog(Connection db, String account, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime fetchStartTime, LocalDateTime fetchEndTime, String error) throws SQLException {
        try (var s = db.prepareStatement("UPDATE emag_fetch_log SET fetch_start=?, fetch_end=?, error=? WHERE emag_login=? AND order_start=? AND order_end=?")) {
            s.setTimestamp(1, Timestamp.valueOf(fetchStartTime));
            s.setTimestamp(2, Timestamp.valueOf(fetchEndTime));
            s.setString(3, error);
            s.setString(4, account);
            s.setTimestamp(5, Timestamp.valueOf(startTime));
            s.setTimestamp(6, Timestamp.valueOf(endTime));
            return s.executeUpdate();
        }
    }

    /**
     * Get all entries from emag_fetch_log which overlap with the given range.
     *
     * @param db database
     * @param account emag account
     * @param startTime start of range inclusive
     * @param endTime end of range exclusive
     */
    private static List<EmagFetchLog> getEmagLog(Connection db, String account, LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        try (var s = db.prepareStatement("SELECT * FROM emag_fetch_log WHERE emag_login=? AND ? < order_end AND ? > order_start")) {
            s.setString(1, account);
            s.setTimestamp(2, Timestamp.valueOf(startTime));
            s.setTimestamp(3, Timestamp.valueOf(endTime));
            try (var rs = s.executeQuery()) {
                var fetchLogs = new ArrayList<EmagFetchLog>();
                while (rs.next()) {
                    fetchLogs.add(new EmagFetchLog(
                            rs.getString(1),
                            rs.getTimestamp(2).toLocalDateTime(),
                            rs.getTimestamp(3).toLocalDateTime(),
                            rs.getTimestamp(4).toLocalDateTime(),
                            rs.getTimestamp(5).toLocalDateTime(),
                            rs.getString(6)
                    ));
                }
                return fetchLogs;
            }
        }
    }
}
