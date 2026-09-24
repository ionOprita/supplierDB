package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

/** Daily complete offer snapshots, including the records nested in each offer. */
class EmagMirrorDBVersion41 {
    static void version41(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE offers_snapshot (
                    vendor_id UUID NOT NULL REFERENCES vendor (id),
                    fetch_date DATE NOT NULL,
                    total_number_of_items INTEGER NOT NULL CHECK (total_number_of_items >= 0),
                    PRIMARY KEY (vendor_id, fetch_date)
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_offer (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    is_unsafe_for_site BOOLEAN,
                    seller_id INTEGER,
                    seller_name TEXT,
                    eans TEXT[],
                    category_doc_id INTEGER,
                    category_id INTEGER,
                    ext_id TEXT,
                    valid INTEGER,
                    inactivation_criticalities TEXT[],
                    ext_stock INTEGER,
                    stock2p INTEGER,
                    stock3p INTEGER,
                    ext_sale_price NUMERIC,
                    ext_original_sale_price NUMERIC,
                    invalidation_reason TEXT,
                    ext_status INTEGER,
                    ext_handling_time INTEGER,
                    fullfilled_by_emag BOOLEAN,
                    ext_url TEXT,
                    ext_part_number TEXT,
                    brand_name TEXT,
                    mkt_id TEXT,
                    type INTEGER,
                    offer_properties TEXT[],
                    min_price NUMERIC,
                    max_price NUMERIC,
                    ext_warranty INTEGER,
                    offer_details_unfair_price NUMERIC,
                    offer_details_locker_eligibility BOOLEAN,
                    offer_details_supply_lead_time INTEGER,
                    offer_details_emag_club INTEGER,
                    offer_details_emag_club_type INTEGER,
                    offer_details_emag_club_merged INTEGER,
                    green_tax NUMERIC,
                    product_performance_suggested_price NUMERIC,
                    product_performance_buy_button_rank INTEGER,
                    product_performance_needed_stock_next7 INTEGER,
                    product_performance_needed_stock_next30 INTEGER,
                    product_performance_depletion_days INTEGER,
                    product_performance_multioffer_offers_count INTEGER,
                    product_performance_order_value2 NUMERIC,
                    rrp NUMERIC,
                    rrp_emag NUMERIC,
                    rrp_emag_type INTEGER,
                    rrp_seller NUMERIC,
                    rrp_type INTEGER,
                    rrp_badge INTEGER,
                    is_stock_supply_needed BOOLEAN,
                    platform_id INTEGER,
                    currency TEXT,
                    doc_product_part_number_key TEXT,
                    doc_product_part_number TEXT,
                    doc_product_name TEXT,
                    doc_product_id INTEGER,
                    doc_product_brand_name TEXT,
                    doc_product_brand_mkt_id INTEGER,
                    product_performance_multioffer_best_price NUMERIC,
                    product_performance_multioffer_no_of_reviews INTEGER,
                    product_performance_multioffer_review_score NUMERIC,
                    recycle_warranties_quantity INTEGER,
                    product_performance_lost_order_value NUMERIC,
                    product_performance_days_without_stock INTEGER,
                    offers_out_of_stock_day TIMESTAMP,
                    product_performance_order_value1 NUMERIC,
                    invalidation_reason_date TIMESTAMP,
                    invalidation_reason_name TEXT,
                    invalidation_sub_reason TEXT,
                    invalidation_sub_reason_name TEXT,
                    has_supply_recommendation BOOLEAN,
                    measurements_present BOOLEAN NOT NULL,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id),
                    FOREIGN KEY (vendor_id, fetch_date)
                        REFERENCES offers_snapshot (vendor_id, fetch_date) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_stock (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    category TEXT,
                    status_type TEXT,
                    status_name TEXT,
                    inactivation_reason TEXT,
                    hotness_type TEXT,
                    hotness_name TEXT,
                    rrp_badge_color TEXT,
                    rrp_guideline TEXT,
                    rrp_value NUMERIC,
                    vat_id INTEGER,
                    start_date BOOLEAN,
                    property_types TEXT[],
                    place_order_to_supplier_until DATE,
                    invalidation_reason_label TEXT,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id),
                    FOREIGN KEY (vendor_id, fetch_date, offer_id)
                        REFERENCES offers_offer (vendor_id, fetch_date, offer_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_price (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    is_ean_mandatory BOOLEAN,
                    is_emag_club_eligible BOOLEAN,
                    status_details TEXT,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id),
                    FOREIGN KEY (vendor_id, fetch_date, offer_id)
                        REFERENCES offers_offer (vendor_id, fetch_date, offer_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_links (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    edit TEXT,
                    seller TEXT,
                    details TEXT,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id),
                    FOREIGN KEY (vendor_id, fetch_date, offer_id)
                        REFERENCES offers_offer (vendor_id, fetch_date, offer_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_supply_recommendation (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    recommended_qty_replenish INTEGER,
                    replenishment_period INTEGER,
                    days_until_oos INTEGER,
                    estimated_days_until_oos INTEGER,
                    days_in_stock_l35 INTEGER,
                    units_sold_l35 INTEGER,
                    units_forecast_n35 INTEGER,
                    units_in_transit INTEGER,
                    stock_in_current_day INTEGER,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id),
                    FOREIGN KEY (vendor_id, fetch_date, offer_id)
                        REFERENCES offers_offer (vendor_id, fetch_date, offer_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE TABLE offers_measurement (
                    vendor_id UUID NOT NULL,
                    fetch_date DATE NOT NULL,
                    offer_id TEXT NOT NULL,
                    position INTEGER NOT NULL CHECK (position >= 0),
                    weight NUMERIC,
                    height NUMERIC,
                    length NUMERIC,
                    width NUMERIC,
                    PRIMARY KEY (vendor_id, fetch_date, offer_id, position),
                    FOREIGN KEY (vendor_id, fetch_date, offer_id)
                        REFERENCES offers_offer (vendor_id, fetch_date, offer_id) ON DELETE CASCADE
                )
                """);
        executeStatement(db, """
                CREATE INDEX offers_offer_vendor_offer_date_idx
                    ON offers_offer (vendor_id, offer_id, fetch_date)
                """);
    }
}
