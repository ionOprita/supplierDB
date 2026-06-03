package ro.sellfluence.db;

import org.jspecify.annotations.NonNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record Brand(UUID id, String name, UUID vendor, String vendorName) {
    static @NonNull List<Brand> getBrands(Connection db) throws SQLException {
        var brands = new ArrayList<Brand>();
        try (var s = db.prepareStatement("""
                SELECT b.id, b.name, b.vendor, v.vendor_name
                FROM brands AS b
                INNER JOIN vendor AS v ON b.vendor = v.id
                ORDER BY v.vendor_name, b.name;
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    brands.add(new Brand(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getObject("vendor", UUID.class),
                            rs.getString("vendor_name")
                    ));
                }
            }
        }
        return brands;
    }

    static int insertBrand(Connection db, String name, UUID vendor) throws SQLException {
        var normalizedName = normalizeName(name);
        if (normalizedName == null) {
            throw new IllegalArgumentException("Brand name is required.");
        }
        if (vendor == null) {
            throw new IllegalArgumentException("Select a vendor.");
        }
        try (var s = db.prepareStatement("""
                INSERT INTO brands (id, name, vendor)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING;
                """)) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, normalizedName);
            s.setObject(3, vendor);
            return s.executeUpdate();
        }
    }

    static UUID insertOrGetBrand(Connection db, String name, UUID vendor) throws SQLException {
        var normalizedName = normalizeName(name);
        if (normalizedName == null) {
            return null;
        }
        if (vendor == null) {
            throw new IllegalArgumentException("A product brand requires a vendor.");
        }
        insertBrand(db, normalizedName, vendor);
        return getBrandId(db, normalizedName, vendor);
    }

    private static UUID getBrandId(Connection db, String name, UUID vendor) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT id
                FROM brands
                WHERE name = ?
                  AND vendor = ?;
                """)) {
            s.setString(1, name);
            s.setObject(2, vendor);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
            }
        }
        throw new SQLException("Brand could not be inserted or found: " + name + ".");
    }

    static int deleteBrand(Connection db, UUID id) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM brands WHERE id = ?")) {
            s.setObject(1, id);
            return s.executeUpdate();
        }
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        var normalized = name.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
