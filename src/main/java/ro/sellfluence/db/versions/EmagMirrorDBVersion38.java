package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion38 {
    static void version38(Connection db) throws SQLException {
        executeStatement(db, """
                CREATE INDEX ads_campaign_campaign_id_report_date_idx
                    ON ads_campaign (campaign_id, report_date);
                """);
        executeStatement(db, """
                CREATE INDEX ads_adset_campaign_id_report_date_idx
                    ON ads_adset (campaign_id, report_date, adset_id);
                """);
        executeStatement(db, """
                CREATE INDEX ads_search_phrase_campaign_adset_report_date_idx
                    ON ads_search_phrase (campaign_id, adset_id, report_date);
                """);
        executeStatement(db, """
                CREATE INDEX ads_targeted_product_campaign_adset_report_date_idx
                    ON ads_targeted_product (campaign_id, adset_id, report_date);
                """);
        executeStatement(db, """
                CREATE INDEX ads_keyword_campaign_adset_report_date_idx
                    ON ads_keyword (campaign_id, adset_id, report_date);
                """);
    }
}
