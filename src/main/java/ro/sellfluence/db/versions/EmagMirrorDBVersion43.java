package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Fields needed for the review sheet export and calendar-day task scheduling. */
class EmagMirrorDBVersion43 {
    static void version43(Connection db) throws SQLException {
        executeStatement(db, "ALTER TABLE review ADD COLUMN moderated_by TEXT");
        executeStatement(db, "ALTER TABLE review_product ADD COLUMN family_characteristic_value TEXT");
        executeStatement(db, "ALTER TABLE tasks ADD COLUMN last_successful_start TIMESTAMP");
        // The current run can be trusted only when it is the run that last succeeded.
        executeStatement(db, """
                UPDATE tasks SET last_successful_start = started
                WHERE name = 'Fetch product reviews from eMAG'
                  AND started IS NOT NULL AND terminated IS NOT NULL
                  AND last_successful_run BETWEEN started AND terminated
                  AND (error IS NULL OR error = '')
                """);
    }
}
