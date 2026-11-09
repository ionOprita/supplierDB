package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Permanent source evidence and normalized historical observations, separate from native metrics. */
public class EmagMirrorDBVersion49 {
    public static void version49(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE product_performance_history_batch (
                    id UUID PRIMARY KEY,
                    revision BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                    content_hash TEXT NOT NULL UNIQUE CHECK (content_hash ~ '^[0-9a-f]{64}$'),
                    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    source_payload JSONB NOT NULL CHECK (jsonb_typeof(source_payload) IN ('object', 'array')),
                    observation_count INTEGER NOT NULL CHECK (observation_count >= 0)
                )
                """);
        executeStatement(db, """
                CREATE TABLE product_performance_history_observation (
                    batch_id UUID NOT NULL REFERENCES product_performance_history_batch (id),
                    observation_index INTEGER NOT NULL CHECK (observation_index >= 0),
                    vendor_id UUID NOT NULL REFERENCES vendor (id),
                    product_code TEXT NOT NULL REFERENCES product (product_code),
                    pnk TEXT,
                    observation_date DATE NOT NULL,
                    domain TEXT NOT NULL CHECK (domain IN ('organic', 'ads', 'reviews', 'rating')),
                    metric_values JSONB NOT NULL CHECK (jsonb_typeof(metric_values) = 'object'),
                    source_spreadsheet_id TEXT NOT NULL CHECK (btrim(source_spreadsheet_id) <> ''),
                    source_sheet_id INTEGER NOT NULL CHECK (source_sheet_id >= 0),
                    source_sheet TEXT NOT NULL CHECK (btrim(source_sheet) <> ''),
                    source_range TEXT NOT NULL CHECK (btrim(source_range) <> ''),
                    PRIMARY KEY (batch_id, observation_index),
                    UNIQUE (batch_id, vendor_id, product_code, observation_date, domain)
                )
                """);
        executeStatement(db, """
                CREATE INDEX product_performance_history_product_date_idx
                    ON product_performance_history_observation (vendor_id, product_code, observation_date)
                """);
    }
}
