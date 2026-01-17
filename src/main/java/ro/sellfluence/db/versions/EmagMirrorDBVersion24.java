package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion24 {
    /**
     * Cache the tab within the employee sheet.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version24(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE storno (
                    storno_date TIMESTAMP,
                    order_id VARCHAR(255),
                    product_id integer,
                    quantity INTEGER
                );
                """);
        executeStatement(db, """
                ALTER TABLE version_info ADD PRIMARY KEY (version);
                """);
    }
}