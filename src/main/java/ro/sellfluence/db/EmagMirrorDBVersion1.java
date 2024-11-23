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
        createVendorTable(db);
        createLockerDetailTable(db);
        createCustomerTable(db);
        createEmagOrderTable(db);
        createFlagTable(db);
        createAttachmentTable(db);
        createVoucherTable(db);
        // This product table represents the data included in an emag order.
        createProductInOrderTable(db);
        // Either the order_id or the product_id will be null.
        // If this doesn't work, then make two tables, one for order voucher splits and one for product voucher splits.
        createVoucherSplitTable(db);
        createRMAResultTable(db);
        createEmagReturnedProductaTable(db);
        createProductTable(db);
        createAWBTable(db);
        createStatusHistoryTable(db);
        createStatusRequestTable(db);
        createEmagFetchLogTable(db);
    }

    private static void createEmagFetchLogTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE emag_fetch_log (
                    emag_login VARCHAR(255),
                    order_start TIMESTAMP NOT NULL,
                    order_end TIMESTAMP NOT NULL,
                    fetch_start TIMESTAMP NOT NULL,
                    fetch_end TIMESTAMP NOT NULL,
                    error VARCHAR(65536),
                    PRIMARY KEY (emag_login, order_start, order_end)
                );
                """)) {
            s.execute();
        }
    }

    private static void createStatusRequestTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE status_request (
                    amount DECIMAL(19,2),
                    created TIMESTAMP,
                    refund_type VARCHAR(255),
                    refund_status VARCHAR(255),
                    rma_id VARCHAR(255),
                    status_date TIMESTAMP,
                    status_history_uuid UUID, -- Foreign key referencing status_history.uuid
                    FOREIGN KEY (status_history_uuid) REFERENCES status_history(uuid)
                );
                """)) {
            s.execute();
        }
    }

    private static void createStatusHistoryTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE status_history (
                    uuid UUID PRIMARY KEY,
                    code VARCHAR(255),
                    event_date TIMESTAMP,
                    emag_id INT, -- Foreign key referencing rma_result.emag_id
                    FOREIGN KEY (emag_id) REFERENCES rma_result(emag_id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createAWBTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE awb (
                    reservation_id INT PRIMARY KEY,
                    emag_id INT, -- Foreign key referencing rma_result.emag_id
                    FOREIGN KEY (emag_id) REFERENCES rma_result(emag_id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createProductTable(Connection db) throws SQLException {
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
    }

    private static void createEmagReturnedProductaTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                            CREATE TABLE emag_returned_products (
                id INT PRIMARY KEY,
                product_emag_id INT,
                product_id INT,
                quantity INT,
                product_name VARCHAR(255),
                return_reason INT,
                observations TEXT,
                diagnostic INT,
                reject_reason INT,
                retained_amount INT,
                emag_id INT, -- Foreign key referencing rma_result.emag_id
                FOREIGN KEY (emag_id) REFERENCES rma_result(emag_id)
                            );
                """)) {
            s.execute();
        }
    }

    private static void createRMAResultTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                            CREATE TABLE rma_result (
                emag_id INT PRIMARY KEY,
                is_full_fbe INT,
                return_parent_id INT,
                order_id VARCHAR(255),
                type INT,
                is_club INT,
                is_fast INT,
                customer_name VARCHAR(255),
                customer_company VARCHAR(255),
                customer_phone VARCHAR(255),
                pickup_country VARCHAR(255),
                pickup_suburb VARCHAR(255),
                pickup_city VARCHAR(255),
                pickup_address VARCHAR(255),
                pickup_zipcode VARCHAR(255),
                pickup_locality_id INT,
                pickup_method INT,
                customer_account_iban VARCHAR(255),
                customer_account_bank VARCHAR(255),
                customer_account_beneficiary VARCHAR(255),
                replacement_product_emag_id INT,
                replacement_product_id INT,
                replacement_product_name VARCHAR(255),
                replacement_product_quantity INT,
                observations TEXT,
                request_status INT,
                return_type INT,
                return_reason INT,
                date TIMESTAMP,
                maximum_finalization_date TIMESTAMP,
                first_pickup_date TIMESTAMP,
                estimated_product_pickup TIMESTAMP,
                estimated_product_reception TIMESTAMP,
                return_tax_value VARCHAR(255),
                swap VARCHAR(255),
                return_address_snapshot TEXT,
                request_history TEXT,
                locker_hash VARCHAR(255),
                locker_pin VARCHAR(255),
                locker_pin_interval_end TIMESTAMP,
                return_address_id INT,
                country VARCHAR(255),
                address_type VARCHAR(255),
                request_status_reason INT
                            );
                """)) {
            s.execute();
        }
    }

    private static void createVoucherSplitTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE voucher_split(
                    voucher_id INTEGER,
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    product_id INTEGER,
                    value DECIMAL(19, 4),
                    vat_value DECIMAL(19, 4),
                    vat VARCHAR(255),
                    offered_by VARCHAR(255),
                    PRIMARY KEY (voucher_id),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id),
                    FOREIGN KEY (product_id) REFERENCES product_in_order(id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createProductInOrderTable(Connection db) throws SQLException {
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
    }

    private static void createVoucherTable(Connection db) throws SQLException {
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
                    id VARCHAR(255),
                    PRIMARY KEY (voucher_id),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                 );
                """)) {
            s.execute();
        }
    }


    private static void createAttachmentTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE attachment(
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    name VARCHAR(255) NOT NULL,
                    url VARCHAR(1024) NOT NULL,
                    type INT,
                    force_download INT,
                    visibility VARCHAR(255),
                    PRIMARY KEY (order_id, url),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createFlagTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE flag(
                    order_id VARCHAR(255),
                    vendor_id UUID,
                    flag VARCHAR(255),
                    value VARCHAR(255),
                    FOREIGN KEY (order_id, vendor_id) REFERENCES emag_order(id, vendor_id)
                );
                """)) {
            s.execute();
        }
    }
    private static void createEmagOrderTable(Connection db) throws SQLException {
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
                  parent_id VARCHAR(255),
                  detailed_payment_method VARCHAR(255),
                  proforms TEXT,
                  cancellation_request VARCHAR(255),
                  has_editable_products INT,
                  late_shipment INT,
                  emag_club INT,
                  weekend_delivery INT,
                  PRIMARY KEY (id, vendor_id),
                  FOREIGN KEY (details_id) REFERENCES locker_details(locker_id),
                  FOREIGN KEY (customer_id) REFERENCES customer(id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createCustomerTable(Connection db) throws SQLException {
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
    }

    private static void createLockerDetailTable(Connection db) throws SQLException {
        try (var s = db.prepareStatement("""
                CREATE TABLE locker_details(
                  locker_id VARCHAR(255),
                  locker_name VARCHAR(255),
                  locker_delivery_eligible INTEGER,
                  courier_external_office_id VARCHAR(255),
                  PRIMARY KEY (locker_id)
                );
                """)) {
            s.execute();
        }
    }

    private static void createVendorTable(Connection db) throws SQLException {
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
    }
}