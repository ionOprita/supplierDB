package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion33 {
    static void version33(Connection db) throws SQLException {
        createBrandsTable(db);
    }

    private static void createBrandsTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE brands (
                    id UUID PRIMARY KEY,
                    name TEXT NOT NULL,
                    vendor UUID NOT NULL,
                    CONSTRAINT brands_name_non_blank CHECK (btrim(name) <> ''),
                    CONSTRAINT brands_vendor_fkey FOREIGN KEY (vendor) REFERENCES vendor(id),
                    CONSTRAINT brands_vendor_name_unique UNIQUE (vendor, name)
                );
                """);
        executeStatement(db, """
                CREATE INDEX brands_vendor_idx ON brands(vendor);
                """);
    }
}
