package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion9 {

    private static final Map<String, String> vendorAccountMap = Map.of(
            "sellfluence", "Sellfluence FBE",
            "zoopieconcept", "Zoopie Concept FBE",
            "zoopieinvest", "Zoopie Invest",
            "zoopiesolutions", "Zoopie Solutions FBE",
            "judios", "Judios RO FBE",
            "koppel", "Koppel",
            "koppelfbe", "Koppel FBE",
            "sellfusion", "SELLFUSION FBE"
    );

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version9(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE vendor ADD COLUMN account VARCHAR(255);
                """);
        for (Map.Entry<String, String> entry : vendorAccountMap.entrySet()) {
            String account = entry.getKey();
            String name = entry.getValue();
            try (var s = db.prepareStatement("""
                    UPDATE vendor SET account = ? WHERE vendor_name = ?;
                    """)) {
                s.setString(1, account);
                s.setString(2, name);
                s.execute();
            }
        }
    }
}