package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

class EmagMirrorDBVersion4 {

    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version4(Connection db) throws SQLException {
        fixDiagnosticColumn(db);
        simplifyFetchLog(db);
    }

    private static void simplifyFetchLog(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log DROP CONSTRAINT emag_fetch_log_pkey;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log RENAME COLUMN order_start TO date;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log ALTER COLUMN date SET DATA TYPE DATE USING date::DATE;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log DROP COLUMN order_end;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log DROP COLUMN fetch_start;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log RENAME COLUMN fetch_end TO fetch_time;
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_fetch_log ADD PRIMARY KEY (emag_login, date);
                """)) {
            s.execute();
        }
    }

    private static void fixDiagnosticColumn(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                ALTER TABLE emag_returned_products
                    ALTER COLUMN diagnostic SET DATA TYPE VARCHAR(255);
                """)) {
            s.execute();
        }
    }
}