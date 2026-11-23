package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Shared current reference VAT for turnover tax across vendor groups. */
public final class EmagMirrorDBVersion53 {
    private EmagMirrorDBVersion53() {
    }

    public static void version53(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE profit_calculator_shared_settings (
                    id SMALLINT PRIMARY KEY CHECK (id = 1),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    turnover_tax_vat_rate NUMERIC CHECK (turnover_tax_vat_rate BETWEEN 0 AND 1)
                )
                """);
        // Cons. Date Secundare!E3 in the reference calculator workbook.
        executeStatement(db, """
                INSERT INTO profit_calculator_shared_settings (id, revision, turnover_tax_vat_rate)
                VALUES (1, 1, 0.21)
                """);
    }
}
