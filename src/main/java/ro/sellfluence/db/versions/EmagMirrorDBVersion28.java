package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion28 {

    private static final Map<String, String> vendorAccountMap = Map.of(
            "sellfluence", "Sellfluence SRL",
            "zoopieconcept", "Zoopie Concept SRL",
            "zoopieinvest", "Zoopie Invest SRL",
            "zoopiesolutions", "Zoopie Solutions SRL",
            "judios", "Judios Concept SRL",
            "koppel", "Koppel SRL",
            "koppelfbe", "Koppel SRL",
            "sellfusion", "Sellfusion SRL"
    );

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version28(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE vendor ADD COLUMN company_name VARCHAR(255);
                """);
        for (Map.Entry<String, String> entry : vendorAccountMap.entrySet()) {
            String account = entry.getKey();
            String name = entry.getValue();
            try (var s = db.prepareStatement("""
                    UPDATE vendor SET company_name = ? WHERE account = ?;
                    """)) {
                s.setString(1, name);
                s.setString(2, account);
                s.execute();
            }
        }
    }
}