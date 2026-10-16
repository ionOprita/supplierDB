package ro.sellfluence.db.versions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EmagMirrorDBVersion45Test {
    private Connection db;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "Disposable PostgreSQL required");
        var properties = new Properties();
        if (System.getenv("ADS_TEST_DB_USER") != null) properties.setProperty("user", System.getenv("ADS_TEST_DB_USER"));
        if (System.getenv("ADS_TEST_DB_PASSWORD") != null) properties.setProperty("password", System.getenv("ADS_TEST_DB_PASSWORD"));
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "voucher_v45_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        sql("""
                CREATE TABLE emag_order (surrogate_id INT PRIMARY KEY, id TEXT, vendor_id UUID, status INT,
                    cashed_co NUMERIC, cashed_cod NUMERIC, shipping_tax NUMERIC)
                """);
        sql("""
                CREATE TABLE product_in_order (id INT, emag_order_surrogate_id INT, part_number_key TEXT,
                    quantity INT, initial_qty INT, storno_qty INT, sale_price NUMERIC, vat TEXT,
                    PRIMARY KEY (id, emag_order_surrogate_id))
                """);
        sql("""
                CREATE TABLE voucher (voucher_id INT, emag_order_surrogate_id INT, modified TEXT, created TEXT,
                    status INT, sale_price_vat NUMERIC, sale_price NUMERIC, voucher_name TEXT, vat NUMERIC,
                    issue_date TEXT, id TEXT, PRIMARY KEY (voucher_id, emag_order_surrogate_id))
                """);
        sql("""
                CREATE TABLE voucher_split (voucher_id INT, emag_order_surrogate_id INT, product_id INT,
                    value NUMERIC, vat_value NUMERIC, vat TEXT, offered_by TEXT, voucher_name TEXT,
                    PRIMARY KEY (voucher_id, product_id, emag_order_surrogate_id))
                """);
        sql("""
                CREATE TABLE order_voucher_split (voucher_id INT, emag_order_surrogate_id INT,
                    value NUMERIC, vat_value NUMERIC)
                """);
        pair(1, "100", "360");
        voucher(2, 10, "-40", "0", "seller");
        db.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (db != null) try (var ignored = db) {
            db.rollback(); db.setAutoCommit(true); db.setSchema("public");
            sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void repairsAllProvidersAndPreservesEveryCopiedFieldAndSource() throws Exception {
        voucher(2, 11, "-20", "0", "emag");
        sql("UPDATE emag_order SET cashed_co = 340");
        var before = EmagMirrorDBVersion45.inspect(db);
        assertEquals(2, before.splitCount());
        assertEquals(2, before.headerCount());
        assertEquals(1, before.paymentVerified());
        EmagMirrorDBVersion45.version45(db);
        assertEquals(2, count("voucher_split", "emag_order_surrogate_id = 1"));
        assertEquals(2, count("voucher", "emag_order_surrogate_id = 1"));
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM (
                    (SELECT voucher_id, modified, created, status, sale_price_vat, sale_price,
                            voucher_name, vat, issue_date, id FROM voucher WHERE emag_order_surrogate_id = 1)
                    EXCEPT
                    (SELECT voucher_id, modified, created, status, sale_price_vat, sale_price,
                            voucher_name, vat, issue_date, id FROM voucher WHERE emag_order_surrogate_id = 2)
                ) differences
                """));
        assertEquals(2, count("voucher_split", "emag_order_surrogate_id = 2"));
        assertEquals(0, EmagMirrorDBVersion45.inspect(db).splitCount());
        EmagMirrorDBVersion45.version45(db);
        assertEquals(4, count("voucher_split", "TRUE"));
    }

    @Test
    void acceptsTheObservedVatInclusiveCheckout() throws Exception {
        sql("UPDATE product_in_order SET quantity = CASE WHEN emag_order_surrogate_id = 1 THEN 1 ELSE 0 END, "
                + "initial_qty = 1, storno_qty = CASE WHEN emag_order_surrogate_id = 1 THEN 0 ELSE 1 END, sale_price = 100.83, vat = '0.19'");
        sql("UPDATE voucher SET sale_price = -20.17, sale_price_vat = -3.83, vat = 0.19");
        sql("UPDATE voucher_split SET value = -20.17, vat_value = -3.83, vat = '0.19'");
        sql("UPDATE emag_order SET cashed_co = 95.99");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(1, count("voucher_split", "emag_order_surrogate_id = 1"));
    }

    @Test
    void acceptsUncollectedCodOnlyWhenEveryProductIsFullyReturned() throws Exception {
        sql("UPDATE emag_order SET cashed_co = NULL, cashed_cod = 0");
        assertEquals(1, EmagMirrorDBVersion45.inspect(db).fullyReturned());
        EmagMirrorDBVersion45.version45(db);
        assertEquals(1, count("voucher_split", "emag_order_surrogate_id = 1"));
    }

    @Test
    void acceptsACompletelyVoucherFundedCheckoutWithoutPayment() throws Exception {
        sql("UPDATE emag_order SET cashed_co = 0");
        sql("UPDATE voucher SET sale_price = -400");
        sql("UPDATE voucher_split SET value = -400");
        sql("UPDATE product_in_order SET quantity = 4, storno_qty = 0 WHERE emag_order_surrogate_id = 2");
        assertEquals(1, EmagMirrorDBVersion45.inspect(db).zeroCheckout());
        EmagMirrorDBVersion45.version45(db);
    }

    @Test
    void rejectsUnpaidOrdersWithAnyUnreturnedProduct() throws Exception {
        sql("UPDATE emag_order SET cashed_co = NULL, cashed_cod = 0");
        sql("INSERT INTO product_in_order VALUES (102, 2, 'OTHER', 1, 1, 0, 10, '0')");
        assertRejected("not fully returned");
    }

    @Test
    void acceptsCodRecordedOnlyOnTheStornoAndUsesOriginalQuantities() throws Exception {
        sql("UPDATE emag_order SET cashed_co = NULL, cashed_cod = CASE WHEN status = 5 THEN 360 ELSE 0 END");
        sql("UPDATE product_in_order SET quantity = 3, storno_qty = 1 WHERE emag_order_surrogate_id = 1");
        assertEquals(1, EmagMirrorDBVersion45.inspect(db).paymentVerified());
        EmagMirrorDBVersion45.version45(db);
    }

    @Test
    void acceptsObservedRoundingButRejectsDifferencesBeyondOneCentPerOriginalUnit() throws Exception {
        sql("UPDATE emag_order SET cashed_co = 359.96");
        EmagMirrorDBVersion45.inspect(db);
        sql("UPDATE emag_order SET cashed_co = 359.95");
        assertRejected("does not match recorded payment");
    }

    @Test
    void rejectsDisagreeingPaymentsAndOverDiscountedOrders() throws Exception {
        sql("UPDATE emag_order SET cashed_co = 350 WHERE status = 5");
        assertRejected("payment amounts disagree");
        sql("UPDATE emag_order SET cashed_co = 0");
        sql("UPDATE voucher SET sale_price = -500");
        sql("UPDATE voucher_split SET value = -500");
        assertRejected("vouchers exceed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sale_price = 101", "vat = '0.19'", "initial_qty = 5", "part_number_key = 'WRONG'"})
    void rejectsMismatchedSnapshotProducts(String change) throws Exception {
        sql("UPDATE product_in_order SET " + change + " WHERE emag_order_surrogate_id = 2");
        assertRejected("differs between snapshots");
    }

    @Test
    void rejectsInconsistentHeadersAndDoesNotOverwriteExistingHeaders() throws Exception {
        sql("UPDATE voucher SET sale_price = -41");
        assertRejected("source voucher header does not reconcile");
        sql("UPDATE voucher SET sale_price = -40");
        sql("INSERT INTO voucher SELECT voucher_id, 1, modified, created, status, sale_price_vat, -41, voucher_name, vat, issue_date, id FROM voucher WHERE emag_order_surrogate_id = 2");
        assertRejected("existing voucher header conflicts");
        assertEquals(1, count("voucher", "emag_order_surrogate_id = 1 AND sale_price = -41"));
    }

    @Test
    void validatesAllCandidatesBeforeAnyInsertsAndSupportsTransactionRollback() throws Exception {
        pair(3, "200", "1");
        voucher(4, 10, "-40", "0", "seller");
        db.commit();
        assertRejected("recorded payment");
        assertEquals(0, count("voucher_split", "emag_order_surrogate_id IN (1,3)"));
        db.rollback();
        assertEquals(2, count("voucher_split", "emag_order_surrogate_id IN (2,4)"));
        sql("UPDATE emag_order SET cashed_co = 360 WHERE surrogate_id IN (3,4)");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(2, count("voucher_split", "emag_order_surrogate_id IN (1,3)"));
        db.rollback();
        assertEquals(0, count("voucher_split", "emag_order_surrogate_id IN (1,3)"));
    }

    @Test
    void includesShippingAndItsSplitWithoutDoubleCountingVoucherHeaders() throws Exception {
        sql("UPDATE emag_order SET shipping_tax = 10, cashed_co = 365");
        sql("UPDATE voucher SET sale_price = -45");
        sql("INSERT INTO order_voucher_split VALUES (10, 1, -5, 0), (10, 2, -5, 0)");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(1, count("voucher", "emag_order_surrogate_id = 1 AND sale_price = -45"));
    }

    @Test
    void repairsOnlyMissingAllocationsAndPreservesExistingHeaderMetadata() throws Exception {
        sql("INSERT INTO product_in_order VALUES (102, 1, 'PNK2', 1, 1, 0, 100, '0'), (102, 2, 'PNK2', 0, 1, 1, 100, '0')");
        sql("UPDATE emag_order SET cashed_co = 460");
        sql("UPDATE voucher_split SET value = -20");
        sql("INSERT INTO voucher_split VALUES (10, 2, 102, -20, 0, '0', 'seller', 'name'), (10, 1, 101, -21, 0, '0.0000', 'seller', 'name')");
        sql("INSERT INTO voucher SELECT voucher_id, 1, 'original modified', created, status, sale_price_vat, sale_price, voucher_name, vat, issue_date, id FROM voucher WHERE emag_order_surrogate_id = 2");
        assertRejected("existing voucher split conflicts");
        assertEquals(1, count("voucher_split", "emag_order_surrogate_id = 1 AND value = -21"));
        sql("UPDATE voucher_split SET value = -20 WHERE emag_order_surrogate_id = 1");
        assertEquals(1, EmagMirrorDBVersion45.inspect(db).splitCount());
        assertEquals(0, EmagMirrorDBVersion45.inspect(db).headerCount());
        EmagMirrorDBVersion45.version45(db);
        assertEquals(2, count("voucher_split", "emag_order_surrogate_id = 1"));
        assertEquals(1, count("voucher", "emag_order_surrogate_id = 1 AND modified = 'original modified'"));
    }

    @Test
    void doesNothingWhenThereAreNoMissingVoucherSplits() throws Exception {
        sql("DELETE FROM voucher_split");
        sql("DELETE FROM voucher");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(0, EmagMirrorDBVersion45.inspect(db).splitCount());
        assertEquals(0, count("voucher", "TRUE"));
    }

    @Test
    void ignoresStatusOneAndDoesNotPairAcrossVendors() throws Exception {
        sql("INSERT INTO emag_order VALUES (9, '100', '11111111-1111-4111-8111-111111111111', 1, 360, 0, 0)");
        sql("INSERT INTO product_in_order SELECT id, 9, part_number_key, quantity, initial_qty, storno_qty, sale_price, vat FROM product_in_order WHERE emag_order_surrogate_id = 1");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(0, count("voucher", "emag_order_surrogate_id = 9"));
        db.rollback();
        sql("UPDATE emag_order SET vendor_id = '22222222-2222-4222-8222-222222222222' WHERE status = 5");
        EmagMirrorDBVersion45.version45(db);
        assertEquals(0, count("voucher", "emag_order_surrogate_id = 1"));
    }

    private void pair(int first, String orderId, String payment) throws Exception {
        sql("INSERT INTO emag_order VALUES (" + first + ", '" + orderId + "', '11111111-1111-4111-8111-111111111111', 4, " + payment + ", 0, 0), ("
                + (first + 1) + ", '" + orderId + "', '11111111-1111-4111-8111-111111111111', 5, " + payment + ", 0, 0)");
        sql("INSERT INTO product_in_order VALUES (101, " + first + ", 'PNK', 4, 4, 0, 100, '0'), (101, " + (first + 1) + ", 'PNK', 0, 4, 4, 100, '0')");
    }

    private void voucher(int snapshot, int voucherId, String value, String vat, String provider) throws Exception {
        sql("INSERT INTO voucher VALUES (" + voucherId + ", " + snapshot + ", 'modified', 'created', 1, " + vat + ", " + value + ", 'name', 0, 'issue', 'remote-id')");
        sql("INSERT INTO voucher_split VALUES (" + voucherId + ", " + snapshot + ", 101, " + value + ", " + vat + ", '0', '" + provider + "', 'name')");
    }

    private void assertRejected(String text) {
        var error = assertThrows(SQLException.class, () -> EmagMirrorDBVersion45.version45(db));
        assertTrue(error.getMessage().contains(text), error.getMessage());
    }
    private void sql(String query) throws SQLException { try (var s = db.createStatement()) { s.execute(query); } }
    private int count(String table, String condition) throws SQLException { return scalar("SELECT COUNT(*) FROM " + table + " WHERE " + condition); }
    private int scalar(String query) throws SQLException {
        try (var s = db.createStatement(); var rs = s.executeQuery(query)) { assertTrue(rs.next()); return rs.getInt(1); }
    }
}
