package ro.koppel.emag;

public class Customer {

    public int id;
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
    public String billing_street;
    public String billing_postal_code;
    public String shipping_contact;
    public String shipping_phone;

    @Override
    public String toString() {
        return "Customer{" +
                "id=" + id +
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
                ", billing_street='" + billing_street + '\'' +
                ", billing_postal_code='" + billing_postal_code + '\'' +
                ", shipping_contact='" + shipping_contact + '\'' +
                ", shipping_phone='" + shipping_phone + '\'' +
                '}';
    }
}
