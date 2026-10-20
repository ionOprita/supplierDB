package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Keep complete task failures, including aggregated exceptions, without a fixed character limit. */
class EmagMirrorDBVersion46 {
    static void version46(Connection db) throws SQLException {
        executeStatement(db, "ALTER TABLE tasks ALTER COLUMN error TYPE TEXT");
    }
}
