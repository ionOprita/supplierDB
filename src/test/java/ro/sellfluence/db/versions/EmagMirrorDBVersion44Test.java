package ro.sellfluence.db.versions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an explicitly supplied disposable PostgreSQL database and an isolated schema. */
class EmagMirrorDBVersion44Test {
    private static final List<String> REVIEW_CHILD_TABLES = List.of("review_comment", "review_user",
            "review_product", "review_price", "review_image", "review_image_size");
    private Connection db;
    private String schema;

    @BeforeEach
    void createIsolatedVersion43Schema() throws SQLException {
        var url = System.getenv("ADS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "ADS_TEST_DB_URL is required for PostgreSQL integration tests");
        var properties = new Properties();
        var user = System.getenv("ADS_TEST_DB_USER");
        var password = System.getenv("ADS_TEST_DB_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        db = DriverManager.getConnection(url, properties);
        db.setAutoCommit(false);
        schema = "reviews_v44_test_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        db.setSchema(schema);
        EmagMirrorDBVersion42.version42(db);
        execute("""
                CREATE TABLE tasks (
                    name VARCHAR(255) PRIMARY KEY, started TIMESTAMP, terminated TIMESTAMP,
                    last_successful_run TIMESTAMP, error TEXT
                )
                """);
        EmagMirrorDBVersion43.version43(db);
        db.commit();
    }

    @AfterEach
    void removeIsolatedSchema() throws SQLException {
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
    void removesUnmatchedGraphsAndRepairsFirstReferencesBeforeDroppingTheColumn() throws SQLException {
        insertFetch("PNK-A", 8, 11L);
        insertReviewGraph("PNK-A", 11, "PNK-B", true, "PNK-A");
        insertReviewGraph("PNK-A", 12, "PNK-A", true, "OTHER-COMMENT-PNK");
        insertReviewGraph("PNK-A", 13, null, true, "PNK-A");
        insertReviewGraph("PNK-A", 14, "", true, "PNK-A");
        insertReviewGraph("PNK-A", 15, null, false, "PNK-A");
        insertReviewGraph("PNK-A", 16, " \t\n", true, "PNK-A");
        insertReviewGraph("PNK-A", 17, "pnk-a", true, "PNK-A");
        insertReviewGraph("PNK-A", 18, "PNK-A ", true, "PNK-A");
        insertFetch("PNK-B", 1, 11L);
        insertReviewGraph("PNK-B", 11, "PNK-B", true, "PNK-B");
        insertFetch("PNK-C", 1, 21L);
        insertReviewGraph("PNK-C", 21, "PNK-B", true, "PNK-C");
        db.commit();

        var warnings = new ArrayList<LogRecord>();
        var handler = captureWarnings(warnings);
        var logger = Logger.getLogger(EmagMirrorDBVersion44.class.getName());
        logger.addHandler(handler);
        try {
            EmagMirrorDBVersion44.version44(db);
            db.commit(); // Checks the completed graph against the deferred first-review foreign key.
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(2, count("SELECT count(*) FROM review"));
        assertPreservedGraph("PNK-A", 12);
        assertPreservedGraph("PNK-B", 11);
        for (long reviewId : List.of(11L, 13L, 14L, 15L, 16L, 17L, 18L)) {
            assertRemovedGraph("PNK-A", reviewId);
        }
        assertRemovedGraph("PNK-C", 21);
        assertNull(value("SELECT first_review_id FROM review_fetch WHERE pnk = ?", "PNK-A"));
        assertEquals(11L, value("SELECT first_review_id FROM review_fetch WHERE pnk = ?", "PNK-B"));
        assertNull(value("SELECT first_review_id FROM review_fetch WHERE pnk = ?", "PNK-C"));
        assertEquals(3, count("SELECT count(*) FROM review_fetch"));
        assertEquals(8, count("SELECT total_count FROM review_fetch WHERE pnk = ?", "PNK-A"));
        assertEquals("Original summary", value("SELECT summary FROM review_fetch WHERE pnk = ?", "PNK-A"));
        assertEquals(3, count("SELECT count(*) FROM review_fetch_metadata"));
        assertEquals(0, productPnkColumnCount());
        assertEquals(2, count("SELECT count(*) FROM review_product WHERE owner_type = 'review' AND pnk IS NOT NULL"));

        assertEquals(4, warnings.size(), "Only absent, null, and blank product PNKs need a warning");
        for (long reviewId : List.of(13L, 14L, 15L, 16L)) {
            assertTrue(warnings.stream().anyMatch(record -> record.getLevel() == Level.WARNING
                    && record.getMessage().contains("review " + reviewId + " ")
                    && record.getMessage().contains("queried PNK 'PNK-A'")));
        }
    }

    @Test
    void rollbackRestoresRemovedGraphsReferencesAndTheDroppedColumn() throws SQLException {
        insertFetch("PNK-A", 1, 11L);
        insertReviewGraph("PNK-A", 11, "PNK-B", true, "PNK-A");
        insertFetch("PNK-B", 1, 11L);
        insertReviewGraph("PNK-B", 11, "PNK-B", true, "PNK-B");
        db.commit();

        EmagMirrorDBVersion44.version44(db);
        assertRemovedGraph("PNK-A", 11);
        assertEquals(0, productPnkColumnCount());
        assertThrows(SQLException.class, () -> execute("SELECT 1 / 0"));
        db.rollback();

        assertPreservedGraph("PNK-A", 11);
        assertPreservedGraph("PNK-B", 11);
        assertEquals(11L, value("SELECT first_review_id FROM review_fetch WHERE pnk = ?", "PNK-A"));
        assertEquals(1, productPnkColumnCount());
        assertEquals("PNK-B", value("""
                SELECT part_number_key FROM review_product
                WHERE pnk = ? AND review_id = ? AND owner_type = 'review'
                """, "PNK-A", 11L));
        db.commit();
    }

    @Test
    void migratesAnEmptyReviewSchema() throws SQLException {
        EmagMirrorDBVersion44.version44(db);
        db.commit();

        assertEquals(0, count("SELECT count(*) FROM review"));
        assertEquals(0, productPnkColumnCount());
    }

    private void insertFetch(String pnk, int totalCount, Long firstReviewId) throws SQLException {
        execute("""
                INSERT INTO review_fetch (
                    pnk, last_successful_fetch_at, response_code, total_count, first_review_id, summary,
                    metadata_present, add_url_present, view_url_present, messages_present
                ) VALUES (?, TIMESTAMPTZ '2026-09-26 03:14:15Z', 200, ?, ?, 'Original summary',
                    true, false, false, false)
                """, pnk, totalCount, firstReviewId);
        execute("INSERT INTO review_fetch_metadata (pnk, position, value) VALUES (?, 0, 'Original metadata')", pnk);
    }

    private void insertReviewGraph(String pnk, long reviewId, String productPnk,
                                   boolean productPresent, String commentProductPnk) throws SQLException {
        execute("""
                INSERT INTO review (
                    pnk, review_id, position, edit_url_present, view_url_present, content_fingerprint,
                    first_fetched_at, last_fetched_at, last_changed_at
                ) VALUES (?, ?, 0, false, false, 'original-review',
                    TIMESTAMPTZ '2026-09-26 03:14:15Z', TIMESTAMPTZ '2026-09-26 03:14:15Z',
                    TIMESTAMPTZ '2026-09-26 03:14:15Z')
                """, pnk, reviewId);
        long commentId = reviewId + 1_000;
        execute("""
                INSERT INTO review_comment (
                    pnk, review_id, comment_id, position, edit_url_present, view_url_present, content_fingerprint,
                    first_fetched_at, last_fetched_at, last_changed_at
                ) VALUES (?, ?, ?, 0, false, false, 'original-comment',
                    TIMESTAMPTZ '2026-09-26 03:14:15Z', TIMESTAMPTZ '2026-09-26 03:14:15Z',
                    TIMESTAMPTZ '2026-09-26 03:14:15Z')
                """, pnk, reviewId, commentId);
        insertOwnerGraph(pnk, reviewId, "review", reviewId, null, productPnk, productPresent);
        insertOwnerGraph(pnk, reviewId, "comment", commentId, commentId, commentProductPnk, true);
    }

    private void insertOwnerGraph(String pnk, long reviewId, String ownerType, long ownerId, Long commentId,
                                  String productPnk, boolean productPresent) throws SQLException {
        execute("""
                INSERT INTO review_user (
                    pnk, review_id, owner_type, owner_id, comment_id, url_present, avatar_present
                ) VALUES (?, ?, ?, ?, ?, false, false)
                """, pnk, reviewId, ownerType, ownerId, commentId);
        if (productPresent) {
            execute("""
                    INSERT INTO review_product (
                        pnk, review_id, owner_type, owner_id, comment_id, part_number_key, url_present, offer_present
                    ) VALUES (?, ?, ?, ?, ?, ?, false, true)
                    """, pnk, reviewId, ownerType, ownerId, commentId, productPnk);
            execute("""
                    INSERT INTO review_price (
                        pnk, review_id, owner_type, owner_id, discount_present, currency_present,
                        currency_name_present, recommended_retail_price_present, lowest_price_30_days_present
                    ) VALUES (?, ?, ?, ?, false, false, false, false, false)
                    """, pnk, reviewId, ownerType, ownerId);
        }
        execute("""
                INSERT INTO review_image (
                    pnk, review_id, owner_type, owner_id, comment_id, image_kind, position, original
                ) VALUES (?, ?, ?, ?, ?, 'product', 0, 'original-image')
                """, pnk, reviewId, ownerType, ownerId, commentId);
        execute("""
                INSERT INTO review_image_size (
                    pnk, review_id, owner_type, owner_id, image_kind, image_position, position, size, url
                ) VALUES (?, ?, ?, ?, 'product', 0, 0, 'small', 'original-image-url')
                """, pnk, reviewId, ownerType, ownerId);
    }

    private void assertPreservedGraph(String pnk, long reviewId) throws SQLException {
        assertEquals(1, count("SELECT count(*) FROM review WHERE pnk = ? AND review_id = ?", pnk, reviewId));
        for (String table : REVIEW_CHILD_TABLES) {
            assertEquals(table.equals("review_comment") ? 1 : 2,
                    count("SELECT count(*) FROM " + table + " WHERE pnk = ? AND review_id = ?", pnk, reviewId), table);
        }
    }

    private void assertRemovedGraph(String pnk, long reviewId) throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM review WHERE pnk = ? AND review_id = ?", pnk, reviewId));
        for (String table : REVIEW_CHILD_TABLES) {
            assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE pnk = ? AND review_id = ?", pnk, reviewId), table);
        }
    }

    private long productPnkColumnCount() throws SQLException {
        return count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'review_product'
                  AND column_name = 'part_number_key'
                """);
    }

    private static Handler captureWarnings(List<LogRecord> warnings) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private long count(String sql, Object... parameters) throws SQLException {
        return ((Number) value(sql, parameters)).longValue();
    }

    private Object value(String sql, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                return rows.getObject(1);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.execute();
        }
    }
}
