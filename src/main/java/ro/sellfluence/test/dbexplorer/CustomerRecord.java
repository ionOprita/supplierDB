package ro.sellfluence.test.dbexplorer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public record CustomerRecord(
        Integer id,
        String name,
        String email,
        String phone1,
        String billingInfo,
        String shippingInfo,
        Timestamp created,
        Timestamp modified
) {
    public static List<CustomerRecord> getCustomerById(Connection db, Integer customerId) throws SQLException {
        List<CustomerRecord> customers = new ArrayList<>();
        String query = """
                SELECT
                    id,
                    name,
                    email,
                    phone_1,
                    CONCAT_WS(', ', billing_locality_id, billing_name, billing_phone, billing_street, billing_country, billing_postal_code, billing_suburb, billing_city) AS billingInfo,
                    CONCAT_WS(', ', shipping_locality_id, shipping_contact, shipping_phone, shipping_street, shipping_country, shipping_postal_code, shipping_suburb, shipping_city) AS shippingInfo,
                    created,
                    modified
                FROM customer
                WHERE id = ?;
                """;

        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    customers.add(new CustomerRecord(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("email"),
                            rs.getString("phone_1"),
                            rs.getString("billingInfo"),
                            rs.getString("shippingInfo"),
                            rs.getTimestamp("created"),
                            rs.getTimestamp("modified")
                    ));
                }
            }
        }
        return customers;
    }
}
