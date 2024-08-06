package ro.sellfluence.emagapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Customer {

    public int id;
    public int mkt_id;
    public String name;
    public String email;
    public String company;
    public String gender;
    public String code;
    public String registration_number;
    public String bank;
    public String iban;
    public String fax;
    public Integer legal_entity;
    public Integer is_vat_payer;
    public String phone_1;
    public String phone_2;
    public String phone_3;
    public String billing_name;
    public String billing_phone;
    public String billing_country;
    public String billing_suburb;
    public String billing_city;
    public String billing_locality_id;
    public String billing_street;
    public String billing_postal_code;
    public String liable_person;
    public String shipping_country;
    public String shipping_suburb;
    public String shipping_city;
    public String shipping_locality_id;
    public String shipping_street;
    public String shipping_postal_code;
    public String shipping_contact;
    public String shipping_phone;
    public LocalDateTime created;
    public LocalDateTime modified;

    @Override
    public String toString() {
        return "Customer{" +
               "id=" + id +
               ", mkt_id=" + mkt_id +
               ", name='" + name + '\'' +
               ", email='" + email + '\'' +
               ", company='" + company + '\'' +
               ", gender='" + gender + '\'' +
               ", code='" + code + '\'' +
               ", registration_number='" + registration_number + '\'' +
               ", bank='" + bank + '\'' +
               ", iban='" + iban + '\'' +
               ", fax='" + fax + '\'' +
               ", legal_entity=" + legal_entity +
               ", is_vat_payer=" + is_vat_payer +
               ", phone_1='" + phone_1 + '\'' +
               ", phone_2='" + phone_2 + '\'' +
               ", phone_3='" + phone_3 + '\'' +
               ", billing_name='" + billing_name + '\'' +
               ", billing_phone='" + billing_phone + '\'' +
               ", billing_country='" + billing_country + '\'' +
               ", billing_suburb='" + billing_suburb + '\'' +
               ", billing_city='" + billing_city + '\'' +
               ", billing_locality_id='" + billing_locality_id + '\'' +
               ", billing_street='" + billing_street + '\'' +
               ", billing_postal_code='" + billing_postal_code + '\'' +
               ", liable_person='" + liable_person + '\'' +
               ", shipping_country='" + shipping_country + '\'' +
               ", shipping_suburb='" + shipping_suburb + '\'' +
               ", shipping_city='" + shipping_city + '\'' +
               ", shipping_locality_id='" + shipping_locality_id + '\'' +
               ", shipping_street='" + shipping_street + '\'' +
               ", shipping_postal_code='" + shipping_postal_code + '\'' +
               ", shipping_contact='" + shipping_contact + '\'' +
               ", shipping_phone='" + shipping_phone + '\'' +
               ", created=" + created +
               ", modified=" + modified +
               '}';
    }

    /**
     * Build a shipping address out of the single fields.
     *
     * @return shipping address as a simple string.
     */
    public String getShippingAddress() {
        List<String> fields = new ArrayList<>();
        add(fields,shipping_street);
        add(fields,shipping_city);
        add(fields,shipping_suburb);
        add(fields,shipping_country);
        return String.join(", ", fields);
    }

    /**
     * Build a shipping address out of the single fields.
     *
     * @return shipping address as a simple string.
     */
    public String getBillingAddress() {
        List<String> fields = new ArrayList<>();
        add(fields,billing_street);
        add(fields,billing_city);
        add(fields,billing_suburb);
        add(fields,billing_country);
        return String.join(", ", fields);
    }

    /**
     * Helper method to add only existing fields to a list.
     *
     * @param fields list to which to add the fields.
     * @param item individual item. It is added only if not null and not empty.
     */
    private static void add(List<String> fields, String item) {
        if (item!=null && !item.isEmpty()) {
            fields.add(item);
        }
    }
}
