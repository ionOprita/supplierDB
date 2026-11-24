package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** The latest imported replacement rate snapshot from the central returns overview. */
public final class EmagMirrorDBVersion54 {
    private EmagMirrorDBVersion54() {
    }

    public static void version54(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE profit_calculator_replacement_rate (
                    pnk TEXT PRIMARY KEY CHECK (btrim(pnk) <> ''),
                    rate NUMERIC NOT NULL CHECK (rate BETWEEN 0 AND 1),
                    captured_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
    }
}
