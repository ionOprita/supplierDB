package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion37 {
    static void version37(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE TABLE ads_keyword (
                    report_date DATE NOT NULL,
                    campaign_id INTEGER NOT NULL,
                    adset_id INTEGER NOT NULL,
                    keyword_id INTEGER NOT NULL,
                    bid NUMERIC(19, 4),
                    status TEXT,
                    keyword TEXT NOT NULL,
                    match_type TEXT,
                    inherited_status TEXT,
                    inherited_bid NUMERIC(19, 4),
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
                    PRIMARY KEY (report_date, campaign_id, adset_id, keyword_id),
                    CONSTRAINT ads_keyword_adset_fkey FOREIGN KEY (report_date, campaign_id, adset_id)
                        REFERENCES ads_adset (report_date, campaign_id, adset_id)
                        ON DELETE CASCADE
                );
                """);
    }
}
