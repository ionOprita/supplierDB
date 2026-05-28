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
        try (var s = db.prepareStatement("""
                INSERT INTO brands (id, name, vendor)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING;
                """)) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, name);
            s.setObject(3, vendor);
            return s.executeUpdate();
        }
    }

    static int deleteBrand(Connection db, UUID id) throws SQLException {
        try (var s = db.prepareStatement("DELETE FROM brands WHERE id = ?")) {
            s.setObject(1, id);
            return s.executeUpdate();
        }
    }
}
