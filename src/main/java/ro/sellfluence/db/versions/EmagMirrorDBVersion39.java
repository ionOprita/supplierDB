package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion39 {
    private static final List<String> ADS_TABLES = List.of(
            "ads_campaign", "ads_adset", "ads_keyword", "ads_search_phrase", "ads_targeted_product"
    );

    static void version39(Connection db) throws SQLException {
        for (String table : ADS_TABLES) {
            executeStatement(db, "ALTER TABLE " + table + " ADD COLUMN vendor_id UUID");
        }

        if (hasExistingAds(db)) {
            UUID vendorId = requireSellfusionVendor(db);
            for (String table : ADS_TABLES) {
                try (var statement = db.prepareStatement("UPDATE " + table + " SET vendor_id = ?")) {
                    statement.setObject(1, vendorId);
                    statement.executeUpdate();
                }
            }
        }

        for (String table : ADS_TABLES) {
            executeStatement(db, "ALTER TABLE " + table + " ALTER COLUMN vendor_id SET NOT NULL");
            executeStatement(db, "ALTER TABLE " + table + " ADD CONSTRAINT " + table
                    + "_vendor_fkey FOREIGN KEY (vendor_id) REFERENCES vendor (id)");
        }

        // Remove dependent foreign keys before replacing the campaign and adset primary keys.
        executeStatement(db, "ALTER TABLE ads_adset DROP CONSTRAINT ads_adset_campaign_fkey");
        for (String table : List.of("ads_keyword", "ads_search_phrase", "ads_targeted_product")) {
            executeStatement(db, "ALTER TABLE " + table + " DROP CONSTRAINT " + table + "_adset_fkey");
        }

        replacePrimaryKey(db, "ads_campaign", "vendor_id, report_date, campaign_id");
        replacePrimaryKey(db, "ads_adset", "vendor_id, report_date, campaign_id, adset_id");
        replacePrimaryKey(db, "ads_keyword", "vendor_id, report_date, campaign_id, adset_id, keyword_id");
        replacePrimaryKey(db, "ads_targeted_product", "vendor_id, report_date, campaign_id, adset_id, doc_id");
        executeStatement(db, """
                ALTER TABLE ads_search_phrase
                    DROP CONSTRAINT ads_search_phrase_unique,
                    ADD CONSTRAINT ads_search_phrase_unique UNIQUE (
                        vendor_id, report_date, campaign_id, adset_id, is_aggregated, search_phrase_hash
                    )
                """);

        executeStatement(db, """
                ALTER TABLE ads_adset
                    ADD CONSTRAINT ads_adset_campaign_fkey
                    FOREIGN KEY (vendor_id, report_date, campaign_id)
                    REFERENCES ads_campaign (vendor_id, report_date, campaign_id)
                    ON DELETE CASCADE
                """);
        for (String table : List.of("ads_keyword", "ads_search_phrase", "ads_targeted_product")) {
            executeStatement(db, "ALTER TABLE " + table + " ADD CONSTRAINT " + table + "_adset_fkey"
                    + " FOREIGN KEY (vendor_id, report_date, campaign_id, adset_id)"
                    + " REFERENCES ads_adset (vendor_id, report_date, campaign_id, adset_id) ON DELETE CASCADE");
        }

        replaceIndex(db, "ads_campaign_campaign_id_idx", "ads_campaign", "vendor_id, campaign_id");
        replaceIndex(db, "ads_adset_adset_id_idx", "ads_adset", "vendor_id, adset_id");
        replaceIndex(db, "ads_campaign_campaign_id_report_date_idx", "ads_campaign",
                "vendor_id, campaign_id, report_date");
        replaceIndex(db, "ads_adset_campaign_id_report_date_idx", "ads_adset",
                "vendor_id, campaign_id, report_date, adset_id");
        for (String table : List.of("ads_keyword", "ads_search_phrase", "ads_targeted_product")) {
            replaceIndex(db, table + "_campaign_adset_report_date_idx", table,
                    "vendor_id, campaign_id, adset_id, report_date");
        }
    }

    private static boolean hasExistingAds(Connection db) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM ads_campaign)
                    OR EXISTS (SELECT 1 FROM ads_adset)
                    OR EXISTS (SELECT 1 FROM ads_keyword)
                    OR EXISTS (SELECT 1 FROM ads_search_phrase)
                    OR EXISTS (SELECT 1 FROM ads_targeted_product)
                """); var result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static UUID requireSellfusionVendor(Connection db) throws SQLException {
        try (var statement = db.prepareStatement("SELECT id FROM vendor WHERE account = ?")) {
            statement.setString(1, "sellfusion");
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Cannot migrate existing Ads data: no vendor has account 'sellfusion'.");
                }
                UUID vendorId = result.getObject(1, UUID.class);
                if (result.next()) {
                    throw new SQLException("Cannot migrate existing Ads data: multiple vendors have account 'sellfusion'.");
                }
                return vendorId;
            }
        }
    }

    private static void replacePrimaryKey(Connection db, String table, String columns) throws SQLException {
        executeStatement(db, "ALTER TABLE " + table + " DROP CONSTRAINT " + table + "_pkey,"
                + " ADD PRIMARY KEY (" + columns + ")");
    }

    private static void replaceIndex(Connection db, String name, String table, String columns) throws SQLException {
        executeStatement(db, "DROP INDEX " + name);
        executeStatement(db, "CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
    }
}
