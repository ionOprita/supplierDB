package ro.sellfluence.db;

import java.sql.Connection;
import java.sql.SQLException;

class EmagMirrorDBVersion1 {


    /**
     * Create the tables for the first version of the database.
     * Reset the database with DROP TABLE VoucherSplit, Product, Voucher, Attachment, Order, Customer;
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version1(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE vendor(
                  id UUID,
                  vendor_name VARCHAR(255) UNIQUE NOT NULL,
                  isFBE BOOLEAN,
                  PRIMARY KEY (id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE locker_details(
                  locker_id VARCHAR(255),
                  locker_name VARCHAR(255),
                  PRIMARY KEY (locker_id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE customer(
                  id INTEGER,
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
                  modified TIMESTAMP,
                  PRIMARY KEY (id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE emag_order(
                  id VARCHAR(255),
                  vendor_id UUID,
                  status INTEGER,
                  is_complete INTEGER,
                  type INTEGER,
                  payment_mode VARCHAR(255),
                  payment_mode_id INTEGER,
                  delivery_payment_mode VARCHAR(255),
                  delivery_mode VARCHAR(255),
                  observation VARCHAR(255),
                  details_id  VARCHAR(255),
                  date TIMESTAMP,
                  payment_status INTEGER,
                  cashed_co DECIMAL(10, 2),
                  cashed_cod DECIMAL(10, 2),
                  shipping_tax DECIMAL(10, 2),
                  customer_id INTEGER,
                  is_storno BOOLEAN,
                  cancellation_reason INTEGER,
                  refunded_amount DECIMAL(10, 2),
                  refund_status VARCHAR(255),
                  maximum_date_for_shipment TIMESTAMP,
                  finalization_date TIMESTAMP,
                  PRIMARY KEY (id, vendor_id),
                  FOREIGN KEY (details_id) REFERENCES locker_details(locker_id),
                  FOREIGN KEY (customer_id) REFERENCES customer(id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE attachment(
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    name VARCHAR(255) NOT NULL,
                    url VARCHAR(1024) NOT NULL,
                    type INT,
                    force_download INT,
                    PRIMARY KEY (order_id, url),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE voucher(
                    voucher_id INT,
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    modified VARCHAR(255),
                    created VARCHAR(255),
                    status INT,
                    sale_price_vat DECIMAL(19, 4),
                    sale_price DECIMAL(19, 4),
                    voucher_name VARCHAR(255),
                    vat DECIMAL(19, 4),
                    issue_date VARCHAR(255),
                    PRIMARY KEY (voucher_id),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );
                """)) {
            s.execute();
        }
        // This product table represents the data included in an emag order.
        try (var s = db.prepareStatement("""
                CREATE TABLE product_in_order(
                    id INTEGER,
                    order_id VARCHAR(255),
                    vendor_id UUID,
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
                    PRIMARY KEY (id),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );
                """)) {
            s.execute();
        }
        // Either the order_id or the product_id will be null.
        // If this doesn't work, then make two tables, one for order voucher splits and one for product voucher splits.
        try (var s = db.prepareStatement("""
                CREATE TABLE voucher_split(
                    voucher_id INTEGER,
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    product_id INTEGER,
                    value DECIMAL(19, 4),
                    vat_value DECIMAL(19, 4),
                    PRIMARY KEY (voucher_id),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id),
                    FOREIGN KEY (product_id) REFERENCES product_in_order(id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE rma_results (
                   emag_id INT PRIMARY KEY,
                   order_id INT,
                   type INT,
                   date TIMESTAMP,
                   request_status INT,
                   return_type INT,
                   return_reason INT,
                   observations TEXT
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE emag_returned_products (
                    id INT PRIMARY KEY,
                    rma_result_id INT,
                    product_emag_id INT,
                    product_id INT,
                    quantity INT,
                    product_name VARCHAR(255),
                    return_reason INT,
                    observations TEXT,
                    diagnostic INT,
                    reject_reason INT,
                    refund_value VARCHAR(255),
                    FOREIGN KEY (rma_result_id) REFERENCES rma_results(emag_id)
                );
                """)) {
            s.execute();
        }
        try (var s = db.prepareStatement("""
                CREATE TABLE product (
                    id UUID PRIMARY KEY,
                    emag_pnk VARCHAR(255) UNIQUE,
                    name VARCHAR(255),
                    category VARCHAR(255),
                    message_keyword VARCHAR(255)
                    -- and so on, this data will be filled in initially from "Date produse & angajati" sheet "Cons. Date. Prod."
                );
                """)) {
            s.execute();
        }
        try (var s=db.prepareStatement("""
                CREATE TABLE emag_fetch_log (
                    emag_login VARCHAR(255) UNIQUE,
                    order_start TIMESTAMP NOT NULL,
                    order_end TIMESTAMP NOT NULL,
                    fetch_start TIMESTAMP NOT NULL,
                    fetch_end TIMESTAMP NOT NULL,
                    error VARCHAR(1024)
                );
                """)) {
            s.execute();
        }
    }
}