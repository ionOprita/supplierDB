package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class OverviewTableIntegrationTest {
    private static final LocalDate THROUGH = LocalDate.of(2026, 11, 3);
    private static final UUID ALPHA = new UUID(0, 1);
    private static final UUID ALPHA_FBE = new UUID(0, 2);
    private static final UUID BETA = new UUID(0, 3);
    private static final UUID FALLBACK_A = new UUID(0, 4);
    private static final UUID FALLBACK_B = new UUID(0, 5);

    private Connection db;
    private String schema;
    private int nextSnapshot = 1;
    private int nextItem = 1;

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
        schema = "overview_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY, vendor_name TEXT, company_name TEXT)");
        execute("""
                CREATE TABLE emag_order (
                    surrogate_id INTEGER PRIMARY KEY, vendor_id UUID, id TEXT,
                    status INTEGER, date TIMESTAMP, modified TIMESTAMP,
                    UNIQUE (vendor_id, id, status)
                )
                """);
        execute("""
                CREATE TABLE product_in_order (
                    id INTEGER, emag_order_surrogate_id INTEGER, product_id INTEGER, mkt_id INTEGER,
                    PRIMARY KEY (id, emag_order_surrogate_id)
                )
                """);
        execute("CREATE TABLE rma_result (emag_id INTEGER PRIMARY KEY, order_id TEXT, request_status INTEGER, date TIMESTAMP)");
        execute("""
                CREATE TABLE emag_returned_products (
                    id INTEGER PRIMARY KEY, emag_id INTEGER, product_id INTEGER,
                    product_emag_id INTEGER, quantity INTEGER
                )
                """);
        vendor(ALPHA, "Alpha main", "Alpha SRL");
        vendor(ALPHA_FBE, "Alpha FBE", " Alpha SRL ");
        vendor(BETA, "Beta main", "Beta SRL");
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
    void combinesCompanyAccountsAndKeepsMissingCompanyVendorsSeparateIncludingZeroActivity() throws SQLException {
        vendor(FALLBACK_A, "Same fallback", null);
        vendor(FALLBACK_B, "Same fallback", " \t ");
        order(ALPHA, "main", 1, "2026-11-01T00:00:00", null);
        order(ALPHA_FBE, "fbe", 4, "2026-11-01T12:00:00", null);
        order(FALLBACK_A, "first-fallback", 1, "2026-11-02T12:00:00", null);
        order(FALLBACK_B, "second-fallback", 1, "2026-11-03T12:00:00", null);

        var data = OverviewTable.read(db, THROUGH);

        assertEquals("2026-11", data.month());
        assertEquals("2026-11-03", data.throughDate());
        assertEquals(List.of("2026-11-01", "2026-11-02", "2026-11-03"), data.days());
        assertEquals(List.of("Alpha SRL", "Beta SRL", "Same fallback", "Same fallback"),
                data.companies().stream().map(OverviewTable.CompanySeries::name).toList());
        var expectedKeys = List.of("company:Alpha SRL", "company:Beta SRL",
                "vendor:" + FALLBACK_A, "vendor:" + FALLBACK_B);
        assertEquals(expectedKeys, data.companies().stream().map(OverviewTable.CompanySeries::key).toList());
        assertEquals(4L, data.companies().stream().map(OverviewTable.CompanySeries::key).distinct().count());
        assertEquals(expectedKeys, OverviewTable.read(db, THROUGH.minusMonths(1)).companies().stream()
                .map(OverviewTable.CompanySeries::key).toList());
        assertEquals(List.of(2L, 0L, 0L), data.companies().get(0).orders());
        assertEquals(List.of(0L, 0L, 0L), data.companies().get(1).orders());
        assertEquals(List.of(0L, 1L, 0L), data.companies().get(2).orders());
        assertEquals(List.of(0L, 0L, 1L), data.companies().get(3).orders());
        assertTrue(data.companies().stream().allMatch(c -> c.returns().equals(List.of(0L, 0L, 0L))));
        assertEquals(0, data.unassignedReturns());
    }

    @Test
    void keepsFallbackCompanyKeyWhenTheVendorDisplayNameChanges() throws SQLException {
        vendor(FALLBACK_A, "Original display name", null);
        var before = company(OverviewTable.read(db, THROUGH), "Original display name");
        try (var statement = db.prepareStatement("UPDATE vendor SET vendor_name = ? WHERE id = ?")) {
            statement.setString(1, "Renamed display name");
            statement.setObject(2, FALLBACK_A);
            statement.executeUpdate();
        }

        var after = company(OverviewTable.read(db, THROUGH.minusMonths(1)), "Renamed display name");

        assertEquals("vendor:" + FALLBACK_A, before.key());
        assertEquals(before.key(), after.key());
    }

    @Test
    void countsTransactionsByOriginalPlacementAndUsesLatestStatusBeforeExcludingCancellations() throws SQLException {
        var original = order(ALPHA, "snapshots", 1, "2026-11-01T09:00:00", "2026-11-01T09:00:00");
        order(ALPHA, "snapshots", 4, "2026-11-01T09:00:00", "2026-11-02T09:00:00");
        order(ALPHA, "snapshots", 5, "2026-11-02T09:00:00", "2026-11-03T09:00:00");
        item(original, 101, 201);
        item(original, 102, 202);
        order(ALPHA, "canceled", 5, "2026-11-01T10:00:00", "2026-11-01T10:00:00");
        order(ALPHA, "canceled", 0, "2026-11-01T10:00:00", "2026-11-03T10:00:00");
        order(ALPHA, "tie-canceled", 4, "2026-11-02T10:00:00", "2026-11-03T10:00:00");
        order(ALPHA, "tie-canceled", 0, "2026-11-02T10:00:00", "2026-11-03T10:00:00");
        order(ALPHA, "reopened", 0, "2026-11-02T11:00:00", "2026-11-02T11:00:00");
        order(ALPHA, "reopened", 1, "2026-11-02T11:00:00", "2026-11-03T11:00:00");
        order(ALPHA, "old-sale", 4, "2026-10-31T23:00:00", null);
        order(ALPHA, "old-sale", 5, "2026-11-01T12:00:00", "2026-11-02T12:00:00");
        order(ALPHA, "shared-id", 2, "2026-11-03T12:00:00", null);
        order(BETA, "shared-id", 3, "2026-11-03T12:00:00", null);
        order(ALPHA, "midnight", 1, "2026-11-01T00:00:00", null);
        order(ALPHA, "last-second", 1, "2026-11-03T23:59:59", null);
        order(ALPHA, "tomorrow", 1, "2026-11-04T00:00:00", null);

        var data = OverviewTable.read(db, THROUGH);

        assertEquals(List.of(2L, 1L, 2L), company(data, "Alpha SRL").orders());
        assertEquals(List.of(0L, 0L, 1L), company(data, "Beta SRL").orders());
    }

    @Test
    void countsFinalizedReturnRequestsOnceRegardlessOfItemsOrSnapshotsUsingRequestDate() throws SQLException {
        var first = order(ALPHA, "old-order", 4, "2026-10-01T09:00:00", null);
        var returned = order(ALPHA, "old-order", 5, "2026-10-01T09:00:00", "2026-11-02T09:00:00");
        order(ALPHA_FBE, "old-order", 4, "2026-10-01T09:00:00", null);
        item(first, 101, 201);
        item(first, 102, 202);
        item(returned, 101, 201);
        item(returned, 102, 202);
        rma(100, "old-order", 7, "2026-11-01T00:00:00");
        returnedItem(100, 101, 201, 10);
        returnedItem(100, 102, 202, 2);
        rma(101, "old-order", 7, "2026-11-02T12:00:00");
        returnedItem(101, 101, 201, 1);
        rma(102, "old-order", 7, "2026-10-31T23:59:59");
        rma(103, "old-order", 7, "2026-11-04T00:00:00");
        rma(104, "old-order", 7, "2026-11-03T23:59:59");
        for (int status = 1; status < 7; status++) {
            rma(110 + status, "old-order", status, "2026-11-03T12:00:00");
        }

        var data = OverviewTable.read(db, THROUGH);

        assertEquals(List.of(1L, 1L, 1L), company(data, "Alpha SRL").returns());
        assertEquals(List.of(0L, 0L, 0L), company(data, "Alpha SRL").orders());
        assertEquals(List.of(0L, 0L, 0L), company(data, "Beta SRL").returns());
        assertEquals(0, data.unassignedReturns());
    }

    @Test
    void resolvesCollidingOrderIdsOnlyWhenExactlyOneCompanyMatchesEveryReturnedProductAcrossItsSnapshots() throws SQLException {
        var first = order(ALPHA, "collision", 4, "2026-10-01T09:00:00", null);
        var returned = order(ALPHA, "collision", 5, "2026-10-01T09:00:00", null);
        var fbe = order(ALPHA_FBE, "collision", 4, "2026-10-01T09:00:00", null);
        var beta = order(BETA, "collision", 4, "2026-10-01T09:00:00", null);
        item(first, 101, 201);
        item(returned, 102, 202);
        item(fbe, 103, 203);
        item(beta, 101, 201);
        item(beta, 104, 204);
        rma(200, "collision", 7, "2026-11-01T12:00:00");
        returnedItem(200, 101, 201, 1);
        returnedItem(200, 102, 202, 1);
        returnedItem(200, 103, 203, 1);
        rma(201, "collision", 7, "2026-11-02T12:00:00");
        returnedItem(201, 104, 204, 7);

        var data = OverviewTable.read(db, THROUGH);

        assertEquals(List.of(1L, 0L, 0L), company(data, "Alpha SRL").returns());
        assertEquals(List.of(0L, 1L, 0L), company(data, "Beta SRL").returns());
        assertEquals(0, data.unassignedReturns());
    }

    @Test
    void reportsMissingAndAmbiguousOwnersWithoutArbitraryAssignment() throws SQLException {
        var alpha = order(ALPHA, "collision", 4, "2026-10-01T09:00:00", null);
        var beta = order(BETA, "collision", 4, "2026-10-01T09:00:00", null);
        item(alpha, 101, 201);
        item(beta, 101, 201);
        rma(300, "missing-order", 7, "2026-11-01T12:00:00");
        rma(301, "collision", 7, "2026-11-01T12:00:00");
        rma(302, "collision", 7, "2026-11-02T12:00:00");
        returnedItem(302, 101, 201, 1);
        rma(303, "collision", 7, "2026-11-03T12:00:00");
        returnedItem(303, 101, 999, 1);

        var data = OverviewTable.read(db, THROUGH);

        assertEquals(4, data.unassignedReturns());
        assertTrue(data.companies().stream().allMatch(c -> c.returns().equals(List.of(0L, 0L, 0L))));
    }

    @Test
    void includesLeapDayAndSupportsAnEmptyCompanyList() throws SQLException {
        order(ALPHA, "leap-day", 4, "2028-02-29T23:59:59", null);
        order(ALPHA, "march", 4, "2028-03-01T00:00:00", null);

        var data = OverviewTable.read(db, LocalDate.of(2028, 2, 29));

        assertEquals("2028-02", data.month());
        assertEquals(29, data.days().size());
        assertEquals("2028-02-29", data.days().getLast());
        assertEquals(1L, company(data, "Alpha SRL").orders().getLast());
        assertEquals(1L, company(data, "Alpha SRL").orders().stream().mapToLong(Long::longValue).sum());
        execute("DELETE FROM vendor");
        assertTrue(OverviewTable.read(db, THROUGH).companies().isEmpty());
    }

    private static OverviewTable.CompanySeries company(OverviewTable.OverviewData data, String name) {
        return data.companies().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private void vendor(UUID id, String name, String company) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO vendor VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setString(3, company);
            statement.executeUpdate();
        }
    }

    private int order(UUID vendor, String id, int status, String date, String modified) throws SQLException {
        var surrogate = nextSnapshot++;
        try (var statement = db.prepareStatement("INSERT INTO emag_order VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, surrogate);
            statement.setObject(2, vendor);
            statement.setString(3, id);
            statement.setInt(4, status);
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.parse(date)));
            statement.setTimestamp(6, modified == null ? null : Timestamp.valueOf(LocalDateTime.parse(modified)));
            statement.executeUpdate();
        }
        return surrogate;
    }

    private void item(int surrogate, int product, int marketplaceProduct) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO product_in_order VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, nextItem++);
            statement.setInt(2, surrogate);
            statement.setInt(3, product);
            statement.setInt(4, marketplaceProduct);
            statement.executeUpdate();
        }
    }

    private void rma(int id, String order, int status, String date) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO rma_result VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, order);
            statement.setInt(3, status);
            statement.setTimestamp(4, Timestamp.valueOf(LocalDateTime.parse(date)));
            statement.executeUpdate();
        }
    }

    private void returnedItem(int rma, int product, int marketplaceProduct, int quantity) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO emag_returned_products VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, nextItem++);
            statement.setInt(2, rma);
            statement.setInt(3, product);
            statement.setInt(4, marketplaceProduct);
            statement.setInt(5, quantity);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
