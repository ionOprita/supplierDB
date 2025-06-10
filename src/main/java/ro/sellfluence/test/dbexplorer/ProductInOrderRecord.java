package ro.sellfluence.test.dbexplorer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public record ProductInOrderRecord(
        int id,
        String name,
        String pnk,
        String externalId,
        int quantity,
        int initialQuantity,
        int stornoQuantity,
        BigDecimal salePrice,
        BigDecimal originalPrice
) {
    public static List<ProductInOrderRecord> getProductInOrderByOrder(Connection db, String orderId) throws SQLException {
        var products = new ArrayList<ProductInOrderRecord>();
        String query = """
                SELECT
                    p.id as product_id,
                    p.name,
                    p.part_number_key,
                    p.ext_part_number,
                    p.quantity,
                    p.initial_qty,
                    p.storno_qty,
                    p.sale_price,
                    p.original_price
                FROM product_in_order p
                INNER JOIN emag_order o ON p.emag_order_surrogate_id = o.surrogate_id
                WHERE o.id = ?;
                """;
        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setString(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    products.add(new ProductInOrderRecord(
                            rs.getInt("product_id"),
                            rs.getString("name"),
                            rs.getString("part_number_key"),
                            rs.getString("ext_part_number"),
                            rs.getInt("quantity"),
                            rs.getInt("initial_qty"),
                            rs.getInt("storno_qty"),
                            rs.getBigDecimal("sale_price"),
                            rs.getBigDecimal("original_price")
                    ));
                }
            }
        }
        return products;
    }
}