package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Shared calculator scenarios are separate from imported product master data. */
public final class EmagMirrorDBVersion50 {
    private EmagMirrorDBVersion50() {
    }

    public static void version50(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE profit_calculator_override (
                    product_code TEXT PRIMARY KEY REFERENCES product(product_code) ON DELETE CASCADE,
                    sale_price_ron NUMERIC CHECK (sale_price_ron >= 0),
                    commission_rate NUMERIC CHECK (commission_rate BETWEEN 0 AND 1),
                    purchase_price_usd NUMERIC CHECK (purchase_price_usd >= 0),
                    transport_price_usd NUMERIC CHECK (transport_price_usd >= 0),
                    usd_ron_rate NUMERIC CHECK (usd_ron_rate > 0),
                    fulfillment_source TEXT CHECK (fulfillment_source IN ('eMAG', 'Minulescu')),
                    revision BIGINT NOT NULL CHECK (revision > 0)
                )
                """);
    }
}
