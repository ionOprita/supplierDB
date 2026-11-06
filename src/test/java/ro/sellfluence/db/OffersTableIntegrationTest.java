package ro.sellfluence.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.sellfluence.emagdashboard.Measurements;
import ro.sellfluence.emagdashboard.Offer;
import ro.sellfluence.emagdashboard.Offers;
import ro.sellfluence.emagdashboard.OffersData;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class OffersTableIntegrationTest {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 9, 23);
    private static final LocalDate SECOND_DAY = FIRST_DAY.plusDays(1);
    private static final List<String> CHILD_TABLES = List.of(
            "offers_stock", "offers_price", "offers_links", "offers_supply_recommendation", "offers_measurement"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UUID firstVendor = UUID.randomUUID();
    private final UUID secondVendor = UUID.randomUUID();
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "offers_storage_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        execute("CREATE TABLE vendor (id UUID PRIMARY KEY)");
        applyMigration();
        try (var statement = db.prepareStatement("INSERT INTO vendor (id) VALUES (?), (?)")) {
            statement.setObject(1, firstVendor);
            statement.setObject(2, secondVendor);
            statement.executeUpdate();
        }
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
    void storesEveryRecordComponentWithoutLosingPrecisionOrListOrder() throws Exception {
        var offer = fullOffer("offer-1");

        assertEquals(1, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(offer)));

        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", offer);
        assertStoredRecord("offers_stock", offer.offerStock());
        assertStoredRecord("offers_price", offer.offerPrice());
        assertStoredRecord("offers_links", offer.links());
        assertStoredRecord("offers_supply_recommendation", offer.supplyRecommendationData());
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT * FROM offers_measurement ORDER BY position")) {
            for (int position = 0; position < offer.measurements().size(); position++) {
                assertTrue(rows.next());
                assertEquals(position, rows.getInt("position"));
                assertRecordColumns(rows, offer.measurements().get(position));
            }
            assertFalse(rows.next());
        }
        assertEquals(7, count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                """));
        assertEquals(7, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                    AND column_name = 'fetch_date' AND data_type = 'date'
                """));
        assertEquals(4, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'offers_snapshot'
                """), "Snapshot metadata also records live visits collection time");
        assertEquals(1, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name LIKE 'offers_%'
                    AND data_type = 'timestamp with time zone'
                """));
        assertEquals(0, count("SELECT count(*) FROM offers_snapshot WHERE visits_fetched_at IS NOT NULL"),
                "The compatibility overload must not label an imported snapshot as a live visits collection");
    }

    @Test
    void readsHotnessOnExactDatesForSelectedVendorAndTrimmedPnkWithoutVisitsProvenance() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                hotnessOffer("shared", " PNK-1 ", 0, "cold", "COLD"),
                hotnessOffer("different-product", "PNK-2", 2, "hot", "HOT")));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY,
                snapshot(hotnessOffer("shared", "PNK-1", 2, "super_hot", "SUPER HOT")));
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY,
                snapshot(hotnessOffer("shared", "PNK-1", 3, "hot", "OTHER ACCOUNT")));
        var missingDay = SECOND_DAY.plusDays(1);

        var result = OffersTable.getProductHotnessForDates(db, firstVendor, "  PNK-1  ",
                List.of(FIRST_DAY, SECOND_DAY, missingDay));

        assertEquals(new OffersTable.ProductHotness("available", "cold", "COLD", FIRST_DAY), result.get(FIRST_DAY));
        assertEquals(new OffersTable.ProductHotness("available", "super_hot", "SUPER HOT", SECOND_DAY),
                result.get(SECOND_DAY));
        assertEquals(new OffersTable.ProductHotness("missing", null, null, null), result.get(missingDay));
        assertEquals("OTHER ACCOUNT", OffersTable.getProductHotnessForDates(db, secondVendor, "PNK-1",
                List.of(FIRST_DAY)).get(FIRST_DAY).hotnessName());
        assertEquals(0, count("SELECT count(*) FROM offers_snapshot WHERE visits_fetched_at IS NOT NULL"));
    }

    @Test
    void selectsHotnessFromTheSameUniqueOfferAsVisitsIncludingZeroAndUnknownStock() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                hotnessOffer("preferred", "PNK-1", 1, "super_hot", "SUPER HOT"),
                hotnessOffer("old-zero", "PNK-1", 0, "cold", "COLD"),
                hotnessOffer("unknown-stock", "PNK-1", null, "hot", "HOT"),
                hotnessOffer("only-zero", "PNK-2", 0, null, "COLD"),
                hotnessOffer("only-unknown", "PNK-3", null, "unfamiliar_type", null),
                hotnessOffer("negative-stock", "PNK-4", -1, "new_type", "NEW CLASSIFICATION"),
                hotnessOffer("zero-stock", "PNK-4", 0, "cold", "COLD")));

        assertEquals(new OffersTable.ProductHotness("available", "super_hot", "SUPER HOT", FIRST_DAY),
                OffersTable.getProductHotnessForDates(db, firstVendor, "PNK-1", List.of(FIRST_DAY)).get(FIRST_DAY));
        assertEquals(new OffersTable.ProductHotness("available", null, "COLD", FIRST_DAY),
                OffersTable.getProductHotnessForDates(db, firstVendor, "PNK-2", List.of(FIRST_DAY)).get(FIRST_DAY));
        assertEquals(new OffersTable.ProductHotness("available", "unfamiliar_type", null, FIRST_DAY),
                OffersTable.getProductHotnessForDates(db, firstVendor, "PNK-3", List.of(FIRST_DAY)).get(FIRST_DAY));
        assertEquals(new OffersTable.ProductHotness("available", "new_type", "NEW CLASSIFICATION", FIRST_DAY),
                OffersTable.getProductHotnessForDates(db, firstVendor, "PNK-4", List.of(FIRST_DAY)).get(FIRST_DAY));
    }

    @Test
    void keepsHotnessAmbiguousForMultipleEffectiveOffersEvenWhenClassificationsMatch() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                hotnessOffer("active-one", "PNK-1", 1, "hot", "HOT"),
                hotnessOffer("active-two", "PNK-1", 2, "cold", "COLD"),
                hotnessOffer("zero-one", "PNK-2", 0, "hot", "HOT"),
                hotnessOffer("zero-two", "PNK-2", 0, "hot", "HOT")));

        for (var pnk : List.of("PNK-1", "PNK-2")) {
            assertEquals(new OffersTable.ProductHotness("ambiguous", null, null, FIRST_DAY),
                    OffersTable.getProductHotnessForDates(db, firstVendor, pnk, List.of(FIRST_DAY)).get(FIRST_DAY));
        }
    }

    @Test
    void doesNotBorrowMissingHotnessFromAnotherOfferOrEarlierSnapshot() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(hotnessOffer("preferred", "PNK-1", 1, "hot", "HOT")));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot(
                offer("""
                        {"id":"preferred", "docProductPartNumberKey":"PNK-1", "extStock":1}
                        """),
                hotnessOffer("old-zero", "PNK-1", 0, "cold", "COLD"),
                hotnessOffer("blank", "PNK-2", 0, "  ", "\t"),
                hotnessOffer("null", "PNK-3", null, null, null)));

        for (var pnk : List.of("PNK-1", "PNK-2", "PNK-3", "ABSENT-PRODUCT")) {
            assertEquals(new OffersTable.ProductHotness("missing", null, null, SECOND_DAY),
                    OffersTable.getProductHotnessForDates(db, firstVendor, pnk, List.of(SECOND_DAY)).get(SECOND_DAY));
        }
        assertEquals(Map.of(), OffersTable.getProductHotnessForDates(db, firstVendor, "PNK-1", List.of()));
        assertEquals(Map.of(SECOND_DAY, new OffersTable.ProductHotness("missing", null, null, null)),
                OffersTable.getProductHotnessForDates(db, firstVendor, " ", List.of(SECOND_DAY)));
    }

    @Test
    void selectsLatestLiveSnapshotAndKeepsRollingCountersIndependentAcrossVendors() throws SQLException {
        var firstFetch = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        var secondFetch = SECOND_DAY.atTime(10, 6).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", " PNK-1 ", 5, 200L, 100L)), firstFetch);
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY,
                snapshot(visitsOffer("one", "PNK-1", 5, 5_000_000_000L, 0L)), secondFetch);
        OffersTable.storeSnapshot(db, secondVendor, SECOND_DAY,
                snapshot(visitsOffer("one", "PNK-1", 5, 999L, 555L)), secondFetch);
        // A legacy/imported snapshot must not claim the new metric was fetched, even if it has values.
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY.plusDays(1),
                snapshot(visitsOffer("one", "PNK-1", 5, 123L, 123L)));

        assertEquals(new OffersTable.ProductVisits("available", 0L, 5_000_000_000L, SECOND_DAY,
                        secondFetch.atOffset(ZoneOffset.UTC), false),
                OffersTable.getLatestProductVisits(db, firstVendor, " PNK-1 ", SECOND_DAY));
        assertTrue(OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", SECOND_DAY.plusDays(1)).stale());
        assertEquals(555L, OffersTable.getLatestProductVisits(db, secondVendor, "PNK-1", SECOND_DAY).visits14());
        assertEquals(100L, OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", FIRST_DAY).visits14());
        assertEquals(2, count("SELECT count(*) FROM offers_snapshot WHERE vendor_id = '" + firstVendor
                + "' AND visits_fetched_at IS NOT NULL"), "Older live snapshots remain available for future analysis");
    }

    @Test
    void readsExactHistoricalDaysInOneBatchWithoutBorrowingMissingOrImportedObservations() throws SQLException {
        var firstFetch = FIRST_DAY.atTime(22, 15).toInstant(ZoneOffset.UTC);
        var missingProductDay = FIRST_DAY.plusDays(2);
        var importedDay = FIRST_DAY.plusDays(3);
        var missingCounterDay = FIRST_DAY.plusDays(4);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", " PNK-1 ", 0, 5_000_000_000L, 0L)), firstFetch);
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY,
                snapshot(visitsOffer("one", "PNK-1", 1, 999L, 999L)), firstFetch);
        OffersTable.storeSnapshot(db, firstVendor, missingProductDay, snapshot(),
                missingProductDay.atTime(22, 15).toInstant(ZoneOffset.UTC));
        OffersTable.storeSnapshot(db, firstVendor, importedDay,
                snapshot(visitsOffer("one", "PNK-1", 1, 100L, 50L)));
        OffersTable.storeSnapshot(db, firstVendor, missingCounterDay,
                snapshot(visitsOffer("one", "PNK-1", 1, null, null)),
                missingCounterDay.atTime(22, 15).toInstant(ZoneOffset.UTC));

        var dates = List.of(FIRST_DAY, SECOND_DAY, missingProductDay, importedDay, missingCounterDay, FIRST_DAY);
        var result = OffersTable.getProductVisitsForDates(db, firstVendor, " PNK-1 ", dates);
        assertEquals(5, result.size());
        assertEquals(new OffersTable.ProductVisits("available", 0L, 5_000_000_000L, FIRST_DAY,
                firstFetch.atOffset(ZoneOffset.UTC), false), result.get(FIRST_DAY));
        for (var day : List.of(SECOND_DAY, importedDay)) {
            assertEquals("missing", result.get(day).status());
            assertNull(result.get(day).snapshotDate());
            assertNull(result.get(day).fetchedAt());
        }
        for (var day : List.of(missingProductDay, missingCounterDay)) {
            assertEquals("missing", result.get(day).status());
            assertEquals(day, result.get(day).snapshotDate());
            assertNotNull(result.get(day).fetchedAt());
            assertNull(result.get(day).visits14());
            assertNull(result.get(day).visits30());
            assertFalse(result.get(day).stale());
        }
        assertEquals(Map.of(), OffersTable.getProductVisitsForDates(db, firstVendor, "PNK-1", List.of()));
        var blank = OffersTable.getProductVisitsForDates(db, firstVendor, " ", List.of(FIRST_DAY));
        assertEquals("missing", blank.get(FIRST_DAY).status());
    }

    @Test
    void historicalVisitsKeepUniqueOfferSelectionAndUseTheLastSuccessfulSameDaySnapshot() throws SQLException {
        var firstFetch = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        var finalFetch = FIRST_DAY.atTime(22, 55).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("selected", "PNK-1", 5, 100L, 50L)), firstFetch);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                visitsOffer("selected", "PNK-1", 5, null, 0L),
                visitsOffer("ignored", "PNK-1", 0, 999L, 999L)), finalFetch);
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot(
                visitsOffer("one", "PNK-1", 1, 10L, 5L),
                visitsOffer("two", "PNK-1", 2, 20L, 6L)),
                SECOND_DAY.atTime(22, 55).toInstant(ZoneOffset.UTC));

        var result = OffersTable.getProductVisitsForDates(db, firstVendor, "PNK-1", List.of(FIRST_DAY, SECOND_DAY));
        assertEquals(new OffersTable.ProductVisits("available", 0L, null, FIRST_DAY,
                finalFetch.atOffset(ZoneOffset.UTC), false), result.get(FIRST_DAY));
        assertEquals("ambiguous", result.get(SECOND_DAY).status());
        assertNull(result.get(SECOND_DAY).visits14());
        assertNull(result.get(SECOND_DAY).visits30());
        assertEquals(SECOND_DAY, result.get(SECOND_DAY).snapshotDate());
    }

    @Test
    void previewUsesLatestCompletedLiveVendorSnapshotEvenWhenProductIsMissing() throws SQLException {
        var today = FIRST_DAY.plusDays(3);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", "PNK-1", 1, 100L, 50L)),
                FIRST_DAY.atTime(22, 15).toInstant(ZoneOffset.UTC));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot(),
                SECOND_DAY.atTime(22, 15).toInstant(ZoneOffset.UTC));
        OffersTable.storeSnapshot(db, firstVendor, today.minusDays(1),
                snapshot(visitsOffer("one", "PNK-1", 1, 200L, 100L)));
        OffersTable.storeSnapshot(db, firstVendor, today,
                snapshot(visitsOffer("one", "PNK-1", 1, 300L, 150L)),
                today.atTime(10, 15).toInstant(ZoneOffset.UTC));
        OffersTable.storeSnapshot(db, firstVendor, today.plusDays(1), snapshot(),
                today.plusDays(1).atTime(10, 15).toInstant(ZoneOffset.UTC));
        OffersTable.storeSnapshot(db, secondVendor, today.minusDays(1), snapshot(),
                today.minusDays(1).atTime(22, 15).toInstant(ZoneOffset.UTC));

        assertEquals(SECOND_DAY,
                OffersTable.getLatestCompletedProductVisitsSnapshotDate(db, firstVendor, today).orElseThrow());
        assertEquals(today.minusDays(1),
                OffersTable.getLatestCompletedProductVisitsSnapshotDate(db, secondVendor, today).orElseThrow());
        assertTrue(OffersTable.getLatestCompletedProductVisitsSnapshotDate(db, firstVendor, FIRST_DAY).isEmpty());
        assertTrue(OffersTable.getLatestCompletedProductVisitsSnapshotDate(db, UUID.randomUUID(), today).isEmpty());
    }

    @Test
    void firstProductActivityIncludesZeroStockOffersAndQualifyingOrdersWithoutDependingOnAds() throws SQLException {
        execute("CREATE TABLE product (product_code TEXT, emag_pnk TEXT)");
        execute("CREATE TABLE emag_order (surrogate_id INTEGER PRIMARY KEY, status INTEGER, date TIMESTAMP, "
                + "vendor_id UUID DEFAULT '" + firstVendor + "')");
        execute("CREATE TABLE product_in_order (emag_order_surrogate_id INTEGER, part_number_key TEXT)");
        execute("INSERT INTO product VALUES ('SKU-1', 'GMV-PNK'), ('OTHER', 'OTHER-PNK')");
        execute("""
                INSERT INTO emag_order VALUES
                (1, 1, '2026-01-01'), (2, 4, '2026-02-01'), (3, 5, '2026-08-01'), (4, 4, '2026-07-01')
                """);
        execute("""
                INSERT INTO product_in_order VALUES
                (1, 'PNK-1'), (2, 'OTHER-PNK'), (3, 'PNK-1'), (4, 'GMV-PNK')
                """);
        execute("INSERT INTO emag_order VALUES (5, 4, '2025-12-01', '" + secondVendor
                + "'), (6, 5, '2025-12-02', '" + secondVendor + "'), (7, 4, '2025-11-01', NULL)");
        execute("INSERT INTO product_in_order VALUES (5, 'GMV-PNK'), (6, 'PNK-1'), (7, 'GMV-PNK')");
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", " PNK-1 ", 0, null, null)));
        OffersTable.storeSnapshot(db, secondVendor, LocalDate.of(2026, 1, 1),
                snapshot(visitsOffer("one", "PNK-1", 1, null, null)));

        assertEquals(LocalDate.of(2026, 7, 1), EmagMirrorDB.getFirstProductPerformanceActivity(
                db, firstVendor, "SKU-1", " PNK-1 ").orElseThrow());
        assertEquals(LocalDate.of(2026, 7, 1), EmagMirrorDB.getFirstProductPerformanceActivity(
                db, firstVendor, "SKU-1", null).orElseThrow());
        assertEquals(LocalDate.of(2026, 8, 1), EmagMirrorDB.getFirstProductPerformanceActivity(
                db, firstVendor, "UNKNOWN", "PNK-1").orElseThrow());
        assertEquals(LocalDate.of(2025, 12, 1), EmagMirrorDB.getFirstProductPerformanceActivity(
                db, secondVendor, "SKU-1", null).orElseThrow());
        assertTrue(EmagMirrorDB.getFirstProductPerformanceActivity(
                db, UUID.randomUUID(), "SKU-1", "PNK-1").isEmpty());
        execute("DELETE FROM product_in_order WHERE part_number_key = 'PNK-1'");
        assertEquals(FIRST_DAY, EmagMirrorDB.getFirstProductPerformanceActivity(
                db, firstVendor, "UNKNOWN", "PNK-1").orElseThrow());
        assertTrue(EmagMirrorDB.getFirstProductPerformanceActivity(db, firstVendor, "UNKNOWN", "UNKNOWN").isEmpty());
    }

    @Test
    void prefersUniqueNonzeroStockOfferAndAcceptsASingleOutOfStockOffer() throws SQLException {
        var fetchedAt = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                visitsOffer("active", "PNK-1", 5, 20L, 10L),
                visitsOffer("old-zero", "PNK-1", 0, 900L, 800L),
                visitsOffer("unknown-stock", "PNK-1", null, 700L, 600L),
                visitsOffer("only-zero", "PNK-2", 0, 40L, 30L),
                visitsOffer("only-unknown", "PNK-3", null, null, 0L)), fetchedAt);

        assertEquals(10L, OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", FIRST_DAY).visits14());
        assertEquals(30L, OffersTable.getLatestProductVisits(db, firstVendor, "PNK-2", FIRST_DAY).visits14());
        var oneUnknown = OffersTable.getLatestProductVisits(db, firstVendor, "PNK-3", FIRST_DAY);
        assertEquals("available", oneUnknown.status());
        assertEquals(0L, oneUnknown.visits14());
        assertNull(oneUnknown.visits30());
    }

    @Test
    void doesNotSumOrGuessBetweenDuplicateCanonicalOffers() throws SQLException {
        var fetchedAt = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                visitsOffer("first", "PNK-1", 1, 20L, 10L),
                visitsOffer("second", "PNK-1", 2, 20L, 10L),
                visitsOffer("zero-one", "PNK-2", 0, 40L, 30L),
                visitsOffer("zero-two", "PNK-2", 0, null, null)), fetchedAt);

        for (var pnk : List.of("PNK-1", "PNK-2")) {
            var visits = OffersTable.getLatestProductVisits(db, firstVendor, pnk, FIRST_DAY);
            assertEquals("ambiguous", visits.status());
            assertNull(visits.visits14());
            assertNull(visits.visits30());
            assertEquals(FIRST_DAY, visits.snapshotDate());
        }
    }

    @Test
    void latestMissingCountersOrProductDoNotFallBackToEarlierSnapshots() throws SQLException {
        var firstFetch = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        var secondFetch = SECOND_DAY.atTime(10, 6).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", "PNK-1", 5, 200L, 100L)), firstFetch);
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY,
                snapshot(visitsOffer("one", "PNK-1", 5, null, null)), secondFetch);

        var expected = new OffersTable.ProductVisits("missing", null, null, SECOND_DAY,
                secondFetch.atOffset(ZoneOffset.UTC), false);
        assertEquals(expected, OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", SECOND_DAY));
        assertEquals(expected, OffersTable.getLatestProductVisits(db, firstVendor, "absent", SECOND_DAY));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot(), secondFetch);
        assertEquals(expected, OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", SECOND_DAY));
        assertEquals(new OffersTable.ProductVisits("missing", null, null, null, null, false),
                OffersTable.getLatestProductVisits(db, secondVendor, "PNK-1", SECOND_DAY));
        assertEquals(new OffersTable.ProductVisits("missing", null, null, null, null, false),
                OffersTable.getLatestProductVisits(db, firstVendor, " ", SECOND_DAY));
    }

    @Test
    void failedLiveReplacementPreservesPreviousCountsAndCollectionTime() throws SQLException {
        var firstFetch = FIRST_DAY.atTime(10, 5).toInstant(ZoneOffset.UTC);
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(visitsOffer("one", "PNK-1", 5, 200L, 100L)), firstFetch);
        db.commit();
        var broken = visitsOffer("broken", "PNK-1", 1, 999L, 888L);
        // Force a real SQL failure after the parent snapshot has been updated.
        execute("ALTER TABLE offers_offer ADD CONSTRAINT reject_broken_offer CHECK (offer_id <> 'broken')");
        db.commit();
        assertThrows(SQLException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(broken), firstFetch.plusSeconds(3600)));
        db.rollback();

        assertEquals(new OffersTable.ProductVisits("available", 100L, 200L, FIRST_DAY,
                        firstFetch.atOffset(ZoneOffset.UTC), false),
                OffersTable.getLatestProductVisits(db, firstVendor, "PNK-1", FIRST_DAY));
    }

    @Test
    void sameDayReplacementRemovesAbsentOffersAndOptionalChildren() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("kept"), fullOffer("removed")));
        var revised = offer("""
                {"id":"kept", "sellerName":"Updated seller", "extSalePrice":19.87654321,
                 "measurements":[], "offerStock":{"propertyTypes":[]}}
                """);

        assertEquals(1, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(revised)));

        assertEquals(1, count("SELECT count(*) FROM offers_offer"));
        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", revised);
        assertStoredRecord("offers_stock", revised.offerStock());
        for (var table : CHILD_TABLES.subList(1, CHILD_TABLES.size())) {
            assertEquals(0, count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void readsGrossDailyPricesAndKeepsTheExistingNonzeroStockSelection() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                offer("""
                        {"id":"selected", "docProductPartNumberKey":" PNK-1 ", "extStock":7,
                         "stock2p":99, "extSalePrice":165.28, "offerStock":{"vatId":9}}
                        """),
                offer("""
                        {"id":"zero", "docProductPartNumberKey":"PNK-1", "extStock":0,
                         "extSalePrice":999, "offerStock":{"vatId":9}}
                        """),
                offer("""
                        {"id":"other", "docProductPartNumberKey":"PNK-2", "extStock":75,
                         "extSalePrice":5}
                        """)));
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY, snapshot(offer("""
                {"id":"selected", "docProductPartNumberKey":"PNK-1", "extStock":999,
                 "extSalePrice":100, "offerStock":{"vatId":10}}
                """)));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot(
                offer("""
                        {"id":"first", "docProductPartNumberKey":"PNK-1", "extStock":2,
                         "extSalePrice":20, "offerStock":{"vatId":5}}
                        """),
                offer("""
                        {"id":"second", "docProductPartNumberKey":"PNK-1", "extStock":3,
                         "extSalePrice":21, "offerStock":{"vatId":10}}
                        """)));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY.plusDays(1), snapshot());
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY.plusDays(2), snapshot(offer("""
                {"id":"only-zero", "docProductPartNumberKey":"PNK-1", "extStock":0,
                 "extSalePrice":100, "offerStock":{"vatId":9}}
                """)));

        var days = OffersTable.getDailyProductOffers(db, firstVendor, " PNK-1 ",
                FIRST_DAY, SECOND_DAY.plusDays(2));
        assertEquals(2, days.size(), "Empty snapshots and zero-stock-only days remain excluded");
        assertEquals(new OffersTable.DailyProductOffer(FIRST_DAY, 1, 7,
                new BigDecimal("199.99")), days.getFirst());
        assertEquals(2, days.get(1).matchingOffers());
        assertEquals(3, days.get(1).stock());
        assertNull(days.get(1).salesPrice(), "Different eligible offers must not mix prices and VAT rates");
        assertEquals(List.of(), OffersTable.getDailyProductOffers(db, firstVendor, " ", FIRST_DAY,
                SECOND_DAY.plusDays(1)));
        assertEquals(new OffersTable.DailyProductOffer(FIRST_DAY, 1, 999, new BigDecimal("111.00")),
                OffersTable.getDailyProductOffers(db, secondVendor, "PNK-1", FIRST_DAY, FIRST_DAY).getFirst());
    }

    @Test
    void resolvesVatOnlyFromTheSameVendorDateAndOfferIdentity() throws SQLException {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(
                pricedOffer("shared", "PNK-1", "100", 9),
                pricedOffer("different-offer", "PNK-2", "100", 5)));
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY,
                snapshot(pricedOffer("shared", "PNK-1", "100", 10)));
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY,
                snapshot(pricedOffer("shared", "PNK-1", "100", 5)));

        assertEquals(List.of(
                        new OffersTable.DailyProductOffer(FIRST_DAY, 1, 1, new BigDecimal("121.00")),
                        new OffersTable.DailyProductOffer(SECOND_DAY, 1, 1, new BigDecimal("111.00"))),
                OffersTable.getDailyProductOffers(db, firstVendor, "PNK-1", FIRST_DAY, SECOND_DAY));
        assertEquals(new BigDecimal("100.00"),
                OffersTable.getDailyProductOffers(db, secondVendor, "PNK-1", FIRST_DAY, FIRST_DAY)
                        .getFirst().salesPrice());
        assertEquals(new BigDecimal("100.00"),
                OffersTable.getDailyProductOffers(db, firstVendor, "PNK-2", FIRST_DAY, FIRST_DAY)
                        .getFirst().salesPrice());
    }

    @Test
    void keepsUnknownVatAndMissingPricesUnavailableWithoutDiscardingStockOrNetPrecision() throws SQLException {
        var unknownVat = pricedOffer("same", "PNK-1", "100", 1);
        var missingVat = offer("""
                {"id":"same", "docProductPartNumberKey":"PNK-1", "extStock":1,
                 "extSalePrice":100, "offerStock":{}}
                """);
        var missingChild = offer("""
                {"id":"same", "docProductPartNumberKey":"PNK-1", "extStock":1, "extSalePrice":100}
                """);
        var missingPrice = pricedOffer("same", "PNK-1", null, 9);
        var zeroPrice = pricedOffer("same", "PNK-1", "0", 9);
        var precisePrice = pricedOffer("same", "PNK-1", "0.014", 9);
        var offers = List.of(unknownVat, missingVat, missingChild, missingPrice, zeroPrice, precisePrice);
        for (int index = 0; index < offers.size(); index++) {
            OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY.plusDays(index), snapshot(offers.get(index)));
        }

        var days = OffersTable.getDailyProductOffers(db, firstVendor, "PNK-1", FIRST_DAY,
                FIRST_DAY.plusDays(offers.size() - 1));
        assertEquals(offers.size(), days.size());
        for (int index = 0; index < days.size(); index++) {
            assertEquals(1, days.get(index).matchingOffers());
            assertEquals(1, days.get(index).stock());
            if (index < 4) assertNull(days.get(index).salesPrice());
        }
        assertEquals(new BigDecimal("0.00"), days.get(4).salesPrice());
        assertEquals(new BigDecimal("0.02"), days.get(5).salesPrice(), "Round only after applying VAT");
    }

    @Test
    void matchingOfferIdsRemainIndependentAcrossVendorsAndDatesIncludingEmptySnapshots() throws Exception {
        var full = snapshot(fullOffer("shared-id"));
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, full);
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, full);
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY, full);

        assertEquals(0, OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot()));

        assertEquals(3, count("SELECT count(*) FROM offers_snapshot"));
        assertEquals(0, snapshotCount(firstVendor, FIRST_DAY));
        assertEquals(1, snapshotCount(firstVendor, SECOND_DAY));
        assertEquals(1, snapshotCount(secondVendor, FIRST_DAY));
        assertEquals(2, count("SELECT count(*) FROM offers_offer"));
        for (var table : CHILD_TABLES) {
            assertEquals(table.equals("offers_measurement") ? 4 : 2,
                    count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void preservesNullAndEmptyCollectionsAndAbsentVersusPresentEmptyRecords() throws SQLException {
        var missing = offer("{\"id\":\"missing\"}");
        var empty = offer("""
                {"id":"empty", "eans":[], "inactivationCriticalities":[], "offerProperties":[],
                 "measurements":[], "offerStock":{"propertyTypes":[]}, "offerPrice":{},
                 "links":{}, "supplyRecommendationData":{}}
                """);
        var values = offer("""
                {"id":"values", "eans":["001",null,"","001"], "offerStock":{}}
                """);

        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(missing, empty, values));

        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT * FROM offers_offer ORDER BY offer_id")) {
            assertTrue(rows.next());
            assertEquals("empty", rows.getString("offer_id"));
            assertTrue(rows.getBoolean("measurements_present"));
            for (var column : List.of("eans", "inactivation_criticalities", "offer_properties")) {
                assertEquals(List.of(), arrayValues(rows, column));
            }
            assertTrue(rows.next());
            assertEquals("missing", rows.getString("offer_id"));
            assertFalse(rows.getBoolean("measurements_present"));
            for (var column : List.of("eans", "inactivation_criticalities", "offer_properties")) {
                assertNull(rows.getArray(column));
            }
            assertTrue(rows.next());
            assertEquals(Arrays.asList("001", null, "", "001"), arrayValues(rows, "eans"));
            assertFalse(rows.next());
        }
        assertEquals(0, count("SELECT count(*) FROM offers_measurement"));
        assertEquals(2, count("SELECT count(*) FROM offers_stock"));
        assertEquals(1, count("SELECT count(*) FROM offers_stock WHERE property_types IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM offers_stock WHERE cardinality(property_types) = 0"));
        for (var table : List.of("offers_price", "offers_links", "offers_supply_recommendation")) {
            assertEquals(1, count("SELECT count(*) FROM " + table), table);
        }
        assertEquals(1, count("SELECT count(*) FROM offers_price WHERE status_details IS NULL"));
    }

    @Test
    void rejectsMalformedAndPartialSnapshotsBeforeChangingTheExistingSnapshot() throws Exception {
        var original = fullOffer("original");
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(original));
        for (var total : Arrays.asList((Integer) null, -1, 0, 2)) {
            assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                    new OffersData(new Offers(List.of(original), total))));
        }
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(original, original)));
        for (var json : List.of("{}", "{\"id\":\"\"}", "{\"id\":\"  \"}")) {
            assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                    snapshot(offer(json))));
        }
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(new Offers(Arrays.asList((Offer) null), 1))));
        var nullMeasurement = sampleRecord(Offer.class, 1,
                Map.of("id", "original", "measurements", Arrays.asList((Measurements) null)));
        assertThrows(IllegalArgumentException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                snapshot(nullMeasurement)));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, null));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(null)));
        assertThrows(NullPointerException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY,
                new OffersData(new Offers(null, 0))));
        assertEquals(1, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertStoredRecord("offers_offer", original);
        assertStoredRecord("offers_links", original.links());
    }

    @Test
    void databaseFailureRollsBackParentReplacementAndEveryChildTable() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("original-1"), fullOffer("original-2")));
        execute("ALTER TABLE offers_measurement ADD CHECK (weight >= 0)");
        db.commit();
        var failingOffer = sampleRecord(Offer.class, 2, Map.of(
                "id", "replacement", "measurements", List.of(new Measurements(BigDecimal.valueOf(-1), null, null, null))));

        assertThrows(SQLException.class,
                () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(failingOffer)));
        db.rollback();

        assertEquals(2, count("SELECT total_number_of_items FROM offers_snapshot"));
        assertEquals(2, count("SELECT count(*) FROM offers_offer WHERE offer_id LIKE 'original-%'"));
        assertEquals(0, count("SELECT count(*) FROM offers_offer WHERE offer_id = 'replacement'"));
        for (var table : CHILD_TABLES) {
            assertEquals(table.equals("offers_measurement") ? 4 : 2,
                    count("SELECT count(*) FROM " + table), table);
        }
    }

    @Test
    void foreignKeysEnforceVendorDateAndOfferOwnershipAndCascadeSnapshotDeletion() throws Exception {
        OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot(fullOffer("shared-id")));
        OffersTable.storeSnapshot(db, secondVendor, FIRST_DAY, snapshot());
        OffersTable.storeSnapshot(db, firstVendor, SECOND_DAY, snapshot());
        assertRejected("23503", () -> OffersTable.storeSnapshot(db, UUID.randomUUID(), FIRST_DAY, snapshot()));
        assertRejected("23503", () -> execute("DELETE FROM vendor WHERE id = '" + firstVendor + "'"));
        for (var table : CHILD_TABLES) {
            var positionColumn = table.equals("offers_measurement") ? ", position" : "";
            var positionValue = table.equals("offers_measurement") ? ", 0" : "";
            String insert = "INSERT INTO " + table + " (vendor_id, fetch_date, offer_id" + positionColumn + ") VALUES ";
            assertRejected("23503", () -> execute(insert + "('" + secondVendor + "', DATE '" + FIRST_DAY
                    + "', 'shared-id'" + positionValue + ")"));
            assertRejected("23503", () -> execute(insert + "('" + firstVendor + "', DATE '" + SECOND_DAY
                    + "', 'shared-id'" + positionValue + ")"));
        }
        assertRejected("23505", () -> execute("""
                INSERT INTO offers_offer (vendor_id, fetch_date, offer_id, measurements_present)
                SELECT vendor_id, fetch_date, offer_id, measurements_present FROM offers_offer
                """));
        assertRejected("23502", () -> execute("UPDATE offers_snapshot SET fetch_date = NULL"));
        assertRejected("23502", () -> execute("UPDATE offers_snapshot SET vendor_id = NULL"));

        execute("DELETE FROM offers_snapshot");

        assertEquals(0, count("SELECT count(*) FROM offers_offer"));
        for (var table : CHILD_TABLES) assertEquals(0, count("SELECT count(*) FROM " + table), table);
        assertEquals(2, count("SELECT count(*) FROM vendor"));
    }

    @Test
    void refusesAutocommitSoCallersCannotPartiallyReplaceASnapshot() throws SQLException {
        db.setAutoCommit(true);
        try {
            assertThrows(SQLException.class, () -> OffersTable.storeSnapshot(db, firstVendor, FIRST_DAY, snapshot()));
            assertEquals(0, count("SELECT count(*) FROM offers_snapshot"));
        } finally {
            db.setAutoCommit(false);
        }
    }

    private static OffersData snapshot(Offer... offers) {
        return new OffersData(new Offers(List.of(offers), offers.length));
    }

    private static Offer offer(String json) {
        return JSON.readValue(json, Offer.class);
    }

    private static Offer pricedOffer(String id, String pnk, String netPrice, int vatId) {
        return offer("""
                {"id":"%s", "docProductPartNumberKey":"%s", "extStock":1,
                 "extSalePrice":%s, "offerStock":{"vatId":%d}}
                """.formatted(id, pnk, netPrice, vatId));
    }

    private static Offer visitsOffer(String id, String pnk, Integer stock, Long visits30, Long visits14) {
        return offer("""
                {"id":"%s", "docProductPartNumberKey":"%s", "extStock":%s,
                 "productPerformanceVisits":%s, "productPerformanceVisitsLast14":%s}
                """.formatted(id, pnk, stock, visits30, visits14));
    }

    private static Offer hotnessOffer(String id, String pnk, Integer stock, String type, String name) {
        var details = new HashMap<String, Object>();
        details.put("hotnessType", type);
        details.put("hotnessName", name);
        var fields = new HashMap<String, Object>();
        fields.put("id", id);
        fields.put("docProductPartNumberKey", pnk);
        fields.put("extStock", stock);
        fields.put("offerStock", details);
        return offer(JSON.writeValueAsString(fields));
    }

    private static Offer fullOffer(String id) throws ReflectiveOperationException {
        return sampleRecord(Offer.class, 1, Map.of("id", id));
    }

    /** Distinct values for every field catch missing columns and mismatched statement positions. */
    private static <T extends Record> T sampleRecord(Class<T> type, int seed, Map<String, Object> overrides)
            throws ReflectiveOperationException {
        var fields = type.getRecordComponents();
        var values = new Object[fields.length];
        var types = new Class<?>[fields.length];
        for (int index = 0; index < fields.length; index++) {
            var field = fields[index];
            var fieldType = field.getType();
            types[index] = fieldType;
            int value = seed * 100 + index;
            if (overrides.containsKey(field.getName())) values[index] = overrides.get(field.getName());
            else if (fieldType == String.class) values[index] = field.getName() + "-" + value;
            else if (fieldType == Integer.class) values[index] = value;
            else if (fieldType == Long.class) values[index] = 5_000_000_000L + value;
            else if (fieldType == Boolean.class) values[index] = index % 2 == 0;
            else if (fieldType == BigDecimal.class) values[index] = new BigDecimal(value + ".123456789");
            else if (fieldType == LocalDate.class) values[index] = FIRST_DAY.plusDays(index);
            else if (fieldType == LocalDateTime.class) {
                values[index] = FIRST_DAY.atTime(13, 27, 41, 123456000).plusDays(index);
            } else if (fieldType == List.class) {
                var elementType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                if (elementType == String.class) {
                    values[index] = List.of(field.getName(), "quote \" and backslash \\", "", "last");
                } else if (elementType == Measurements.class) {
                    values[index] = List.of(sampleRecord(Measurements.class, 2, Map.of()),
                            sampleRecord(Measurements.class, 3, Map.of()));
                } else throw new AssertionError("Unsupported list type: " + field);
            } else if (fieldType.isRecord()) {
                values[index] = sampleRecord(fieldType.asSubclass(Record.class), seed + 1, Map.of());
            } else throw new AssertionError("Unsupported record component: " + field);
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }

    private void assertStoredRecord(String table, Record expected) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table)) {
            assertTrue(rows.next(), table);
            assertEquals(firstVendor, rows.getObject("vendor_id", UUID.class));
            assertEquals(FIRST_DAY, rows.getObject("fetch_date", LocalDate.class));
            assertRecordColumns(rows, expected);
            assertFalse(rows.next(), table);
        }
    }

    private static void assertRecordColumns(ResultSet rows, Record expected) throws Exception {
        for (var field : expected.getClass().getRecordComponents()) {
            if (field.getType().isRecord() || field.getName().equals("measurements")) continue;
            String column = field.getName().equals("id") ? "offer_id"
                    : field.getName().replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
            Object value = field.getAccessor().invoke(expected);
            if (value instanceof BigDecimal decimal) {
                var actual = rows.getBigDecimal(column);
                assertNotNull(actual, column);
                assertEquals(0, decimal.compareTo(actual), column);
            } else if (value instanceof List<?>) assertEquals(value, arrayValues(rows, column), column);
            else if (value instanceof LocalDate) assertEquals(value, rows.getObject(column, LocalDate.class), column);
            else if (value instanceof LocalDateTime) assertEquals(value, rows.getObject(column, LocalDateTime.class), column);
            else assertEquals(value, rows.getObject(column), column);
        }
    }

    private static List<String> arrayValues(ResultSet rows, String column) throws SQLException {
        var array = rows.getArray(column);
        if (array == null) return null;
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private int snapshotCount(UUID vendor, LocalDate date) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT total_number_of_items FROM offers_snapshot WHERE vendor_id = ? AND fetch_date = ?
                """)) {
            statement.setObject(1, vendor);
            statement.setObject(2, date);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private void assertRejected(String sqlState, SqlAction action) throws SQLException {
        var savepoint = db.setSavepoint();
        var exception = assertThrows(SQLException.class, action::run);
        assertEquals(sqlState, exception.getSQLState());
        db.rollback(savepoint);
        db.releaseSavepoint(savepoint);
    }

    private long count(String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private void applyMigration() throws Exception {
        for (int version : List.of(41, 48)) {
            var type = Class.forName("ro.sellfluence.db.versions.EmagMirrorDBVersion" + version);
            var method = type.getDeclaredMethod("version" + version, Connection.class);
            method.setAccessible(true);
            try {
                method.invoke(null, db);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof Exception failure) throw failure;
                throw exception;
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
