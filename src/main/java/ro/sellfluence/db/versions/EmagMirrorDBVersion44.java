package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Keep reviews only on their original product and remove the redundant product PNK. */
class EmagMirrorDBVersion44 {
    private static final Logger logger = Logger.getLogger(EmagMirrorDBVersion44.class.getName());

    static void version44(Connection db) throws SQLException {
        logMissingProductPnks(db);
        // The deferred first-review foreign key has no cascading delete action.
        executeStatement(db, """
                UPDATE review_fetch AS f
                SET first_review_id = NULL
                WHERE f.first_review_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM review_product AS p
                    WHERE p.pnk = f.pnk AND p.review_id = f.first_review_id
                      AND p.owner_type = 'review' AND p.part_number_key = f.pnk
                  )
                """);
        // Deleting the parent also removes its comments and all nested owner rows.
        executeStatement(db, """
                DELETE FROM review AS r
                WHERE NOT EXISTS (
                    SELECT 1 FROM review_product AS p
                    WHERE p.pnk = r.pnk AND p.review_id = r.review_id
                      AND p.owner_type = 'review' AND p.part_number_key = r.pnk
                )
                """);
        executeStatement(db, "ALTER TABLE review_product DROP COLUMN part_number_key");
    }

    private static void logMissingProductPnks(Connection db) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT r.pnk, r.review_id, p.part_number_key
                FROM review AS r
                LEFT JOIN review_product AS p
                  ON p.pnk = r.pnk AND p.review_id = r.review_id AND p.owner_type = 'review'
                WHERE p.part_number_key IS NULL OR p.part_number_key <> r.pnk
                ORDER BY r.pnk, r.review_id
                """); var reviews = statement.executeQuery()) {
            while (reviews.next()) {
                String productPnk = reviews.getString("part_number_key");
                if (productPnk == null || productPnk.isBlank()) {
                    logger.warning("Removing historical review " + reviews.getLong("review_id")
                            + " for queried PNK '" + reviews.getString("pnk")
                            + "' because its product PNK is missing or blank");
                }
            }
        }
    }
}
