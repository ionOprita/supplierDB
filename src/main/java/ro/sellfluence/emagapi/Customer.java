package ro.sellfluence.emagapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Customer(
        int id,
        int mkt_id,
        String name,
        String email,
        String company,
        String gender,
        String code,
        String registration_number,
        String bank,
        String iban,
        String fax,
        Integer legal_entity,
        Integer is_vat_payer,
        String phone_1,
        String phone_2,
        String phone_3,
        String billing_name,
        String billing_phone,
        String billing_country,
        String billing_suburb,
        String billing_city,
        String billing_locality_id,
        String billing_street,
        String billing_postal_code,
        String liable_person,
        String shipping_country,
        String shipping_suburb,
        String shipping_city,
        String shipping_locality_id,
        String shipping_street,
        String shipping_postal_code,
        String shipping_contact,
        String shipping_phone,
        LocalDateTime created,
        LocalDateTime modified
) {

    /**
     * Build a shipping address out of the single fields.
     *
     * @return shipping address as a simple string.
     */
    public String getShippingAddress() {
        var fields = new ArrayList<String>();
        add(fields, shipping_street);
        add(fields, shipping_city);
        add(fields, shipping_suburb);
        add(fields, shipping_country);
        return String.join(", ", fields);
    }

    /**
     * Build a billing address out of the single fields.
     *
     * @return billing address as a simple string.
     */
    public String getBillingAddress() {
        var fields = new ArrayList<String>();
        add(fields, billing_street);
        add(fields, billing_city);
        add(fields, billing_suburb);
        add(fields, billing_country);
        return String.join(", ", fields);
    }

    /**
     * Helper method to add only existing fields to a list.
     *
     * @param fields list to which to add the fields.
     * @param item individual item. It is added only if not null and not empty.
     */
    private static void add(List<String> fields, String item) {
        if (item != null && !item.isEmpty()) {
            fields.add(item);
        }
    }

    public boolean sameExceptForDate(Object o) {
        if (!(o instanceof Customer customer)) return false;
        return id == customer.id && mkt_id == customer.mkt_id && Objects.equals(fax, customer.fax) && Objects.equals(name, customer.name) && Objects.equals(code, customer.code) && Objects.equals(bank, customer.bank) && Objects.equals(iban, customer.iban) && Objects.equals(email, customer.email) && Objects.equals(gender, customer.gender) && Objects.equals(company, customer.company) && Objects.equals(phone_1, customer.phone_1) && Objects.equals(phone_2, customer.phone_2) && Objects.equals(phone_3, customer.phone_3) && Objects.equals(billing_name, customer.billing_name) && Objects.equals(billing_city, customer.billing_city) && Objects.equals(legal_entity, customer.legal_entity) && Objects.equals(is_vat_payer, customer.is_vat_payer) && Objects.equals(billing_phone, customer.billing_phone) && Objects.equals(liable_person, customer.liable_person) && Objects.equals(shipping_city, customer.shipping_city) && Objects.equals(billing_suburb, customer.billing_suburb) && Objects.equals(billing_street, customer.billing_street) && Objects.equals(shipping_phone, customer.shipping_phone) && Objects.equals(billing_country, customer.billing_country) && Objects.equals(shipping_suburb, customer.shipping_suburb) && Objects.equals(shipping_street, customer.shipping_street) && Objects.equals(shipping_country, customer.shipping_country) && Objects.equals(shipping_contact, customer.shipping_contact) && Objects.equals(registration_number, customer.registration_number) && Objects.equals(billing_locality_id, customer.billing_locality_id) && Objects.equals(billing_postal_code, customer.billing_postal_code) && Objects.equals(shipping_locality_id, customer.shipping_locality_id) && Objects.equals(shipping_postal_code, customer.shipping_postal_code);
    }

}