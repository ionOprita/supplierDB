package ro.sellfluence.emagapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
}