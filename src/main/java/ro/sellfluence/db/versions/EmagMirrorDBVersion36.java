package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion36 {
    static void version36(Connection db) throws SQLException {
        createAdsCampaignTable(db);
        createAdsAdsetTable(db);
        createAdsSearchPhraseTable(db);
        createAdsTargetedProductTable(db);
    }

    private static void createAdsCampaignTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE ads_campaign (
                    report_start_date DATE NOT NULL,
                    report_end_date DATE NOT NULL,
                    campaign_id INTEGER NOT NULL,
                    name TEXT,
                    advertiser_id INTEGER,
                    daily_budget NUMERIC(19, 4),
                    effective_daily_budget NUMERIC(19, 4),
                    remaining_daily_budget NUMERIC(19, 4),
                    status TEXT,
                    inherited_status TEXT,
                    targeting TEXT,
                    date_start TIMESTAMP,
                    date_end TIMESTAMP,
                    advertiser_name TEXT,
                    summary_average_cost_of_sale NUMERIC(19, 4),
                    summary_clicks INTEGER,
                    summary_ctr NUMERIC(19, 4),
                    summary_effective_cpc NUMERIC(19, 4),
                    summary_impressions INTEGER,
                    summary_sales NUMERIC(19, 4),
                    summary_sales_count INTEGER,
                    summary_sold_units INTEGER,
                    summary_spent NUMERIC(19, 4),
                    summary_active_offer_count INTEGER,
                    summary_offer_count INTEGER,
                    summary_paused_offer_count INTEGER,
                    summary_adset_count INTEGER,
                    summary_keyword_count INTEGER,
                    summary_product_target_count INTEGER,
                    summary_conversion_rate NUMERIC(19, 4),
                    summary_return_on_advertising_spend NUMERIC(19, 4),
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp,
                    CONSTRAINT ads_campaign_report_range CHECK (report_end_date >= report_start_date),
                    PRIMARY KEY (report_start_date, report_end_date, campaign_id)
                );
                """);
        executeStatement(db, """
                CREATE INDEX ads_campaign_campaign_id_idx
                    ON ads_campaign (campaign_id);
                """);
    }

    private static void createAdsAdsetTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE ads_adset (
                    report_start_date DATE NOT NULL,
                    report_end_date DATE NOT NULL,
                    campaign_id INTEGER NOT NULL,
                    adset_id INTEGER NOT NULL,
                    name TEXT,
                    targeting TEXT,
                    bid NUMERIC(19, 4),
                    status TEXT,
                    inherited_status TEXT,
                    recommended_bid NUMERIC(19, 4),
                    summary_average_cost_of_sale NUMERIC(19, 4),
                    summary_clicks INTEGER,
                    summary_ctr NUMERIC(19, 4),
                    summary_effective_cpc NUMERIC(19, 4),
                    summary_impressions INTEGER,
                    summary_sales NUMERIC(19, 4),
                    summary_sales_count INTEGER,
                    summary_sold_units INTEGER,
                    summary_spent NUMERIC(19, 4),
                    summary_active_offer_count INTEGER,
                    summary_offer_count INTEGER,
                    summary_paused_offer_count INTEGER,
                    summary_adset_count INTEGER,
                    summary_keyword_count INTEGER,
                    summary_product_target_count INTEGER,
                    summary_conversion_rate NUMERIC(19, 4),
                    summary_return_on_advertising_spend NUMERIC(19, 4),
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp,
                    PRIMARY KEY (report_start_date, report_end_date, campaign_id, adset_id),
                    CONSTRAINT ads_adset_campaign_fkey FOREIGN KEY (report_start_date, report_end_date, campaign_id)
                        REFERENCES ads_campaign (report_start_date, report_end_date, campaign_id)
                        ON DELETE CASCADE
                );
                """);
        executeStatement(db, """
                CREATE INDEX ads_adset_adset_id_idx
                    ON ads_adset (adset_id);
                """);
    }

    private static void createAdsSearchPhraseTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE ads_search_phrase (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    report_start_date DATE NOT NULL,
                    report_end_date DATE NOT NULL,
                    campaign_id INTEGER NOT NULL,
                    adset_id INTEGER NOT NULL,
                    search_phrase TEXT NOT NULL,
                    search_phrase_hash CHAR(64) NOT NULL,
                    is_aggregated BOOLEAN NOT NULL,
                    summary_average_cost_of_sale NUMERIC(19, 4),
                    summary_clicks INTEGER,
                    summary_ctr NUMERIC(19, 4),
                    summary_effective_cpc NUMERIC(19, 4),
                    summary_impressions INTEGER,
                    summary_sales NUMERIC(19, 4),
                    summary_sales_count INTEGER,
                    summary_sold_units INTEGER,
                    summary_spent NUMERIC(19, 4),
                    summary_active_offer_count INTEGER,
                    summary_offer_count INTEGER,
                    summary_paused_offer_count INTEGER,
                    summary_adset_count INTEGER,
                    summary_keyword_count INTEGER,
                    summary_product_target_count INTEGER,
                    summary_conversion_rate NUMERIC(19, 4),
                    summary_return_on_advertising_spend NUMERIC(19, 4),
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp,
                    CONSTRAINT ads_search_phrase_adset_fkey FOREIGN KEY (report_start_date, report_end_date, campaign_id, adset_id)
                        REFERENCES ads_adset (report_start_date, report_end_date, campaign_id, adset_id)
                        ON DELETE CASCADE
                );
                """);
        executeStatement(db, """
                ALTER TABLE ads_search_phrase
                    ADD CONSTRAINT ads_search_phrase_unique UNIQUE (
                        report_start_date,
                        report_end_date,
                        campaign_id,
                        adset_id,
                        is_aggregated,
                        search_phrase_hash
                    );
                """);
    }

    private static void createAdsTargetedProductTable(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE ads_targeted_product (
                    report_start_date DATE NOT NULL,
                    report_end_date DATE NOT NULL,
                    campaign_id INTEGER NOT NULL,
                    adset_id INTEGER NOT NULL,
                    doc_id INTEGER NOT NULL,
                    product_name TEXT,
                    price NUMERIC(19, 4),
                    rating NUMERIC(19, 4),
                    pnk TEXT,
                    category_name TEXT,
                    category_id INTEGER,
                    brand_name TEXT,
                    brand_id INTEGER,
                    clicks INTEGER,
                    impressions INTEGER,
                    ctr NUMERIC(19, 4),
                    effective_cpc NUMERIC(19, 4),
                    spent NUMERIC(19, 4),
                    average_cost_of_sale NUMERIC(19, 4),
                    sales NUMERIC(19, 4),
                    sold_units INTEGER,
                    sales_count INTEGER,
                    image_url TEXT,
                    return_on_advertising_spend NUMERIC(19, 4),
                    conversion_rate NUMERIC(19, 4),
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT current_timestamp,
                    PRIMARY KEY (report_start_date, report_end_date, campaign_id, adset_id, doc_id),
                    CONSTRAINT ads_targeted_product_adset_fkey FOREIGN KEY (report_start_date, report_end_date, campaign_id, adset_id)
                        REFERENCES ads_adset (report_start_date, report_end_date, campaign_id, adset_id)
                        ON DELETE CASCADE
                );
                """);
        executeStatement(db, """
                CREATE INDEX ads_targeted_product_pnk_idx
                    ON ads_targeted_product (pnk)
                    WHERE pnk IS NOT NULL;
                """);
        executeStatement(db, """
                CREATE INDEX ads_targeted_product_brand_id_idx
                    ON ads_targeted_product (brand_id)
                    WHERE brand_id IS NOT NULL;
                """);
        executeStatement(db, """
                CREATE INDEX ads_targeted_product_category_id_idx
                    ON ads_targeted_product (category_id)
                    WHERE category_id IS NOT NULL;
                """);
    }
}
