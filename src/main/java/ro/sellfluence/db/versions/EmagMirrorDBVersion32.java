package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion32 {
    static void version32(Connection db) throws SQLException {
        addProductDetailsColumns(db);
        addTransportColumns(db);
        addEmagListingColumns(db);
        addCategoryColumns(db);
        addMessageAndReviewColumns(db);
    }

    private static void addProductDetailsColumns(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                    ADD COLUMN model TEXT,
                    ADD COLUMN product_length_mm NUMERIC,
                    ADD COLUMN product_width_mm NUMERIC,
                    ADD COLUMN product_height_mm NUMERIC,
                    ADD COLUMN product_weight_g NUMERIC,
                    ADD COLUMN ean TEXT,
                    ADD COLUMN brand TEXT,
                    ADD COLUMN warranty_months INTEGER,
                    ADD COLUMN import_tax NUMERIC,
                    ADD COLUMN supplier_product_code TEXT;
                """);
    }

    private static void addTransportColumns(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                    ADD COLUMN air_transport_pcs_per_carton NUMERIC,
                    ADD COLUMN air_transport_kg_per_carton NUMERIC,
                    ADD COLUMN air_transport_length_cm_per_carton NUMERIC,
                    ADD COLUMN air_transport_width_cm_per_carton NUMERIC,
                    ADD COLUMN air_transport_height_cm_per_carton NUMERIC,
                    ADD COLUMN air_transport_volume_m3_per_carton NUMERIC,
                    ADD COLUMN rail_transport_pcs_per_carton NUMERIC,
                    ADD COLUMN rail_transport_kg_per_carton NUMERIC,
                    ADD COLUMN rail_transport_length_cm_per_carton NUMERIC,
                    ADD COLUMN rail_transport_width_cm_per_carton NUMERIC,
                    ADD COLUMN rail_transport_height_cm_per_carton NUMERIC,
                    ADD COLUMN rail_transport_volume_m3_per_carton NUMERIC,
                    ADD COLUMN sea_transport_pcs_per_carton NUMERIC,
                    ADD COLUMN sea_transport_kg_per_carton NUMERIC,
                    ADD COLUMN sea_transport_length_cm_per_carton NUMERIC,
                    ADD COLUMN sea_transport_width_cm_per_carton NUMERIC,
                    ADD COLUMN sea_transport_height_cm_per_carton NUMERIC,
                    ADD COLUMN sea_transport_volume_m3_per_carton NUMERIC,
                    ADD COLUMN truck_transport_pcs_per_carton NUMERIC,
                    ADD COLUMN truck_transport_kg_per_carton NUMERIC,
                    ADD COLUMN truck_transport_length_cm_per_carton NUMERIC,
                    ADD COLUMN truck_transport_width_cm_per_carton NUMERIC,
                    ADD COLUMN truck_transport_height_cm_per_carton NUMERIC,
                    ADD COLUMN truck_transport_volume_m3_per_carton NUMERIC;
                """);
    }

    private static void addEmagListingColumns(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                    ADD COLUMN emag_link TEXT,
                    ADD COLUMN emag_title TEXT,
                    ADD COLUMN income_profit_tax TEXT,
                    ADD COLUMN vat_payer BOOLEAN,
                    ADD COLUMN emag_sale_price_ron NUMERIC,
                    ADD COLUMN emag_commission NUMERIC,
                    ADD COLUMN offer_id_concept BIGINT,
                    ADD COLUMN offer_id_solutions BIGINT,
                    ADD COLUMN offer_id_solutions_fbe BIGINT,
                    ADD COLUMN offer_id_judios_concept BIGINT,
                    ADD COLUMN offer_id_judios_concept_fbe BIGINT,
                    ADD COLUMN offer_id_judy_creative_studios_fbe BIGINT,
                    ADD COLUMN offer_id_sellfusion BIGINT,
                    ADD COLUMN offer_id_sellfusion_fbe BIGINT,
                    ADD COLUMN offer_id_koppel BIGINT,
                    ADD COLUMN offer_id_koppel_fbe BIGINT;
                """);
    }

    private static void addCategoryColumns(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                    ADD COLUMN index_category TEXT,
                    ADD COLUMN division TEXT,
                    ADD COLUMN supracategory TEXT,
                    ADD COLUMN category_name TEXT,
                    ADD COLUMN subcategory TEXT,
                    ADD COLUMN subsubcategory TEXT,
                    ADD COLUMN supracategory_country TEXT,
                    ADD COLUMN category_country TEXT,
                    ADD COLUMN category_id INTEGER,
                    ADD COLUMN scm_id INTEGER,
                    ADD COLUMN doc_id INTEGER,
                    ADD COLUMN indexed_subcategory_country TEXT,
                    ADD COLUMN big_category TEXT,
                    ADD COLUMN emag_ads_auto_id BIGINT,
                    ADD COLUMN emag_ads_manual_id BIGINT;
                """);
    }

    private static void addMessageAndReviewColumns(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE product
                    ADD COLUMN gender TEXT,
                    ADD COLUMN manual_video_link TEXT,
                    ADD COLUMN usage_guide_link TEXT,
                    ADD COLUMN usage_site_link TEXT,
                    ADD COLUMN usage_manual_link TEXT,
                    ADD COLUMN other_comments TEXT,
                    ADD COLUMN review_caller TEXT,
                    ADD COLUMN report_link TEXT;
                """);
    }
}
