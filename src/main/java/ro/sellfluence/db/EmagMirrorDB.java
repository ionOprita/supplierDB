package ro.sellfluence.db;

import ch.claudio.db.DB;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class EmagMirrorDB {

    public static void main(String[] args) throws IOException, SQLException {
        var db = new DB("emag");
        db.prepareDB(EmagMirrorDB::version1);
    }

    private static void version1(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
CREATE TABLE LockerDetails(
  id INTEGER PRIMARY KEY,
  locker_id VARCHAR(255),
  locker_name VARCHAR(255)
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE Customer(
  id INTEGER PRIMARY KEY,
  mkt_id INTEGER,
  name VARCHAR(255),
  email VARCHAR(255),
  company VARCHAR(255),
  gender VARCHAR(255),
  code VARCHAR(255),
  registration_number VARCHAR(255),
  bank VARCHAR(255),
  iban VARCHAR(255),
  fax VARCHAR(255),
  legal_entity INTEGER,
  is_vat_payer INTEGER,
  phone_1 VARCHAR(255),
  phone_2 VARCHAR(255),
  phone_3 VARCHAR(255),
  billing_name VARCHAR(255),
  billing_phone VARCHAR(255),
  billing_country VARCHAR(255),
  billing_suburb VARCHAR(255),
  billing_city VARCHAR(255),
  billing_locality_id VARCHAR(255),
  billing_street VARCHAR(255),
  billing_postal_code VARCHAR(255),
  liable_person VARCHAR(255),
  shipping_country VARCHAR(255),
  shipping_suburb VARCHAR(255),
  shipping_city VARCHAR(255),
  shipping_locality_id VARCHAR(255),
  shipping_street VARCHAR(255),
  shipping_postal_code VARCHAR(255),
  shipping_contact VARCHAR(255),
  shipping_phone VARCHAR(255),
  created TIMESTAMP,
  modified TIMESTAMP
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE OrderResult(
  id VARCHAR(255),
  vendor_name VARCHAR(255),
  status INTEGER,
  is_complete INTEGER,
  type INTEGER,
  payment_mode VARCHAR(255),
  payment_mode_id INTEGER,
  delivery_payment_mode VARCHAR(255),
  delivery_mode VARCHAR(255),
  observation VARCHAR(255),
  details_id INTEGER,
  date TIMESTAMP,
  payment_status INTEGER,
  cashed_co DECIMAL(10, 2),
  cashed_cod DECIMAL(10, 2),
  shipping_tax DECIMAL(10, 2),
  customer_id INTEGER,
  is_storno BOOLEAN,
  cancellation_reason INTEGER,
  PRIMARY KEY (id),
  FOREIGN KEY (details_id) REFERENCES LockerDetails(id),
  FOREIGN KEY (customer_id) REFERENCES Customer(id)
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE Order(
  id VARCHAR(255),
  vendor_name VARCHAR(255),
  status INTEGER,
  is_complete INTEGER,
  type INTEGER,
  payment_mode VARCHAR(255),
  payment_mode_id INTEGER,
  delivery_payment_mode VARCHAR(255),
  delivery_mode VARCHAR(255),
  observation VARCHAR(255),
  details_id INTEGER,
  date TIMESTAMP,
  payment_status INTEGER,
  cashed_co DECIMAL(10, 2),
  cashed_cod DECIMAL(10, 2),
  shipping_tax DECIMAL(10, 2),
  customer_id INTEGER,
  is_storno BOOLEAN,
  cancellation_reason INTEGER,
  PRIMARY KEY (id),
  FOREIGN KEY (details_id) REFERENCES LockerDetails(id),
  FOREIGN KEY (customer_id) REFERENCES Customer(id)
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE Attachment(
    order_id INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(255) NOT NULL,
    type INT,
    force_download INT,
    FOREIGN KEY (order_id) REFERENCES Order(id)
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE Voucher(
    voucher_id INT,
    order_id INT NOT NULL,
    modified VARCHAR(255),
    created VARCHAR(255),
    status INT,
    sale_price_vat DECIMAL(19, 4),
    sale_price DECIMAL(19, 4),
    voucher_name VARCHAR(255),
    vat DECIMAL(19, 4),
    issue_date VARCHAR(255),
    FOREIGN KEY (order_id) REFERENCES Order(id)
);
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
CREATE TABLE Product(
    id INT NOT NULL,
    order_id INT NOT NULL,
    product_id INT,
    mkt_id INT,
    name VARCHAR(255),
    status INT NOT NULL,
    ext_part_number VARCHAR(255),
    part_number VARCHAR(255),
    part_number_key VARCHAR(255),
    currency VARCHAR(255),
    vat VARCHAR(255),
    retained_amount INT,
    quantity INT,
    initial_qty INT,
    storno_qty INT,
    reversible_vat_charging INT,
    sale_price DECIMAL(19, 4),
    original_price DECIMAL(19, 4),
    created TIMESTAMP,
    modified TIMESTAMP,
    details TEXT,
    recycle_warranties TEXT,
    FOREIGN KEY (order_id) REFERENCES Order(id)
);
                """)) {
            s.execute();
        }
        // Either the order_id or the product_id will be null.
        // If this doesn't work, then make two tables, one for order voucher splits and one for product voucher splits.
        try (var s = db.prepareStatement("""
CREATE TABLE VoucherSplit(
    voucher_id INT,
    order_id INT,
    product_id INT,
    value DECIMAL(19, 4),
    vat_value DECIMAL(19, 4),
    FOREIGN KEY (order_id) REFERENCES Order(id),
    FOREIGN KEY (product_id) REFERENCES Product(id)
);
                """)) {
            s.execute();
        }
    }
}
