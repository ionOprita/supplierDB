package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion15 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version15(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE DOMAIN year_month AS DATE
                CHECK (EXTRACT(DAY FROM VALUE) = 1);
                """);
        executeStatement(db, """
                CREATE TABLE gmv(
                    product_id UUID NOT NULL,
                    month year_month NOT NULL,
                    gmv NUMERIC(10,2) NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES product(id)
                );
                """);
    }
}