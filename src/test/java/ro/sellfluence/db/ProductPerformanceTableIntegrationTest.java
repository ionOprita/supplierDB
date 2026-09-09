package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only the explicitly configured disposable PostgreSQL database, in an isolated schema. */
class ProductPerformanceTableIntegrationTest {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 8, 30);
    private static final LocalDate SECOND_DAY = FIRST_DAY.plusDays(1);
    private static final String PNK = "DDHSVQMBM";
    private static final String OTHER_PNK = "OTHER1234";
    private static final String FOREIGN_PNK = "FOREIGN12";

    private final UUID vendor = UUID.randomUUID();
    private final UUID otherVendor = UUID.randomUUID();
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        String url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        String user = System.getenv("ADS_TEST_DB_USER");
        String password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "product_performance_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute("""
                CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_name TEXT, account TEXT);
                CREATE TABLE product (
                    product_code TEXT PRIMARY KEY, emag_pnk TEXT, vendor UUID REFERENCES vendor(id)
                );
                """);
        for (int version = 36; version <= 39; version++) applyMigration(version);
        addVendor(vendor, "first");
        addVendor(otherVendor, "second");
        addProduct("selected", " \t" + PNK + "\r\n ", vendor);
        addProduct("other", OTHER_PNK, vendor);
        addProduct("foreign", FOREIGN_PNK, otherVendor);
        db.commit();
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (db != null) {
            try (var connection = db) {
                db.rollback();
                db.setAutoCommit(true);
                if (schema != null) execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void exactKnownAdsetPnkTakesPrecedenceOverCampaignFallback() throws SQLException {
        addAdset(vendor, FIRST_DAY, 1, 10, " \n\t" + PNK + "\r  ", "Campaign " + OTHER_PNK,
                "auto", "active", "active", 5);
        addAdset(vendor, FIRST_DAY, 2, 20, OTHER_PNK, "Campaign " + PNK,
                "auto", "active", "active", 500);
        addAdset(vendor, FIRST_DAY, 3, 30, "custom adset", "Campaign [" + PNK + "]",
                "products", "active", "active", 7);
        addAdset(vendor, FIRST_DAY, 4, 40, "custom adset", "Campaign prefix" + PNK,
                "auto", "active", "active", 500);
        addAdset(vendor, FIRST_DAY, 5, 50, null, null, "auto", "active", "active", 500);
        // A PNK belonging only to another vendor is not a known adset PNK for this vendor.
        addAdset(vendor, FIRST_DAY, 6, 60, FOREIGN_PNK, "Campaign " + PNK,
                "auto", "active", "active", 9);

        var rows = ProductPerformanceTable.getDailyAdsets(db, vendor, " \t" + PNK + "\n ");
        assertEquals(List.of(10, 30, 60), rows.stream().map(row -> row.key().adsetId()).toList());
        assertEquals(List.of(5L, 7L, 9L), rows.stream().map(row -> row.metrics().clicks()).toList());
        assertTrue(rows.stream().allMatch(row -> row.matchedPnks().equals(List.of(PNK))));
    }

    @Test
    void campaignFallbackReturnsDistinctCandidatesWithoutMultiplyingAdsets() throws SQLException {
        addProduct("duplicate-trimmed-pnk", PNK, vendor);
        addProduct("blank-pnk", " \t\r\n ", vendor);
        addProduct("null-pnk", null, vendor);
        addAdset(vendor, FIRST_DAY, 1, 10, "custom", "AUTO | " + PNK + " / " + OTHER_PNK + " / " + PNK,
                "auto", "active", "active", 5);
        addAdset(vendor, FIRST_DAY, 2, 20, PNK, "AUTO | " + PNK + " / " + OTHER_PNK,
                "auto", "active", "active", 6);
        addAdset(vendor, FIRST_DAY, 3, 30, "custom", "AUTO | " + PNK + " / " + FOREIGN_PNK,
                "auto", "active", "active", 7);

        var rows = ProductPerformanceTable.getDailyAdsets(db, vendor, PNK);
        assertEquals(3, rows.size());
        assertEquals(List.of(PNK, OTHER_PNK), rows.get(0).matchedPnks());
        assertEquals(List.of(PNK), rows.get(1).matchedPnks());
        assertEquals(List.of(PNK), rows.get(2).matchedPnks());
        assertEquals(5L, rows.getFirst().metrics().clicks());
        var otherRows = ProductPerformanceTable.getDailyAdsets(db, vendor, OTHER_PNK);
        assertEquals(1, otherRows.size());
        assertEquals(10, otherRows.getFirst().key().adsetId());
    }

    @Test
    void bothStatusesMustBeActiveOnTheSameDateAndVendor() throws SQLException {
        addAdset(vendor, FIRST_DAY, 1, 10, PNK, "campaign", "auto", "active", "active", 5);
        addAdset(vendor, SECOND_DAY, 1, 10, PNK, "campaign", "auto", "active", "paused", 500);
        addAdset(vendor, FIRST_DAY, 2, 20, PNK, "campaign", "auto", "paused", "active", 500);
        addAdset(vendor, FIRST_DAY, 3, 30, PNK, "campaign", "auto", "ACTIVE", "active", 500);
        addAdset(vendor, FIRST_DAY, 4, 40, PNK, "campaign", "auto", "active", null, 500);
        addAdset(vendor, SECOND_DAY, 5, 50, PNK, "campaign", "products", "active", "active", 7);
        addAdset(otherVendor, FIRST_DAY, 1, 10, FOREIGN_PNK, "campaign", "auto", "active", "active", 900);

        var rows = ProductPerformanceTable.getDailyAdsets(db, vendor, PNK);
        assertEquals(List.of(FIRST_DAY, SECOND_DAY), rows.stream().map(row -> row.key().reportDate()).toList());
        assertEquals(List.of(5L, 7L), rows.stream().map(row -> row.metrics().clicks()).toList());
        assertTrue(rows.stream().allMatch(row -> vendor.equals(row.key().vendorId())));
        assertEquals(900L, ProductPerformanceTable.getDailyAdsets(db, otherVendor, FOREIGN_PNK)
                .getFirst().metrics().clicks());
        assertTrue(ProductPerformanceTable.getDailyAdsets(db, otherVendor, PNK).isEmpty());
    }

    @Test
    void keywordClassificationUsesFullSnapshotKeyAndIgnoresOnlyNegatives() throws SQLException {
        addAdset(vendor, FIRST_DAY, 1, 10, PNK, "campaign", "keywords", "active", "active", 5);
        addAdset(vendor, SECOND_DAY, 1, 10, PNK, "campaign", "keywords", "active", "active", 6);
        addAdset(vendor, FIRST_DAY, 2, 10, PNK, "campaign", "keywords", "active", "active", 7);
        addAdset(otherVendor, FIRST_DAY, 1, 10, FOREIGN_PNK, "campaign", "keywords", "active", "active", 900);
        addKeyword(vendor, FIRST_DAY, 1, 10, 1, "broad", "active");
        addKeyword(vendor, FIRST_DAY, 1, 10, 2, "broad", "paused");
        addKeyword(vendor, FIRST_DAY, 1, 10, 3, "negative", "active");
        addKeyword(vendor, SECOND_DAY, 1, 10, 1, "exact", "paused");
        addKeyword(vendor, FIRST_DAY, 2, 10, 1, "exact", "active");
        addKeyword(otherVendor, FIRST_DAY, 1, 10, 1, "exact", "active");

        var rows = ProductPerformanceTable.getDailyAdsets(db, vendor, PNK);
        assertEquals(3, rows.size(), "Multiple keywords must never multiply daily adset primitives");
        assertEquals(List.of(List.of("broad"), List.of("exact"), List.of("exact")),
                rows.stream().map(ProductPerformanceTable.DailyAdset::matchTypes).toList());
        assertEquals(List.of(5L, 7L, 6L), rows.stream().map(row -> row.metrics().clicks()).toList());
        var metrics = rows.getFirst().metrics();
        assertEquals(500L, metrics.impressions());
        assertEquals(0, new BigDecimal("2.50").compareTo(metrics.spend()));
        assertEquals(0, new BigDecimal("25.00").compareTo(metrics.sales()));
        assertEquals(3L, metrics.units());
        assertEquals(2L, metrics.salesCount());
    }

    @Test
    void missingMixedAndNullKeywordTypesRemainVisibleForValidation() throws SQLException {
        for (int adset = 10; adset <= 60; adset += 10) {
            addAdset(vendor, FIRST_DAY, 1, adset, PNK, "campaign",
                    adset == 60 ? "auto" : "keywords", "active", "active", 5);
        }
        addKeyword(vendor, FIRST_DAY, 1, 20, 1, "negative", "active");
        addKeyword(vendor, FIRST_DAY, 1, 30, 1, "broad", "active");
        addKeyword(vendor, FIRST_DAY, 1, 30, 2, "exact", "active");
        addKeyword(vendor, FIRST_DAY, 1, 40, 1, null, "active");
        addKeyword(vendor, FIRST_DAY, 1, 40, 2, "broad", "active");
        addKeyword(vendor, FIRST_DAY, 1, 50, 1, "unexpected", "active");
        addKeyword(vendor, FIRST_DAY, 1, 60, 1, "broad", "active");

        var rows = ProductPerformanceTable.getDailyAdsets(db, vendor, PNK);
        assertEquals(List.of(List.of(), List.of(), List.of("broad", "exact"),
                        List.of("(missing)", "broad"), List.of("unexpected"), List.of()),
                rows.stream().map(ProductPerformanceTable.DailyAdset::matchTypes).toList());
    }

    @Test
    void nullablePrimitivesAndEmptyProductSelectionsArePreserved() throws SQLException {
        addAdset(vendor, FIRST_DAY, 1, 10, PNK, "campaign", "products", "active", "active", null);
        execute("""
                UPDATE ads_adset SET summary_spent = NULL, summary_sales = NULL,
                                     summary_sold_units = NULL, summary_sales_count = NULL
                """);
        var metrics = ProductPerformanceTable.getDailyAdsets(db, vendor, PNK).getFirst().metrics();
        assertNull(metrics.impressions());
        assertNull(metrics.clicks());
        assertNull(metrics.spend());
        assertNull(metrics.sales());
        assertNull(metrics.units());
        assertNull(metrics.salesCount());
        assertTrue(ProductPerformanceTable.getDailyAdsets(db, vendor, null).isEmpty());
        assertTrue(ProductPerformanceTable.getDailyAdsets(db, vendor, " \t ").isEmpty());
        assertTrue(ProductPerformanceTable.getDailyAdsets(db, vendor, "UNKNOWN").isEmpty());
    }

    private void addVendor(UUID id, String account) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO vendor(id, vendor_name, account) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, account);
            statement.setString(3, account);
            statement.executeUpdate();
        }
    }

    private void addProduct(String code, String pnk, UUID vendorId) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO product(product_code, emag_pnk, vendor) VALUES (?, ?, ?)")) {
            statement.setString(1, code);
            statement.setString(2, pnk);
            statement.setObject(3, vendorId);
            statement.executeUpdate();
        }
    }

    private void addAdset(UUID vendorId, LocalDate date, int campaignId, int adsetId, String name,
                          String campaignName, String targeting, String adsetStatus, String campaignStatus,
                          Integer clicks) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO ads_campaign(vendor_id, report_date, campaign_id, name, status, inherited_status)
                VALUES (?, ?, ?, ?, ?, 'paused') ON CONFLICT DO NOTHING
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, date);
            statement.setInt(3, campaignId);
            statement.setString(4, campaignName);
            statement.setString(5, campaignStatus);
            statement.executeUpdate();
        }
        try (var statement = db.prepareStatement("""
                INSERT INTO ads_adset(vendor_id, report_date, campaign_id, adset_id, name, targeting,
                    status, inherited_status, summary_clicks, summary_impressions, summary_spent,
                    summary_sales, summary_sold_units, summary_sales_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'paused', ?, ?, 2.50, 25.00, 3, 2)
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, date);
            statement.setInt(3, campaignId);
            statement.setInt(4, adsetId);
            statement.setString(5, name);
            statement.setString(6, targeting);
            statement.setString(7, adsetStatus);
            statement.setObject(8, clicks);
            statement.setObject(9, clicks == null ? null : clicks * 100);
            statement.executeUpdate();
        }
    }

    private void addKeyword(UUID vendorId, LocalDate date, int campaignId, int adsetId,
                            int keywordId, String matchType, String status) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO ads_keyword(vendor_id, report_date, campaign_id, adset_id, keyword_id,
                    keyword, match_type, status, summary_clicks)
                VALUES (?, ?, ?, ?, ?, 'keyword', ?, ?, 999999)
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, date);
            statement.setInt(3, campaignId);
            statement.setInt(4, adsetId);
            statement.setInt(5, keywordId);
            statement.setString(6, matchType);
            statement.setString(7, status);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private void applyMigration(int version) throws Exception {
        var type = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion" + version);
        var method = type.getDeclaredMethod("version" + version, Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(null, db);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception failure) throw failure;
            throw error;
        }
    }
}
