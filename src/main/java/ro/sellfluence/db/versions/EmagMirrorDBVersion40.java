package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion40 {
    static void version40(Connection db) throws SQLException {
        executeStatement(db, "ALTER TABLE vendor ADD COLUMN vendor_group VARCHAR(4)");
        executeStatement(db, "UPDATE vendor SET vendor_group = UPPER(SUBSTRING(account, 1, 1))");
        executeStatement(db, "ALTER TABLE vendor ALTER COLUMN vendor_group SET NOT NULL");
    }
}
