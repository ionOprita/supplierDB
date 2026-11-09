package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Preserve observed rolling visits and distinguish newly collected data from old offer snapshots. */
class EmagMirrorDBVersion48 {
    static void version48(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE offers_offer
                    ADD COLUMN product_performance_visits BIGINT,
                    ADD COLUMN product_performance_visits_last14 BIGINT
                """);
        executeStatement(db, "ALTER TABLE offers_snapshot ADD COLUMN visits_fetched_at TIMESTAMPTZ");
    }
}
