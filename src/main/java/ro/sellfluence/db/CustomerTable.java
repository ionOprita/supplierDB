package ro.sellfluence.db;

import ro.sellfluence.emagapi.Customer;
import ro.sellfluence.support.Logs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static ro.sellfluence.support.UsefulMethods.toLocalDateTime;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class CustomerTable {

    private static final Logger debugLogger = Logs.getFileLogger("emag_customer", FINE, 5, 10_000_000);

    /**
     * Insert a customer into the database.
     * If there is already a customer by the same ID, the record is updated if the data differs and the new customer has
     * a more recent modified date.
     *
     * @param db database
     * @param customer new customer record
     * @throws SQLException in case of malfunction
     */
    static void insertOrUpdateCustomer(Connection db, final Customer customer) throws SQLException {
        var added = insertCustomer(db, customer);
        if (added == 0) {
            var current = selectCustomer(db, customer.id());
            if (!customer.sameExceptForDate(current) && customer.modified().isAfter(current.modified())) {
                debugLogger.log(FINE, () -> "Updating customer:%n old: %s%n new: %s%n".formatted(current, customer));
                updateCustomer(db, customer);
            }
        }
    }

    private static int insertCustomer(Connection db, Customer c) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO customer (
                id, mkt_id, name, email, company, gender, code, registration_number, bank, iban, fax, legal_entity,
                is_vat_payer, phone_1, phone_2, phone_3, billing_name, billing_phone, billing_country, billing_suburb,
                billing_city, billing_locality_id, billing_street, billing_postal_code, liable_person, shipping_country,
                shipping_suburb, shipping_city, shipping_locality_id, shipping_street,
                shipping_postal_code, shipping_contact, shipping_phone, created, modified
                )
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING""")) {
            s.setInt(1, c.id());
            s.setInt(2, c.mkt_id());
            s.setString(3, c.name());
            s.setString(4, c.email());
            s.setString(5, c.company());
            s.setString(6, c.gender());
            s.setString(7, c.code());
            s.setString(8, c.registration_number());
            s.setString(9, c.bank());
            s.setString(10, c.iban());
            s.setString(11, c.fax());
            s.setInt(12, c.legal_entity());
            s.setInt(13, c.is_vat_payer());
            s.setString(14, c.phone_1());
            s.setString(15, c.phone_2());
            s.setString(16, c.phone_3());
            s.setString(17, c.billing_name());
            s.setString(18, c.billing_phone());
            s.setString(19, c.billing_country());
            s.setString(20, c.billing_suburb());
            s.setString(21, c.billing_city());
            s.setString(22, c.billing_locality_id());
            s.setString(23, c.billing_street());
            s.setString(24, c.billing_postal_code());
            s.setString(25, c.liable_person());
            s.setString(26, c.shipping_country());
            s.setString(27, c.shipping_suburb());
            s.setString(28, c.shipping_city());
            s.setString(29, c.shipping_locality_id());
            s.setString(30, c.shipping_street());
            s.setString(31, c.shipping_postal_code());
            s.setString(32, c.shipping_contact());
            s.setString(33, c.shipping_phone());
            s.setTimestamp(34, toTimestamp(c.created()));
            s.setTimestamp(35, toTimestamp(c.modified()));
            return s.executeUpdate();
        }
    }

    private static Customer selectCustomer(Connection db, int customerId) throws SQLException {
        Customer customer = null;
        try (var s = db.prepareStatement("SELECT * FROM customer WHERE id = ?")) {
            s.setInt(1, customerId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    customer = new Customer(customerId, rs.getInt("mkt_id"), rs.getString("name"), rs.getString("email"), rs.getString("company"), rs.getString("gender"), rs.getString("code"), rs.getString("registration_number"), rs.getString("bank"), rs.getString("iban"), rs.getString("fax"), rs.getInt("legal_entity"), rs.getInt("is_vat_payer"), rs.getString("phone_1"), rs.getString("phone_2"), rs.getString("phone_3"), rs.getString("billing_name"), rs.getString("billing_phone"), rs.getString("billing_country"), rs.getString("billing_suburb"), rs.getString("billing_city"), rs.getString("billing_locality_id"), rs.getString("billing_street"), rs.getString("billing_postal_code"), rs.getString("liable_person"), rs.getString("shipping_country"), rs.getString("shipping_suburb"), rs.getString("shipping_city"), rs.getString("shipping_locality_id"), rs.getString("shipping_street"), rs.getString("shipping_postal_code"), rs.getString("shipping_contact"), rs.getString("shipping_phone"), toLocalDateTime(rs.getTimestamp("created")), toLocalDateTime(rs.getTimestamp("modified")));
                }
            }
        }
        return customer;
    }


    private static int updateCustomer(Connection db, Customer c) throws SQLException {
        try (var s = db.prepareStatement("UPDATE customer SET mkt_id = ?, name = ?, email = ?, company = ?, gender = ?, code = ?, registration_number = ?, bank = ?, iban = ?, fax = ?, legal_entity = ?, is_vat_payer = ?, phone_1 = ?, phone_2 = ?, phone_3 = ?, billing_name = ?, billing_phone = ?, billing_country = ?, billing_suburb = ?, billing_city = ?, billing_locality_id = ?, billing_street = ?, billing_postal_code = ?, liable_person = ?, shipping_country = ?, shipping_suburb = ?, shipping_city = ?, shipping_locality_id = ?, shipping_street = ?, shipping_postal_code = ?, shipping_contact = ?, shipping_phone = ?, modified = ? WHERE id = ?")) {
            s.setInt(1, c.mkt_id());
            s.setString(2, c.name());
            s.setString(3, c.email());
            s.setString(4, c.company());
            s.setString(5, c.gender());
            s.setString(6, c.code());
            s.setString(7, c.registration_number());
            s.setString(8, c.bank());
            s.setString(9, c.iban());
            s.setString(10, c.fax());
            s.setInt(11, c.legal_entity());
            s.setInt(12, c.is_vat_payer());
            s.setString(13, c.phone_1());
            s.setString(14, c.phone_2());
            s.setString(15, c.phone_3());
            s.setString(16, c.billing_name());
            s.setString(17, c.billing_phone());
            s.setString(18, c.billing_country());
            s.setString(19, c.billing_suburb());
            s.setString(20, c.billing_city());
            s.setString(21, c.billing_locality_id());
            s.setString(22, c.billing_street());
            s.setString(23, c.billing_postal_code());
            s.setString(24, c.liable_person());
            s.setString(25, c.shipping_country());
            s.setString(26, c.shipping_suburb());
            s.setString(27, c.shipping_city());
            s.setString(28, c.shipping_locality_id());
            s.setString(29, c.shipping_street());
            s.setString(30, c.shipping_postal_code());
            s.setString(31, c.shipping_contact());
            s.setString(32, c.shipping_phone());
            s.setTimestamp(33, toTimestamp(c.modified()));
            s.setInt(34, c.id());
            return s.executeUpdate();
        }
    }
}