package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion33 {
    static void version33(Connection db) throws SQLException {
        createBrandsTable(db);
        populateBrandsFromProducts(db);
        migrateProductBrandToBrandReference(db);
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

    private static void populateBrandsFromProducts(Connection db) throws SQLException {
        rejectBrandsWithoutVendor(db);
        try (var select = db.prepareStatement("""
                SELECT DISTINCT btrim(brand) AS brand_name, vendor
                FROM product
                WHERE brand IS NOT NULL
                  AND btrim(brand) <> '';
                """);
             var insert = db.prepareStatement("""
                     INSERT INTO brands (id, name, vendor)
                     VALUES (?, ?, ?)
                     ON CONFLICT DO NOTHING;
                     """)) {
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, rs.getString("brand_name"));
                    insert.setObject(3, rs.getObject("vendor", UUID.class));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private static void rejectBrandsWithoutVendor(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT count(*)
                FROM product
                WHERE brand IS NOT NULL
                  AND btrim(brand) <> ''
                  AND vendor IS NULL;
                """);
             var rs = s.executeQuery()) {
            rs.next();
            var productsWithoutVendor = rs.getLong(1);
            if (productsWithoutVendor > 0) {
                throw new SQLException("Cannot migrate product.brand to brands.id: " + productsWithoutVendor + " product rows have a brand but no vendor.");
            }
        }
    }

    private static void migrateProductBrandToBrandReference(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product ADD COLUMN brand_id UUID;
                """);
        executeStatement(db, """
                UPDATE product AS p
                SET brand_id = b.id
                FROM brands AS b
                WHERE p.vendor = b.vendor
                  AND btrim(p.brand) = b.name;
                """);
        rejectUnmatchedBrandReferences(db);
        executeStatement(db, """
                ALTER TABLE product DROP COLUMN brand;
                """);
        executeStatement(db, """
                ALTER TABLE product RENAME COLUMN brand_id TO brand;
                """);
        executeStatement(db, """
                ALTER TABLE product
                    ADD CONSTRAINT product_brand_fkey FOREIGN KEY (brand) REFERENCES brands(id);
                """);
        executeStatement(db, """
                CREATE INDEX product_brand_idx ON product(brand);
                """);
    }

    private static void rejectUnmatchedBrandReferences(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT count(*)
                FROM product
                WHERE brand IS NOT NULL
                  AND btrim(brand) <> ''
                  AND brand_id IS NULL;
                """);
             var rs = s.executeQuery()) {
            rs.next();
            var unresolvedBrands = rs.getLong(1);
            if (unresolvedBrands > 0) {
                throw new SQLException("Cannot migrate product.brand to brands.id: " + unresolvedBrands + " product rows could not be matched to a brand id.");
            }
        }
    }
}
