package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** An import and its ordered child records share one revision and one vendor-group owner. */
public final class EmagMirrorDBVersion55 {
    private EmagMirrorDBVersion55() {}

    public static void version55(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE stock_import (
                    id UUID PRIMARY KEY,
                    vendor_group TEXT NOT NULL CHECK (btrim(vendor_group) <> ''),
                    revision BIGINT NOT NULL CHECK (revision > 0),
                    fields JSONB NOT NULL CHECK (jsonb_typeof(fields) = 'object'),
                    units NUMERIC, eta DATE, total_ron NUMERIC,
                    source_book TEXT,
                    source_tab INTEGER,
                    source_key TEXT,
                    source_column INTEGER,
                    source_fingerprint TEXT,
                    source_revision BIGINT,
                    CHECK ((source_book IS NULL AND source_tab IS NULL AND source_key IS NULL
                            AND source_column IS NULL AND source_fingerprint IS NULL AND source_revision IS NULL)
                        OR (source_book IS NOT NULL AND source_tab IS NOT NULL AND source_key IS NOT NULL
                            AND source_column > 0 AND source_column IS NOT NULL AND source_fingerprint IS NOT NULL
                            AND source_revision IS NOT NULL)),
                    UNIQUE(vendor_group,source_book,source_tab,source_key)
                )
                """);
        executeStatement(db, """
                CREATE INDEX stock_import_register ON stock_import(vendor_group,(fields->>'orderDate') DESC)
                """);
        executeStatement(db, """
                CREATE UNIQUE INDEX stock_import_reference ON stock_import(vendor_group,btrim(fields->>'reference'))
                WHERE btrim(fields->>'reference') <> ''
                """);
        executeStatement(db, """
                CREATE TABLE stock_import_line (
                    import_id UUID NOT NULL REFERENCES stock_import ON DELETE CASCADE, position INTEGER NOT NULL,
                    product_code TEXT, model TEXT, pnk TEXT, quantity NUMERIC, unit_usd NUMERIC,
                    pieces_per_carton NUMERIC, kg_per_carton NUMERIC, m3_per_carton NUMERIC, duty_rate NUMERIC,
                    PRIMARY KEY(import_id,position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE stock_import_transfer (
                    import_id UUID NOT NULL REFERENCES stock_import ON DELETE CASCADE, position INTEGER NOT NULL,
                    kind TEXT NOT NULL, transfer_date DATE, reference TEXT, ron NUMERIC, usd NUMERIC, rate NUMERIC,
                    PRIMARY KEY(import_id,position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE stock_import_taric (
                    import_id UUID NOT NULL REFERENCES stock_import ON DELETE CASCADE, position INTEGER NOT NULL,
                    code TEXT, net_kg NUMERIC, gross_kg NUMERIC, quantity NUMERIC, PRIMARY KEY(import_id,position)
                )
                """);
        executeStatement(db, """
                CREATE TABLE stock_import_invoice (
                    import_id UUID NOT NULL REFERENCES stock_import ON DELETE CASCADE, position INTEGER NOT NULL,
                    kind TEXT NOT NULL, reference TEXT NOT NULL, PRIMARY KEY(import_id,position)
                )
                """);
    }
}
