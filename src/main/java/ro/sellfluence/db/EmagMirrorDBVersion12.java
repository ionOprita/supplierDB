package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;
import static ro.sellfluence.support.Time.timeE;

class EmagMirrorDBVersion12 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version12(Connection db) throws SQLException {
        timeE("Add status constraint", () -> addStatusCheckConstraint(db));
        timeE("Add uniqueness constraint", () -> addUniqnessConstraint(db));
    }

    private static void addStatusCheckConstraint(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE emag_order
                ADD CONSTRAINT status_check CHECK (status >= 0 AND status <= 5);
                """);
    }

    private static void addUniqnessConstraint(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE emag_order
                ADD CONSTRAINT unique_order UNIQUE (id, vendor_id, status);
                """);
    }
}