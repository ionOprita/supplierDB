package ro.sellfluence.db;

import ch.claudio.db.DB;
import ro.sellfluence.emagapi.Attachment;
import ro.sellfluence.emagapi.Customer;
import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.emagapi.Voucher;
import ro.sellfluence.emagapi.VoucherSplit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

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
                        if (!order.id.equals(attachment.order_id)) {
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

    private UUID getOrAddVendor(String name) throws SQLException {
        return database.writeTX(db -> {
                    UUID id = null;
                    try (var s = db.prepareStatement("SELECT id FROM Vendor WHERE vendor_name=?")) {
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
                        try (var s = db.prepareStatement("INSERT INTO Vendor (id, vendor_name) VALUES (?,?)")) {
                            s.setObject(1, id);
                            s.setString(2, name);
                            s.executeUpdate();
                        }
                    }
                    return id;
                }
        );
    }

    private static int insertAttachment(Connection db, Attachment attachment, String orderId, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO Attachment (order_id, vendor_id, name, url, type, force_download) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(order_id, url) DO NOTHING")) {
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
        try (var s = db.prepareStatement("INSERT INTO Voucher (voucher_id, order_id, vendor_id, modified, created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
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
        try (var s = conn.prepareStatement("INSERT INTO VoucherSplit (voucher_id, order_id, vendor_id, value, vat_value) VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING")) {
            s.setInt(1, voucherSplit.voucher_id);
            s.setString(2, orderId);
            s.setObject(3, vendorId);
            s.setBigDecimal(4, voucherSplit.value);
            s.setBigDecimal(5, voucherSplit.vat_value);
            return s.executeUpdate();
        }
    }

    private static int insertVoucherSplit(Connection conn, VoucherSplit voucherSplit, String orderId, UUID vendorId, int productId) throws SQLException {
        try (var s = conn.prepareStatement("INSERT INTO VoucherSplit (voucher_id, order_id, vendor_id, product_id, value, vat_value) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(voucher_id) DO NOTHING")) {
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
        try (var s = db.prepareStatement("INSERT INTO Product (id, order_id, vendor_id, product_id, mkt_id, name, status, ext_part_number, part_number, part_number_key, currency, vat, retained_amount, quantity, initial_qty, storno_qty, reversible_vat_charging, sale_price, original_price, created, modified, details, recycle_warranties) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING")) {
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
        try (var s = db.prepareStatement("INSERT INTO Customer (id, mkt_id, name, email, company, gender, code, registration_number, bank, iban, fax, legal_entity, is_vat_payer, phone_1, phone_2, phone_3, billing_name, billing_phone, billing_country, billing_suburb, billing_city, billing_locality_id, billing_street, billing_postal_code, liable_person, shipping_country, shipping_suburb, shipping_city, shipping_locality_id, shipping_street, shipping_postal_code, shipping_contact, shipping_phone, created, modified) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
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
        try (var s = db.prepareStatement("INSERT INTO LockerDetails (locker_id, locker_name) VALUES (?,?) ON CONFLICT(locker_id) DO NOTHING")) {
            s.setString(1, ld.locker_id);
            s.setString(2, ld.locker_name);
            return s.executeUpdate();
        }
    }

    private static int insertOrder(Connection db, OrderResult or, UUID vendorId) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO EmagOrder (vendor_id, id, status, is_complete, type, payment_mode, payment_mode_id, delivery_payment_mode, delivery_mode, observation, details_id, date, payment_status, cashed_co, cashed_cod, shipping_tax, customer_id, is_storno, cancellation_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id, vendor_id) DO NOTHING")) {
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
}
