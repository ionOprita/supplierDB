package ro.sellfluence.test.dbexplorer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OrderRecord(
        String orderId,
        UUID vendorId,
        String vendorName,
        Integer customerId,
        Integer status,
        Timestamp date,
        Timestamp created,
        Timestamp modified,
        long surrogateId
) {
    public static List<OrderRecord> getOrdersById(Connection db, String orderId) throws SQLException {
        var orders = new ArrayList<OrderRecord>();
        String query = """
                SELECT
                    o.id AS orderId,
                    o.vendor_id AS vendorId,
                    v.vendor_name AS vendorName,
                    o.customer_id AS customerId,
                    o.status,
                    o.date,
                    o.created,
                    o.modified,
                    o.surrogate_id
                FROM emag_order o
                JOIN vendor v ON o.vendor_id = v.id
                WHERE o.id = ?;
                """;

        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setString(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    orders.add(new OrderRecord(
                            rs.getString("orderId"),
                            UUID.fromString(rs.getString("vendorId")),
                            rs.getString("vendorName"),
                            rs.getInt("customerId"),
                            rs.getInt("status"),
                            rs.getTimestamp("date"),
                            rs.getTimestamp("created"),
                            rs.getTimestamp("modified"),
                            rs.getLong("surrogate_id")
                    ));
                }
            }
        }
        return orders;
    }
    public static List<OrderRecord> getOrdersByCustomerId(Connection db, int customerId) throws SQLException {
        var orders = new ArrayList<OrderRecord>();
        String query = """
                SELECT
                    o.id AS orderId,
                    o.vendor_id AS vendorId,
                    v.vendor_name AS vendorName,
                    o.customer_id AS customerId,
                    o.status,
                    o.date,
                    o.created,
                    o.modified
                    o.surrogate_id
               FROM emag_order o
                JOIN vendor v ON o.vendor_id = v.id
                WHERE o.customer_id = ?;
                """;

        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    orders.add(new OrderRecord(
                            rs.getString("orderId"),
                            UUID.fromString(rs.getString("vendorId")),
                            rs.getString("vendorName"),
                            rs.getInt("customerId"),
                            rs.getInt("status"),
                            rs.getTimestamp("date"),
                            rs.getTimestamp("created"),
                            rs.getTimestamp("modified"),
                            rs.getLong("surrogate_id")
                    ));
                }
            }
        }
        return orders;
    }
}