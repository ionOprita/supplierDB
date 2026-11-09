package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Permanent task attempts and the calendar date from which each offers source is monitored. */
class EmagMirrorDBVersion47 {
    static void version47(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE task_runs (
                    id BIGSERIAL PRIMARY KEY,
                    task_name TEXT NOT NULL,
                    started_at TIMESTAMPTZ NOT NULL,
                    finished_at TIMESTAMPTZ,
                    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'INTERRUPTED')),
                    error TEXT,
                    CHECK ((status = 'RUNNING') = (finished_at IS NULL))
                )
                """);
        executeStatement(db, "CREATE INDEX task_runs_started_idx ON task_runs (started_at DESC, id DESC)");
        executeStatement(db, "CREATE INDEX task_runs_task_started_idx ON task_runs (task_name, started_at DESC, id DESC)");
        executeStatement(db, """
                CREATE TABLE offer_collection_sources (
                    task_name TEXT PRIMARY KEY,
                    vendor_id UUID NOT NULL REFERENCES vendor (id),
                    enabled_on DATE NOT NULL
                )
                """);
    }
}
