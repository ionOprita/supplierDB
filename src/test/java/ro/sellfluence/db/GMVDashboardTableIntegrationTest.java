package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads only an explicitly supplied disposable PostgreSQL database, using an isolated schema. */
class GMVDashboardTableIntegrationTest {
    private static final UUID ALPHA = new UUID(0, 1);
    private static final UUID ALPHA_FBE = new UUID(0, 2);
    private static final UUID BETA = new UUID(0, 3);
    private static final UUID FALLBACK_A = new UUID(0, 4);
    private static final UUID FALLBACK_B = new UUID(0, 5);
    private static final LocalDate THROUGH = LocalDate.of(2026, 11, 3);

    private Connection db;
    private String schema;
    private int nextSnapshot = 1;
    private int nextVoucher = 1;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "gmv_dashboard_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_name TEXT, company_name TEXT)");
        execute("""
                CREATE TABLE product (
                    product_code TEXT PRIMARY KEY, name TEXT, emag_pnk TEXT, vendor UUID, retracted BOOLEAN
                )
                """);
        execute("""
                CREATE TABLE emag_order (
                    surrogate_id INTEGER PRIMARY KEY, id TEXT, vendor_id UUID, status INTEGER,
                    date TIMESTAMP, modified TIMESTAMP, UNIQUE (vendor_id, id, status)
                )
                """);
        execute("""
                CREATE TABLE product_in_order (
                    id INTEGER, emag_order_surrogate_id INTEGER, part_number_key TEXT,
                    quantity INTEGER, initial_qty INTEGER, storno_qty INTEGER, sale_price NUMERIC,
                    vat TEXT, currency TEXT, created TIMESTAMP, modified TIMESTAMP,
                    PRIMARY KEY (id, emag_order_surrogate_id)
                )
                """);
        execute("""
                CREATE TABLE voucher_split (
                    voucher_id INTEGER PRIMARY KEY, product_id INTEGER, emag_order_surrogate_id INTEGER,
                    value NUMERIC, vat_value NUMERIC, offered_by TEXT
                )
                """);
        vendor(ALPHA, "Alpha main", "Alpha SRL");
        vendor(ALPHA_FBE, "Alpha FBE", " Alpha SRL ");
        vendor(BETA, "Beta main", "Beta SRL");
        vendor(FALLBACK_A, "Same fallback", null);
        vendor(FALLBACK_B, "Same fallback", " \t ");
        product("CODE", "PNK-A", BETA, true);
        product("OTHER", "PNK-B", ALPHA, false);
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                db.setSchema("public");
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void usesStoredVatAndSellerVouchersWithOneMonthlyCalculationAndActualOrderCompany() throws Exception {
        var finalized = source("pair", ALPHA, 4, "2026-11-01T10:00:00", "PNK-A",
                4, 4, 0, "100.00", "0.19", "RON");
        var returned = source("pair", ALPHA, 5, "2026-11-03T10:00:00", "PNK-A",
                3, 4, 1, "110.00", "0.21", "RON");
        voucher(finalized, "-30", "-10", "seller");
        voucher(finalized, "-100", "-20", "emag");
        voucher(returned, "-25", "-5", "seller");
        var fbe = source("fbe", ALPHA_FBE, 4, "2026-11-02T10:00:00", "PNK-A",
                2, 2, 0, "3.995", "0", "RON");
        voucher(fbe, "-0.015", null, "seller");
        voucher(fbe, null, "-0.015", "seller");

        var data = GMVDashboardTable.read(db, THROUGH);

        assertEquals("377.27", data.total());
        assertEquals(List.of("0.00", "7.97", "369.30"), company(data, "company:Alpha SRL").gmv());
        assertEquals("377.27", company(data, "company:Alpha SRL").total());
        assertEquals("0.00", company(data, "company:Beta SRL").total(),
                "Catalog ownership and retracted flags must not override the actual order vendor");
        assertEquals(legacy("CODE", THROUGH), new BigDecimal(data.total()));
        assertReconciles(data);
    }

    @Test
    void keepsOutsidePartnerStatesForCrossMonthStornoAndForAFuturePartner() throws Exception {
        var original = source("earlier", ALPHA, 4, "2026-10-31T10:00:00", "PNK-A",
                4, 4, 0, "100", "0", "RON");
        var returned = source("earlier", ALPHA, 5, "2026-11-02T10:00:00", "PNK-A",
                3, 4, 1, "110", "0", "RON");
        voucher(original, "-40", "0", "seller");
        voucher(returned, "-30", "0", "seller");
        source("later", ALPHA, 4, "2026-11-01T00:00:00", "PNK-A", 2, 2, 0, "10", "0", "RON");
        source("later", ALPHA, 5, "2026-12-01T00:00:00", "PNK-A", 1, 2, 1, "12", "0", "RON");

        var data = GMVDashboardTable.read(db, THROUGH);

        assertEquals("-80.00", data.total());
        assertEquals(List.of("20.00", "-100.00", "0.00"), company(data, "company:Alpha SRL").gmv());
        assertEquals(legacy("CODE", THROUGH), new BigDecimal(data.total()));
        assertEquals("360.00", GMVDashboardTable.read(db, LocalDate.of(2026, 10, 31)).total());
        assertReconciles(data);
    }

    @Test
    void restatesThePartialMonthOnlyWhenItsStornoSnapshotEntersTheRequestedRange() throws Exception {
        var first = source("pair", ALPHA, 4, "2026-11-01T00:00:00", "PNK-A",
                4, 4, 0, "100", "0", "RON");
        var last = source("pair", ALPHA, 5, "2026-11-03T23:59:59", "PNK-A",
                3, 4, 1, "110", "0", "RON");
        voucher(first, "-40", "0", "seller");
        voucher(last, "-30", "0", "seller");

        var early = GMVDashboardTable.read(db, LocalDate.of(2026, 11, 1));
        var later = GMVDashboardTable.read(db, THROUGH);

        assertEquals(List.of("360.00"), company(early, "company:Alpha SRL").gmv());
        assertEquals(List.of("0.00", "0.00", "300.00"), company(later, "company:Alpha SRL").gmv());
        assertEquals(legacy("CODE", LocalDate.of(2026, 11, 1)), new BigDecimal(early.total()));
        assertEquals(legacy("CODE", THROUGH), new BigDecimal(later.total()));
    }

    @Test
    void preservesDuplicateCatalogCodesAndRechecksThePnkCatalogJoinForEveryPartnerState() throws Exception {
        product("CLONE", "PNK-A", ALPHA, false);
        source("changed", ALPHA, 4, "2026-10-31T12:00:00", "PNK-A", 4, 4, 0, "100", "0", "RON");
        source("changed", ALPHA, 5, "2026-11-02T12:00:00", "PNK-B", 3, 4, 1, "110", "0", "RON");
        source("normal", ALPHA, 4, "2026-11-01T12:00:00", "PNK-A", 1, 1, 0, "7.25", "0", "RON");

        var data = GMVDashboardTable.read(db, THROUGH);

        assertEquals("344.50", data.total());
        assertEquals(List.of("14.50", "330.00", "0.00"), company(data, "company:Alpha SRL").gmv());
        assertEquals(legacy("CODE", THROUGH).add(legacy("CLONE", THROUGH)).add(legacy("OTHER", THROUGH)),
                new BigDecimal(data.total()));
    }

    @Test
    void keepsSameOrderAndLineIdsIndependentAcrossVendorsIncludingAccountsInTheSameCompany() throws SQLException {
        source("shared", ALPHA, 4, "2026-11-01T12:00:00", "PNK-A", 2, 2, 0, "10", "0", "RON");
        source("shared", ALPHA_FBE, 4, "2026-11-01T12:00:00", "PNK-A", 4, 4, 0, "10", "0", "RON");
        source("shared", BETA, 4, "2026-11-01T12:00:00", "PNK-A", 3, 3, 0, "10", "0", "RON");

        var data = GMVDashboardTable.read(db, THROUGH);

        assertEquals("90.00", data.total());
        assertEquals("60.00", company(data, "company:Alpha SRL").total());
        assertEquals("30.00", company(data, "company:Beta SRL").total());
        assertReconciles(data);
    }

    @Test
    void usesTwoBatchedReadsAndExcludesUnrelatedOldLinesUnknownPnksAndOtherStatuses() throws SQLException {
        source("normal", ALPHA, 4, "2026-11-03T23:59:59", "PNK-A", 1, 1, 0, "12.34", "0", "RON");
        source("old", ALPHA, 4, "2025-01-01T00:00:00", "PNK-A", 1, 1, 0, "100", "not-VAT", "EUR");
        source("unknown", ALPHA, 4, "2026-11-02T12:00:00", "UNKNOWN", 1, 1, 0, "100", "bad", "EUR");
        source("tomorrow", ALPHA, 4, "2026-11-04T00:00:00", "PNK-A", 1, 1, 0, "100", "bad", "EUR");
        source("new", ALPHA, 1, "2026-11-02T12:00:00", "PNK-A", 1, 1, 0, "100", "bad", "EUR");
        var queryCount = new AtomicInteger();
        var boundedConnection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (_, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        queryCount.incrementAndGet();
                        var sql = (String) arguments[0];
                        assertTrue(sql.stripLeading().startsWith("SELECT") || sql.stripLeading().startsWith("WITH"));
                    }
                    try {
                        return method.invoke(db, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        var data = GMVDashboardTable.read(boundedConnection, THROUGH);

        assertEquals(2, queryCount.get());
        assertEquals("12.34", data.total());
        assertEquals(List.of("0.00", "0.00", "12.34"), company(data, "company:Alpha SRL").gmv());
    }

    @Test
    void rejectsUnsupportedAndMissingCurrencyIncludingAnOutsidePartner() throws SQLException {
        source("pair", ALPHA, 4, "2026-10-31T12:00:00", "PNK-A", 4, 4, 0, "100", "0", "EUR");
        source("pair", ALPHA, 5, "2026-11-02T12:00:00", "PNK-A", 3, 4, 1, "100", "0", "RON");
        assertThrows(GMVDashboardTable.UnsupportedCurrencyException.class, () -> GMVDashboardTable.read(db, THROUGH));
        execute("UPDATE product_in_order SET currency = NULL");
        assertThrows(GMVDashboardTable.UnsupportedCurrencyException.class, () -> GMVDashboardTable.read(db, THROUGH));
        execute("UPDATE product_in_order SET currency = 'RON'");
        assertEquals("-100.00", GMVDashboardTable.read(db, THROUGH).total());
    }

    @Test
    void keepsZeroCompaniesFallbackKeysAndExactZeroForFullyReturnedLines() throws SQLException {
        source("zero", ALPHA, 4, "2026-11-01T12:00:00", "PNK-A", 1, 1, 0, "10", "0", "RON");
        var returned = source("zero", ALPHA, 5, "2026-11-02T12:00:00", "PNK-A", 0, 1, 1, "11", "0", "RON");
        voucher(returned, "-10", "0", "seller");
        var data = GMVDashboardTable.read(db, THROUGH);

        assertEquals("0.00", data.total());
        assertEquals(List.of("company:Alpha SRL", "company:Beta SRL", "vendor:" + FALLBACK_A, "vendor:" + FALLBACK_B),
                data.companies().stream().map(GMVDashboardTable.CompanySeries::key).toList());
        assertTrue(data.companies().stream().allMatch(company -> company.gmv().equals(List.of("0.00", "0.00", "0.00"))));
        assertReconciles(data);
        execute("DELETE FROM vendor");
        assertThrows(SQLException.class, () -> GMVDashboardTable.read(db, THROUGH));
    }

    @SuppressWarnings("unchecked")
    private BigDecimal legacy(String code, LocalDate through) throws Exception {
        var method = EmagMirrorDB.class.getDeclaredMethod("getOrderDataByProduct", Connection.class, String.class);
        method.setAccessible(true);
        var orders = (Map<String, List<POInfo>>) method.invoke(null, db, code);
        return GMV.computeGMVForPeriod(orders, through.withDayOfMonth(1), through);
    }

    private static GMVDashboardTable.CompanySeries company(GMVDashboardTable.GMVDashboardData data, String key) {
        return data.companies().stream().filter(company -> company.key().equals(key)).findFirst().orElseThrow();
    }

    private static void assertReconciles(GMVDashboardTable.GMVDashboardData data) {
        var total = new BigDecimal("0.00");
        for (var company : data.companies()) {
            var sum = company.gmv().stream().map(BigDecimal::new).reduce(new BigDecimal("0.00"), BigDecimal::add);
            assertEquals(company.total(), sum.toPlainString());
            total = total.add(sum);
        }
        assertEquals(data.total(), total.toPlainString());
    }

    private void vendor(UUID id, String name, String company) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO vendor VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setString(3, company);
            statement.executeUpdate();
        }
    }

    private void product(String code, String pnk, UUID owner, boolean retracted) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO product VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, "Product " + code);
            statement.setString(3, pnk);
            statement.setObject(4, owner);
            statement.setBoolean(5, retracted);
            statement.executeUpdate();
        }
    }

    private int source(String order, UUID vendor, int status, String date, String pnk,
                       int quantity, int initial, int storno, String price, String vat, String currency) throws SQLException {
        var surrogate = nextSnapshot++;
        try (var statement = db.prepareStatement("INSERT INTO emag_order VALUES (?, ?, ?, ?, ?, NULL)")) {
            statement.setInt(1, surrogate);
            statement.setString(2, order);
            statement.setObject(3, vendor);
            statement.setInt(4, status);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.parse(date)));
            statement.executeUpdate();
        }
        try (var statement = db.prepareStatement("INSERT INTO product_in_order VALUES (101, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            statement.setInt(1, surrogate);
            statement.setString(2, pnk);
            statement.setInt(3, quantity);
            statement.setInt(4, initial);
            statement.setInt(5, storno);
            statement.setBigDecimal(6, price == null ? null : new BigDecimal(price));
            statement.setString(7, vat);
            statement.setString(8, currency);
            statement.executeUpdate();
        }
        return surrogate;
    }

    private void voucher(int surrogate, String value, String vat, String who) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO voucher_split VALUES (?, 101, ?, ?, ?, ?)")) {
            statement.setInt(1, nextVoucher++);
            statement.setInt(2, surrogate);
            statement.setBigDecimal(3, value == null ? null : new BigDecimal(value));
            statement.setBigDecimal(4, vat == null ? null : new BigDecimal(vat));
            statement.setString(5, who);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
