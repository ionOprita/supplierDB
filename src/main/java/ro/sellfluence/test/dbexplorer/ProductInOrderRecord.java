package ro.sellfluence.test.dbexplorer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public record ProductInOrderRecord(
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
        return null;
    }
}