package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideChange;
import ro.sellfluence.db.ProfitCalculatorTable.OverrideConflictException;
import ro.sellfluence.db.versions.EmagMirrorDBVersion50;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only the explicitly configured disposable database, never the application's database. */
class ProfitCalculatorTableIntegrationTest {
    private static final YearMonth PREVIOUS_MONTH = YearMonth.of(2026, 10);
    private Connection db;
    private String schema;

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
        schema = "profit_calculator_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("""
                CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_group TEXT, company_name TEXT);
                CREATE TABLE product (
                    product_code TEXT PRIMARY KEY, name TEXT, emag_pnk TEXT,
                    vendor UUID REFERENCES vendor(id), category TEXT,
                    retracted BOOLEAN NOT NULL, continue_to_sell BOOLEAN NOT NULL,
                    emag_sale_price_ron NUMERIC, income_profit_tax TEXT, vat_payer BOOLEAN,
                    emag_commission NUMERIC
                );
                CREATE TABLE emag_order (
                    surrogate_id SERIAL PRIMARY KEY, id TEXT NOT NULL,
                    vendor_id UUID REFERENCES vendor(id), status INTEGER NOT NULL,
                    date TIMESTAMP, is_storno BOOLEAN,
                    UNIQUE (id, vendor_id, status)
                );
                CREATE TABLE product_in_order (
                    id INTEGER NOT NULL, emag_order_surrogate_id INTEGER REFERENCES emag_order(surrogate_id),
                    part_number_key TEXT, quantity INTEGER, initial_qty INTEGER, storno_qty INTEGER,
                    PRIMARY KEY (id, emag_order_surrogate_id)
                );
                """);
        EmagMirrorDBVersion50.version50(db);
    }

    @AfterEach
    void discardIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void filtersByGroupAndCategoryAcrossVendorsAndIncludesRetractedProducts() throws SQLException {
        var first = addVendor("S");
        var second = addVendor("\tS\u2003");
        var foreign = addVendor("J");
        addProduct("active", "S. 1 - Active", "PNK1", first, "Trimmers", false, true);
        addProduct("retracted", null, null, second, "\u2003Trimmers\t", true, false);
        addProduct("other-category", "Other", "PNK3", first, "Epilators", false, true);
        addProduct("other-group", "Other group", "PNK4", foreign, "Trimmers", false, true);

        var products = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers", PREVIOUS_MONTH);

        assertEquals(List.of("active", "retracted"), products.stream()
                .map(ProfitCalculatorTable.CategoryProduct::productCode).sorted().toList());
        var retracted = products.stream().filter(product -> product.productCode().equals("retracted"))
                .findFirst().orElseThrow();
        assertTrue(retracted.retracted());
        assertNull(retracted.name());
        assertNull(retracted.pnk());
        assertTrue(ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers' OR TRUE --", PREVIOUS_MONTH).isEmpty());
        assertTrue(ProfitCalculatorTable.getCategoryProducts(db, "Z", "Trimmers", PREVIOUS_MONTH).isEmpty());
    }

    @Test
    void readsNullableReferenceValuesAndPreservesZeroPriceAndFalseVatStatus() throws SQLException {
        var vendor = addVendor("S");
        addProduct("reference", "S. 1 - Reference", "PNK1", vendor, "Trimmers", false, true);
        addProduct("zero", "S. 2 - Zero", "PNK2", vendor, "Trimmers", false, true);
        addProduct("missing", "S. 3 - Missing", "PNK3", vendor, "Trimmers", false, true);
        setReferenceValues("reference", new BigDecimal("169.99"), "Profit", true);
        setReferenceValues("zero", BigDecimal.ZERO, "Venit", false);
        setCommission("reference", new BigDecimal("0.18"));
        setCommission("zero", BigDecimal.ZERO);

        var products = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers", PREVIOUS_MONTH);
        var reference = products.stream().filter(product -> product.productCode().equals("reference"))
                .findFirst().orElseThrow();
        var zero = products.stream().filter(product -> product.productCode().equals("zero"))
                .findFirst().orElseThrow();
        var missing = products.stream().filter(product -> product.productCode().equals("missing"))
                .findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("169.99").compareTo(reference.emagSalePriceRon()));
        assertEquals("Profit", reference.incomeProfitTax());
        assertEquals(Boolean.TRUE, reference.vatPayer());
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.emagSalePriceRon()));
        assertEquals("Venit", zero.incomeProfitTax());
        assertEquals(Boolean.FALSE, zero.vatPayer());
        assertNull(missing.emagSalePriceRon());
        assertNull(missing.incomeProfitTax());
        assertNull(missing.vatPayer());
        assertEquals(new BigDecimal("0.18"), reference.emagCommission());
        assertEquals(BigDecimal.ZERO, zero.emagCommission());
        assertNull(missing.emagCommission());
    }

    @Test
    void countsGrossFinalizedLinesBySellingGroupAndCalendarMonthWithoutLosingLineOrAccountIdentity()
            throws SQLException {
        var owner = addVendor("S");
        var sameGroupFbeAccount = addVendor("\tS\u2003");
        var foreignAccount = addVendor("J");
        addProduct("target", "S. 1 - Target", "TARGET", owner, "Trimmers", false, true);

        var first = addOrder("same-order-id", owner, 4, "2026-10-01T00:00:00", false);
        addLine(first, 1, "TARGET", 2, 200, 100);
        addLine(first, 2, "TARGET", 3, 300, 100);
        // Same order ID in a second selected-group account is a distinct sale.
        var fbe = addOrder("same-order-id", sameGroupFbeAccount, 4, "2026-10-31T23:59:59.999999", true);
        addLine(fbe, 1, "TARGET", 7, 700, 100);
        var paired = addOrder("paired", owner, 4, "2026-10-15T12:00:00", false);
        addLine(paired, 1, "TARGET", 3, 300, 100);
        var storno = addOrder("paired", owner, 5, "2026-10-28T12:00:00", true);
        addLine(storno, 1, "TARGET", 0, 3, 3);
        var onlyStorno = addOrder("only-storno", owner, 5, "2026-10-15T12:00:00", true);
        addLine(onlyStorno, 1, "TARGET", 0, 100, 100);
        var foreign = addOrder("same-order-id", foreignAccount, 4, "2026-10-15T12:00:00", false);
        addLine(foreign, 1, "TARGET", 1000, 1000, 0);
        for (var status : List.of(0, 1, 2, 3)) {
            var ignored = addOrder("not-finalized-" + status, owner, status, "2026-10-15T12:00:00", false);
            addLine(ignored, 1, "TARGET", 100, 100, 0);
        }
        var before = addOrder("before-month", owner, 4, "2026-09-30T23:59:59.999999", false);
        addLine(before, 1, "TARGET", 100, 100, 0);
        var after = addOrder("end-exclusive", owner, 4, "2026-11-01T00:00:00", false);
        addLine(after, 1, "TARGET", 100, 100, 0);
        addLine(paired, 2, "DIFFERENT-PNK", 100, 100, 0);

        assertEquals(15L, product("target").previousMonthGrossUnits());
        var september = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers", YearMonth.of(2026, 9));
        assertEquals(100L, september.getFirst().previousMonthGrossUnits());
        var november = ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers", YearMonth.of(2026, 11));
        assertEquals(100L, november.getFirst().previousMonthGrossUnits());
    }

    @Test
    void distinguishesNoMatchingSalesAndTrueZeroFromUnknownFinalizedQuantities() throws SQLException {
        var vendor = addVendor("S");
        addProduct("none", "S. 1 - No sales", "NONE", vendor, "Trimmers", false, true);
        addProduct("zero", "S. 2 - Zero sales", "ZERO", vendor, "Trimmers", false, true);
        addProduct("unknown", "S. 3 - Unknown", "UNKNOWN", vendor, "Trimmers", false, true);
        addProduct("only-storno", "S. 4 - Only storno", "ONLY-STORNO", vendor, "Trimmers", false, true);
        var finalized = addOrder("finalized", vendor, 4, "2026-10-15T12:00:00", false);
        addLine(finalized, 1, "ZERO", 0, 100, 0);
        addLine(finalized, 2, "UNKNOWN", null, 100, 0);
        addLine(finalized, 3, "UNKNOWN", 6, 6, 0);
        var storno = addOrder("storno", vendor, 5, "2026-10-15T12:00:00", true);
        addLine(storno, 1, "ONLY-STORNO", 0, 50, 50);

        assertEquals(0L, product("none").previousMonthGrossUnits());
        assertEquals(0L, product("zero").previousMonthGrossUnits());
        assertEquals(0L, product("only-storno").previousMonthGrossUnits());
        assertNull(product("unknown").previousMonthGrossUnits(), "A partial sum must not hide an unknown quantity");
    }

    @Test
    void savesAndReloadsSharedOverridesWithoutChangingProductMasterData() throws SQLException {
        var vendor = addVendor("S");
        execute("UPDATE vendor SET company_name = 'Sellfusion SRL'");
        addProduct("reference", "S. 1 - Reference", "PNK1", vendor, "Trimmers", false, true);
        setReferenceValues("reference", new BigDecimal("169.99"), "Profit", true);

        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("reference", 0, Map.of(
                "salePriceRon", "149.99", "commissionRate", "0.14", "purchasePriceUsd", "10.25",
                "transportPriceUsd", "0", "usdRonRate", "4.5555", "fulfillmentSource", "eMAG"))));
        var saved = product("reference");
        assertEquals("Sellfusion SRL", saved.companyName());
        assertEquals(1, saved.overrideRevision());
        assertEquals(0, new BigDecimal("149.99").compareTo(saved.overrides().salePriceRon()));
        assertEquals(0, new BigDecimal("0.14").compareTo(saved.overrides().commissionRate()));
        assertEquals(0, new BigDecimal("10.25").compareTo(saved.overrides().purchasePriceUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.overrides().transportPriceUsd()));
        assertEquals(0, new BigDecimal("4.5555").compareTo(saved.overrides().usdRonRate()));
        assertEquals("eMAG", saved.overrides().fulfillmentSource());
        assertEquals(0, new BigDecimal("169.99").compareTo(saved.emagSalePriceRon()));
        assertEquals("Profit", saved.incomeProfitTax());
        assertTrue(saved.vatPayer());

        var changed = new LinkedHashMap<String, String>();
        changed.put("salePriceRon", null);
        changed.put("commissionRate", "0");
        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("reference", 1, changed)));
        var cleared = product("reference");
        assertEquals(2, cleared.overrideRevision());
        assertNull(cleared.overrides().salePriceRon());
        assertEquals(0, BigDecimal.ZERO.compareTo(cleared.overrides().commissionRate()));
        assertEquals(0, new BigDecimal("10.25").compareTo(cleared.overrides().purchasePriceUsd()));
        assertEquals("eMAG", cleared.overrides().fulfillmentSource());
    }

    @Test
    void staleRevisionsRollbackTheWholeBatchIncludingEarlierInserts() throws SQLException {
        var vendor = addVendor("S");
        addProduct("a-first", "S. 1 - First", "PNK1", vendor, "Trimmers", false, true);
        addProduct("z-conflict", "S. 2 - Conflict", "PNK2", vendor, "Trimmers", false, true);
        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 0, Map.of("salePriceRon", "100"))));

        var conflict = assertThrows(OverrideConflictException.class, () -> ProfitCalculatorTable.saveOverrides(db, List.of(
                new OverrideChange("a-first", 0, Map.of("salePriceRon", "20")),
                new OverrideChange("z-conflict", 0, Map.of("salePriceRon", "200")))));
        assertEquals("z-conflict", conflict.productCode());
        assertEquals(0, product("a-first").overrideRevision());
        assertNull(product("a-first").overrides().salePriceRon());
        assertEquals(0, new BigDecimal("100").compareTo(product("z-conflict").overrides().salePriceRon()));

        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 1, Map.of("salePriceRon", "120"))));
        assertThrows(OverrideConflictException.class, () -> ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("z-conflict", 1, Map.of("commissionRate", "0.2")))));
        assertEquals(2, product("z-conflict").overrideRevision());
        assertNull(product("z-conflict").overrides().commissionRate());
    }

    @Test
    void clearingEveryFieldRetainsItsRevisionAndOverridesCanBeStoredForIneligibleProducts() throws SQLException {
        var vendor = addVendor("S");
        addProduct("retracted", "S. 1 - Retracted", null, vendor, "Trimmers", true, false);
        ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("retracted", 0, Map.of("salePriceRon", "0"))));
        var clearing = new LinkedHashMap<String, String>();
        clearing.put("salePriceRon", null);
        ProfitCalculatorTable.saveOverrides(db, List.of(new OverrideChange("retracted", 1, clearing)));

        var saved = product("retracted");
        assertEquals(2, saved.overrideRevision());
        assertEquals(ProfitCalculatorTable.Overrides.empty(), saved.overrides());
        assertTrue(saved.retracted());
        assertNull(saved.pnk());
        assertThrows(IllegalArgumentException.class, () -> ProfitCalculatorTable.saveOverrides(db,
                List.of(new OverrideChange("not-a-product", 0, Map.of("salePriceRon", "1")))));
        assertEquals(2, product("retracted").overrideRevision());
    }

    @Test
    void concurrentCreationAndUpdateRejectTheOtherWritersStaleRevision() throws Exception {
        var vendor = addVendor("S");
        addProduct("shared", "S. 1 - Shared", "SHARED", vendor, "Trimmers", false, true);
        db.commit();
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        try (var other = DriverManager.getConnection(System.getenv("ADS_TEST_DB_URL"), properties)) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("SET lock_timeout TO '5s'");
            }
            assertConcurrentConflict(other, new OverrideChange("shared", 0, Map.of("salePriceRon", "100")),
                    new OverrideChange("shared", 0, Map.of("salePriceRon", "200")));
            assertEquals(1, product("shared").overrideRevision());
            assertConcurrentConflict(other, new OverrideChange("shared", 1, Map.of("salePriceRon", "110")),
                    new OverrideChange("shared", 1, Map.of("commissionRate", "0.2")));
            assertEquals(2, product("shared").overrideRevision());
            assertEquals(0, new BigDecimal("110").compareTo(product("shared").overrides().salePriceRon()));
            assertNull(product("shared").overrides().commissionRate());
            other.rollback();
        }
    }

    private void assertConcurrentConflict(Connection other, OverrideChange first, OverrideChange second) throws Exception {
        ProfitCalculatorTable.saveOverrides(db, List.of(first));
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var conflict = executor.submit(() -> {
                started.countDown();
                try {
                    ProfitCalculatorTable.saveOverrides(other, List.of(second));
                    return null;
                } catch (OverrideConflictException error) {
                    return error;
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            db.commit();
            assertNotNull(conflict.get(5, TimeUnit.SECONDS));
        }
    }

    private ProfitCalculatorTable.CategoryProduct product(String code) throws SQLException {
        return ProfitCalculatorTable.getCategoryProducts(db, "S", "Trimmers", PREVIOUS_MONTH).stream()
                .filter(product -> product.productCode().equals(code)).findFirst().orElseThrow();
    }

    private void setReferenceValues(String code, BigDecimal price, String taxRegime, Boolean vatPayer)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                UPDATE product SET emag_sale_price_ron = ?, income_profit_tax = ?, vat_payer = ?
                WHERE product_code = ?
                """)) {
            statement.setBigDecimal(1, price);
            statement.setString(2, taxRegime);
            statement.setObject(3, vatPayer);
            statement.setString(4, code);
            statement.executeUpdate();
        }
    }

    private void setCommission(String code, BigDecimal commission) throws SQLException {
        try (var statement = db.prepareStatement("UPDATE product SET emag_commission = ? WHERE product_code = ?")) {
            statement.setBigDecimal(1, commission);
            statement.setString(2, code);
            statement.executeUpdate();
        }
    }

    private int addOrder(String id, UUID seller, int status, String date, boolean isStorno) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO emag_order (id, vendor_id, status, date, is_storno)
                VALUES (?, ?, ?, ?, ?) RETURNING surrogate_id
                """)) {
            statement.setString(1, id);
            statement.setObject(2, seller);
            statement.setInt(3, status);
            statement.setTimestamp(4, Timestamp.valueOf(LocalDateTime.parse(date)));
            statement.setBoolean(5, isStorno);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void addLine(int order, int line, String pnk, Integer quantity, Integer initial, Integer storno)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO product_in_order
                    (id, emag_order_surrogate_id, part_number_key, quantity, initial_qty, storno_qty)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, line);
            statement.setInt(2, order);
            statement.setString(3, pnk);
            statement.setObject(4, quantity);
            statement.setObject(5, initial);
            statement.setObject(6, storno);
            statement.executeUpdate();
        }
    }

    private UUID addVendor(String group) throws SQLException {
        var id = UUID.randomUUID();
        try (var statement = db.prepareStatement("INSERT INTO vendor (id, vendor_group) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, group);
            statement.executeUpdate();
        }
        return id;
    }

    private void addProduct(String code, String name, String pnk, UUID vendor, String category,
                            boolean retracted, boolean continueToSell) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO product (product_code, name, emag_pnk, vendor, category, retracted, continue_to_sell)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, pnk);
            statement.setObject(4, vendor);
            statement.setString(5, category);
            statement.setBoolean(6, retracted);
            statement.setBoolean(7, continueToSell);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
