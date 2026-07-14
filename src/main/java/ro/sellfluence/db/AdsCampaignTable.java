package ro.sellfluence.db;

import ro.sellfluence.emagapi.AdSet;
import ro.sellfluence.emagapi.AdsAdset;
import ro.sellfluence.emagapi.AdsCampaign;
import ro.sellfluence.emagapi.AdsPerformanceSummary;
import ro.sellfluence.emagapi.AdsRecommendedBid;
import ro.sellfluence.emagapi.AdsSearchPhrase;
import ro.sellfluence.emagapi.AdsTargetedProduct;
import ro.sellfluence.emagapi.Campaign;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class AdsCampaignTable {

    public record AdsCampaignColumn(String key, String label, boolean numeric) {
    }

    public record AdsCampaignRow(int campaignId, Map<String, String> values) {
    }

    public record AdsCampaignTableData(List<AdsCampaignColumn> columns, List<AdsCampaignRow> rows) {
    }

    public record AdsAdsetColumn(String key, String label, boolean numeric) {
    }

    public record AdsAdsetRow(int campaignId, int adsetId, Map<String, String> values) {
    }

    public record AdsAdsetTableData(String campaignName, List<AdsAdsetColumn> columns, List<AdsAdsetRow> rows) {
    }

    public record AdsSearchPhraseColumn(String key, String label, boolean numeric) {
    }

    public record AdsSearchPhraseRow(Map<String, String> values) {
    }

    public record AdsSearchPhraseTableData(String campaignName,
                                           String adsetName,
                                           String reportDate,
                                           List<AdsSearchPhraseColumn> columns,
                                           List<AdsSearchPhraseRow> rows) {
    }

    public record AdsTargetedProductColumn(String key, String label, boolean numeric) {
    }

    public record AdsTargetedProductRow(Map<String, String> values) {
    }

    public record AdsTargetedProductTableData(String campaignName,
                                              String adsetName,
                                              String reportDate,
                                              List<AdsTargetedProductColumn> columns,
                                              List<AdsTargetedProductRow> rows) {
    }

    private record AdsNames(String campaignName, String adsetName) {
    }

    private static final List<AdsCampaignColumn> ADS_CAMPAIGN_COLUMNS = List.of(
            column("name", "Name", false),
            column("campaign_id", "Campaign ID", true),
            column("advertiser_id", "Advertiser ID", true),
            column("daily_budget", "Daily budget", true),
            column("effective_daily_budget", "Effective daily budget", true),
            column("remaining_daily_budget", "Remaining daily budget", true),
            column("status", "Status", false),
            column("inherited_status", "Inherited status", false),
            column("targeting", "Targeting", false),
            column("date_start", "Date start", false),
            column("date_end", "Date end", false),
            column("advertiser_name", "Advertiser name", false),
            column("summary_average_cost_of_sale", "Average cost of sale", true),
            column("summary_clicks", "Clicks", true),
            column("summary_ctr", "CTR", true),
            column("summary_effective_cpc", "Effective CPC", true),
            column("summary_impressions", "Impressions", true),
            column("summary_sales", "Sales", true),
            column("summary_sales_count", "Sales count", true),
            column("summary_sold_units", "Sold units", true),
            column("summary_spent", "Spent", true),
            column("summary_active_offer_count", "Active offer count", true),
            column("summary_offer_count", "Offer count", true),
            column("summary_paused_offer_count", "Paused offer count", true),
            column("summary_adset_count", "Adset count", true),
            column("summary_keyword_count", "Keyword count", true),
            column("summary_product_target_count", "Product target count", true),
            column("summary_conversion_rate", "Conversion rate", true),
            column("summary_return_on_advertising_spend", "Return on advertising spend", true),
            column("last_seen_at", "Last seen at", false)
    );

    private static final List<AdsAdsetColumn> ADS_ADSET_COLUMNS = List.of(
            adsetColumn("name", "Name", false),
            adsetColumn("targeting", "Targeting", false),
            adsetColumn("bid", "Bid", true),
            adsetColumn("status", "Status", false),
            adsetColumn("inherited_status", "Inherited status", false),
            adsetColumn("recommended_bid", "Recommended bid", true),
            adsetColumn("summary_average_cost_of_sale", "Summary average cost of sale", true),
            adsetColumn("summary_clicks", "Clicks", true),
            adsetColumn("summary_ctr", "CTR", true),
            adsetColumn("summary_effective_cpc", "Effective CPC", true),
            adsetColumn("summary_impressions", "Impressions", true),
            adsetColumn("summary_sales", "Sales", true),
            adsetColumn("summary_sales_count", "Sales count", true),
            adsetColumn("summary_sold_units", "Sold units", true),
            adsetColumn("summary_spent", "Spent", true),
            adsetColumn("summary_active_offer_count", "Active offer count", true),
            adsetColumn("summary_offer_count", "Offer count", true),
            adsetColumn("summary_paused_offer_count", "Paused offer count", true),
            adsetColumn("summary_adset_count", "Adset count", true),
            adsetColumn("summary_keyword_count", "Keyword count", true),
            adsetColumn("summary_product_target_count", "Product target count", true),
            adsetColumn("summary_conversion_rate", "Conversion rate", true),
            adsetColumn("summary_return_on_advertising_spend", "Return on advertising spend", true),
            adsetColumn("last_seen_at", "Last seen at", false)
    );

    private static final List<AdsSearchPhraseColumn> ADS_SEARCH_PHRASE_COLUMNS = List.of(
            searchPhraseColumn("search_phrase", "Search phrase", false),
            searchPhraseColumn("is_aggregated", "Is aggregated", false),
            searchPhraseColumn("summary_average_cost_of_sale", "Summary average cost of sale", true),
            searchPhraseColumn("summary_clicks", "Clicks", true),
            searchPhraseColumn("summary_ctr", "Summary CTR", true),
            searchPhraseColumn("summary_effective_cpc", "Effective CPC", true),
            searchPhraseColumn("summary_impressions", "Impressions", true),
            searchPhraseColumn("summary_sales", "Sales", true),
            searchPhraseColumn("summary_sales_count", "Sales count", true),
            searchPhraseColumn("summary_sold_units", "Sold units", true),
            searchPhraseColumn("summary_spent", "Spent", true),
            searchPhraseColumn("summary_active_offer_count", "Active offer count", true),
            searchPhraseColumn("summary_offer_count", "Offer count", true),
            searchPhraseColumn("summary_paused_offer_count", "Paused offer count", true),
            searchPhraseColumn("summary_adset_count", "Adset count", true),
            searchPhraseColumn("summary_keyword_count", "Keyword count", true),
            searchPhraseColumn("summary_product_target_count", "Product target count", true),
            searchPhraseColumn("summary_conversion_rate", "Conversion rate", true),
            searchPhraseColumn("summary_return_on_advertising_spend", "Return on advertising spend", true),
            searchPhraseColumn("last_seen_at", "Last seen at", false)
    );

    private static final List<AdsTargetedProductColumn> ADS_TARGETED_PRODUCT_COLUMNS = List.of(
            targetedProductColumn("product_name", "Product name", false),
            targetedProductColumn("price", "Price", true),
            targetedProductColumn("rating", "Rating", true),
            targetedProductColumn("pnk", "PNK", false),
            targetedProductColumn("category_name", "Category name", false),
            targetedProductColumn("brand_name", "Brand name", false),
            targetedProductColumn("clicks", "Clicks", true),
            targetedProductColumn("impressions", "Impressions", true),
            targetedProductColumn("ctr", "CTR", true),
            targetedProductColumn("effective_cpc", "Effective CPC", true),
            targetedProductColumn("spent", "Spent", true),
            targetedProductColumn("average_cost_of_sale", "Average cost of sale", true),
            targetedProductColumn("sales", "Sales", true),
            targetedProductColumn("sold_units", "Sold units", true),
            targetedProductColumn("sales_count", "Sales count", true),
            targetedProductColumn("image_url", "Image URL", false),
            targetedProductColumn("return_on_advertising_spend", "Return on advertising spend", true),
            targetedProductColumn("conversion_rate", "Conversion rate", true),
            targetedProductColumn("last_seen_at", "Last seen at", false)
    );

    static List<LocalDate> getCampaignReportDates(Connection db) throws SQLException {
        Objects.requireNonNull(db);

        var result = new ArrayList<LocalDate>();
        try (var s = db.prepareStatement("""
                SELECT DISTINCT report_date
                FROM ads_campaign
                ORDER BY report_date DESC
                """)) {
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getDate("report_date").toLocalDate());
                }
            }
        }
        return result;
    }

    static AdsCampaignTableData getCampaignsByReportDate(Connection db, LocalDate reportDate) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(reportDate);

        var rows = new ArrayList<AdsCampaignRow>();
        try (var s = db.prepareStatement("""
                SELECT
                    name,
                    campaign_id,
                    advertiser_id,
                    daily_budget,
                    effective_daily_budget,
                    remaining_daily_budget,
                    status,
                    inherited_status,
                    targeting,
                    date_start,
                    date_end,
                    advertiser_name,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend,
                    last_seen_at
                FROM ads_campaign
                WHERE report_date = ?
                ORDER BY lower(name) NULLS LAST, name NULLS LAST, campaign_id
                """)) {
            s.setDate(1, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(readCampaignRow(rs));
                }
            }
        }
        return new AdsCampaignTableData(ADS_CAMPAIGN_COLUMNS, rows);
    }

    static List<LocalDate> getAdsetReportDates(Connection db, int campaignId) throws SQLException {
        Objects.requireNonNull(db);

        var result = new ArrayList<LocalDate>();
        try (var s = db.prepareStatement("""
                SELECT DISTINCT report_date
                FROM ads_campaign
                WHERE campaign_id = ?
                ORDER BY report_date DESC
                """)) {
            s.setInt(1, campaignId);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getDate("report_date").toLocalDate());
                }
            }
        }
        return result;
    }

    static AdsAdsetTableData getAdsetsByReportDate(Connection db, int campaignId, LocalDate reportDate) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(reportDate);

        var campaignName = getCampaignName(db, campaignId, reportDate);
        var rows = new ArrayList<AdsAdsetRow>();
        try (var s = db.prepareStatement("""
                SELECT
                    name,
                    campaign_id,
                    adset_id,
                    targeting,
                    bid,
                    status,
                    inherited_status,
                    recommended_bid,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend,
                    last_seen_at
                FROM ads_adset
                WHERE campaign_id = ?
                  AND report_date = ?
                ORDER BY lower(name) NULLS LAST, name NULLS LAST, adset_id
                """)) {
            s.setInt(1, campaignId);
            s.setDate(2, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(readAdsetRow(rs));
                }
            }
        }
        return new AdsAdsetTableData(campaignName, ADS_ADSET_COLUMNS, rows);
    }

    static AdsSearchPhraseTableData getSearchPhrases(Connection db,
                                                     int campaignId,
                                                     int adsetId,
                                                     LocalDate reportDate) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(reportDate);

        var names = getCampaignAndAdsetNames(db, campaignId, adsetId, reportDate);
        var rows = new ArrayList<AdsSearchPhraseRow>();
        try (var s = db.prepareStatement("""
                SELECT
                    search_phrase,
                    is_aggregated,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend,
                    last_seen_at
                FROM ads_search_phrase
                WHERE campaign_id = ?
                  AND adset_id = ?
                  AND report_date = ?
                  AND (summary_clicks > 0 OR summary_impressions > 50)
                ORDER BY summary_clicks DESC NULLS LAST,
                         summary_impressions DESC NULLS LAST,
                         search_phrase ASC
                """)) {
            s.setInt(1, campaignId);
            s.setInt(2, adsetId);
            s.setDate(3, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(readSearchPhraseRow(rs));
                }
            }
        }
        return new AdsSearchPhraseTableData(
                names.campaignName(),
                names.adsetName(),
                reportDate.toString(),
                ADS_SEARCH_PHRASE_COLUMNS,
                rows
        );
    }

    static AdsTargetedProductTableData getTargetedProducts(Connection db,
                                                           int campaignId,
                                                           int adsetId,
                                                           LocalDate reportDate) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(reportDate);

        var names = getCampaignAndAdsetNames(db, campaignId, adsetId, reportDate);
        var rows = new ArrayList<AdsTargetedProductRow>();
        try (var s = db.prepareStatement("""
                SELECT
                    product_name,
                    price,
                    rating,
                    pnk,
                    category_name,
                    brand_name,
                    clicks,
                    impressions,
                    ctr,
                    effective_cpc,
                    spent,
                    average_cost_of_sale,
                    sales,
                    sold_units,
                    sales_count,
                    image_url,
                    return_on_advertising_spend,
                    conversion_rate,
                    last_seen_at
                FROM ads_targeted_product
                WHERE campaign_id = ?
                  AND adset_id = ?
                  AND report_date = ?
                  AND (clicks > 0 OR impressions > 50)
                ORDER BY clicks DESC NULLS LAST,
                         impressions DESC NULLS LAST,
                         product_name ASC NULLS LAST
                """)) {
            s.setInt(1, campaignId);
            s.setInt(2, adsetId);
            s.setDate(3, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    rows.add(readTargetedProductRow(rs));
                }
            }
        }
        return new AdsTargetedProductTableData(
                names.campaignName(),
                names.adsetName(),
                reportDate.toString(),
                ADS_TARGETED_PRODUCT_COLUMNS,
                rows
        );
    }

    static int upsertCampaigns(Connection db, List<Campaign> campaigns) throws SQLException {
        Objects.requireNonNull(db);
        Objects.requireNonNull(campaigns);

        int changedRows = 0;
        changedRows += upsertCampaignRows(db, campaigns);
        changedRows += upsertAdsetRows(db, campaigns);
        changedRows += upsertSearchPhraseRows(db, campaigns);
        changedRows += upsertTargetedProductRows(db, campaigns);
        return changedRows;
    }

    private static int upsertCampaignRows(Connection db, List<Campaign> campaigns) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO ads_campaign (
                    report_date,
                    campaign_id,
                    name,
                    advertiser_id,
                    daily_budget,
                    effective_daily_budget,
                    remaining_daily_budget,
                    status,
                    inherited_status,
                    targeting,
                    date_start,
                    date_end,
                    advertiser_name,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_date, campaign_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    advertiser_id = EXCLUDED.advertiser_id,
                    daily_budget = EXCLUDED.daily_budget,
                    effective_daily_budget = EXCLUDED.effective_daily_budget,
                    remaining_daily_budget = EXCLUDED.remaining_daily_budget,
                    status = EXCLUDED.status,
                    inherited_status = EXCLUDED.inherited_status,
                    targeting = EXCLUDED.targeting,
                    date_start = EXCLUDED.date_start,
                    date_end = EXCLUDED.date_end,
                    advertiser_name = EXCLUDED.advertiser_name,
                    summary_average_cost_of_sale = EXCLUDED.summary_average_cost_of_sale,
                    summary_clicks = EXCLUDED.summary_clicks,
                    summary_ctr = EXCLUDED.summary_ctr,
                    summary_effective_cpc = EXCLUDED.summary_effective_cpc,
                    summary_impressions = EXCLUDED.summary_impressions,
                    summary_sales = EXCLUDED.summary_sales,
                    summary_sales_count = EXCLUDED.summary_sales_count,
                    summary_sold_units = EXCLUDED.summary_sold_units,
                    summary_spent = EXCLUDED.summary_spent,
                    summary_active_offer_count = EXCLUDED.summary_active_offer_count,
                    summary_offer_count = EXCLUDED.summary_offer_count,
                    summary_paused_offer_count = EXCLUDED.summary_paused_offer_count,
                    summary_adset_count = EXCLUDED.summary_adset_count,
                    summary_keyword_count = EXCLUDED.summary_keyword_count,
                    summary_product_target_count = EXCLUDED.summary_product_target_count,
                    summary_conversion_rate = EXCLUDED.summary_conversion_rate,
                    summary_return_on_advertising_spend = EXCLUDED.summary_return_on_advertising_spend,
                    last_seen_at = current_timestamp
                """)) {
            for (var campaign : campaigns) {
                bindCampaign(s, campaign);
                s.addBatch();
            }
            return changedRows(s.executeBatch());
        }
    }

    private static int upsertAdsetRows(Connection db, List<Campaign> campaigns) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO ads_adset (
                    report_date,
                    campaign_id,
                    adset_id,
                    name,
                    targeting,
                    bid,
                    status,
                    inherited_status,
                    recommended_bid,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_date, campaign_id, adset_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    targeting = EXCLUDED.targeting,
                    bid = EXCLUDED.bid,
                    status = EXCLUDED.status,
                    inherited_status = EXCLUDED.inherited_status,
                    recommended_bid = EXCLUDED.recommended_bid,
                    summary_average_cost_of_sale = EXCLUDED.summary_average_cost_of_sale,
                    summary_clicks = EXCLUDED.summary_clicks,
                    summary_ctr = EXCLUDED.summary_ctr,
                    summary_effective_cpc = EXCLUDED.summary_effective_cpc,
                    summary_impressions = EXCLUDED.summary_impressions,
                    summary_sales = EXCLUDED.summary_sales,
                    summary_sales_count = EXCLUDED.summary_sales_count,
                    summary_sold_units = EXCLUDED.summary_sold_units,
                    summary_spent = EXCLUDED.summary_spent,
                    summary_active_offer_count = EXCLUDED.summary_active_offer_count,
                    summary_offer_count = EXCLUDED.summary_offer_count,
                    summary_paused_offer_count = EXCLUDED.summary_paused_offer_count,
                    summary_adset_count = EXCLUDED.summary_adset_count,
                    summary_keyword_count = EXCLUDED.summary_keyword_count,
                    summary_product_target_count = EXCLUDED.summary_product_target_count,
                    summary_conversion_rate = EXCLUDED.summary_conversion_rate,
                    summary_return_on_advertising_spend = EXCLUDED.summary_return_on_advertising_spend,
                    last_seen_at = current_timestamp
                """)) {
            for (var campaign : campaigns) {
                var campaignId = campaignId(campaign);
                for (var adSet : listOrEmpty(campaign.adSets())) {
                    bindAdset(s, campaign, campaignId, adSet.adSet());
                    s.addBatch();
                }
            }
            return changedRows(s.executeBatch());
        }
    }

    private static int upsertSearchPhraseRows(Connection db, List<Campaign> campaigns) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO ads_search_phrase (
                    report_date,
                    campaign_id,
                    adset_id,
                    search_phrase,
                    search_phrase_hash,
                    is_aggregated,
                    summary_average_cost_of_sale,
                    summary_clicks,
                    summary_ctr,
                    summary_effective_cpc,
                    summary_impressions,
                    summary_sales,
                    summary_sales_count,
                    summary_sold_units,
                    summary_spent,
                    summary_active_offer_count,
                    summary_offer_count,
                    summary_paused_offer_count,
                    summary_adset_count,
                    summary_keyword_count,
                    summary_product_target_count,
                    summary_conversion_rate,
                    summary_return_on_advertising_spend
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (
                    report_date,
                    campaign_id,
                    adset_id,
                    is_aggregated,
                    search_phrase_hash
                ) DO UPDATE SET
                    search_phrase = EXCLUDED.search_phrase,
                    summary_average_cost_of_sale = EXCLUDED.summary_average_cost_of_sale,
                    summary_clicks = EXCLUDED.summary_clicks,
                    summary_ctr = EXCLUDED.summary_ctr,
                    summary_effective_cpc = EXCLUDED.summary_effective_cpc,
                    summary_impressions = EXCLUDED.summary_impressions,
                    summary_sales = EXCLUDED.summary_sales,
                    summary_sales_count = EXCLUDED.summary_sales_count,
                    summary_sold_units = EXCLUDED.summary_sold_units,
                    summary_spent = EXCLUDED.summary_spent,
                    summary_active_offer_count = EXCLUDED.summary_active_offer_count,
                    summary_offer_count = EXCLUDED.summary_offer_count,
                    summary_paused_offer_count = EXCLUDED.summary_paused_offer_count,
                    summary_adset_count = EXCLUDED.summary_adset_count,
                    summary_keyword_count = EXCLUDED.summary_keyword_count,
                    summary_product_target_count = EXCLUDED.summary_product_target_count,
                    summary_conversion_rate = EXCLUDED.summary_conversion_rate,
                    summary_return_on_advertising_spend = EXCLUDED.summary_return_on_advertising_spend,
                    last_seen_at = current_timestamp
                """)) {
            for (var campaign : campaigns) {
                var campaignId = campaignId(campaign);
                for (var adSet : listOrEmpty(campaign.adSets())) {
                    var adsetId = adsetId(adSet.adSet());
                    for (var phrase : listOrEmpty(adSet.searchPrases())) {
                        bindSearchPhrase(s, campaign, campaignId, adsetId, phrase);
                        s.addBatch();
                    }
                }
            }
            return changedRows(s.executeBatch());
        }
    }

    private static int upsertTargetedProductRows(Connection db, List<Campaign> campaigns) throws SQLException {
        try (var s = db.prepareStatement("""
                INSERT INTO ads_targeted_product (
                    report_date,
                    campaign_id,
                    adset_id,
                    doc_id,
                    product_name,
                    price,
                    rating,
                    pnk,
                    category_name,
                    category_id,
                    brand_name,
                    brand_id,
                    clicks,
                    impressions,
                    ctr,
                    effective_cpc,
                    spent,
                    average_cost_of_sale,
                    sales,
                    sold_units,
                    sales_count,
                    image_url,
                    return_on_advertising_spend,
                    conversion_rate
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_date, campaign_id, adset_id, doc_id) DO UPDATE SET
                    product_name = EXCLUDED.product_name,
                    price = EXCLUDED.price,
                    rating = EXCLUDED.rating,
                    pnk = EXCLUDED.pnk,
                    category_name = EXCLUDED.category_name,
                    category_id = EXCLUDED.category_id,
                    brand_name = EXCLUDED.brand_name,
                    brand_id = EXCLUDED.brand_id,
                    clicks = EXCLUDED.clicks,
                    impressions = EXCLUDED.impressions,
                    ctr = EXCLUDED.ctr,
                    effective_cpc = EXCLUDED.effective_cpc,
                    spent = EXCLUDED.spent,
                    average_cost_of_sale = EXCLUDED.average_cost_of_sale,
                    sales = EXCLUDED.sales,
                    sold_units = EXCLUDED.sold_units,
                    sales_count = EXCLUDED.sales_count,
                    image_url = EXCLUDED.image_url,
                    return_on_advertising_spend = EXCLUDED.return_on_advertising_spend,
                    conversion_rate = EXCLUDED.conversion_rate,
                    last_seen_at = current_timestamp
                """)) {
            for (var campaign : campaigns) {
                var campaignId = campaignId(campaign);
                for (var adSet : listOrEmpty(campaign.adSets())) {
                    var adsetId = adsetId(adSet.adSet());
                    for (var targetedProduct : listOrEmpty(adSet.targetedProducts())) {
                        bindTargetedProduct(s, campaign, campaignId, adsetId, targetedProduct);
                        s.addBatch();
                    }
                }
            }
            return changedRows(s.executeBatch());
        }
    }

    private static void bindCampaign(PreparedStatement s, Campaign campaignSnapshot) throws SQLException {
        var campaign = Objects.requireNonNull(campaignSnapshot.campaign(), "campaign");
        int index = bindCampaignKey(s, 1, campaignSnapshot, campaignId(campaignSnapshot));
        s.setString(index++, campaign.name());
        setInteger(s, index++, campaign.advertiserId());
        setBigDecimal(s, index++, campaign.dailyBudget());
        setBigDecimal(s, index++, campaign.effectiveDailyBudget());
        setBigDecimal(s, index++, campaign.remainingDailyBudget());
        s.setString(index++, campaign.status());
        s.setString(index++, campaign.inheritedStatus());
        s.setString(index++, campaign.targeting());
        setTimestamp(s, index++, campaign.dateStart());
        setTimestamp(s, index++, campaign.dateEnd());
        s.setString(index++, campaign.advertiserName());
        bindSummary(s, index, campaign.summary());
    }

    private static void bindAdset(PreparedStatement s, Campaign campaign, int campaignId, AdsAdset adset) throws SQLException {
        Objects.requireNonNull(adset, "adset");
        int index = bindCampaignKey(s, 1, campaign, campaignId);
        s.setInt(index++, adsetId(adset));
        s.setString(index++, adset.name());
        s.setString(index++, adset.targeting());
        setBigDecimal(s, index++, adset.bid());
        s.setString(index++, adset.status());
        s.setString(index++, adset.inheritedStatus());
        setBigDecimal(s, index++, recommendedBid(adset.recommendedBid()));
        bindSummary(s, index, adset.summary());
    }

    private static void bindSearchPhrase(PreparedStatement s, Campaign campaign, int campaignId, int adsetId, AdsSearchPhrase phrase) throws SQLException {
        Objects.requireNonNull(phrase, "phrase");
        rejectMismatchedAdsetId(phrase.adsetId(), adsetId, "search phrase");
        int index = bindCampaignKey(s, 1, campaign, campaignId);
        s.setInt(index++, adsetId);
        var searchPhrase = Objects.requireNonNull(phrase.searchPhrase(), "searchPhrase");
        s.setString(index++, searchPhrase);
        s.setString(index++, sha256(searchPhrase));
        setBoolean(s, index++, Objects.requireNonNull(phrase.isAggregated(), "isAggregated"));
        bindSummary(s, index, phrase.summary());
    }

    private static void bindTargetedProduct(PreparedStatement s, Campaign campaign, int campaignId, int adsetId, AdsTargetedProduct product) throws SQLException {
        Objects.requireNonNull(product, "product");
        rejectMismatchedAdsetId(product.adsetId(), adsetId, "targeted product");
        int index = bindCampaignKey(s, 1, campaign, campaignId);
        s.setInt(index++, adsetId);
        s.setInt(index++, Objects.requireNonNull(product.docId(), "docId"));
        s.setString(index++, product.productName());
        setBigDecimal(s, index++, product.price());
        setBigDecimal(s, index++, product.rating());
        s.setString(index++, product.pnk());
        s.setString(index++, product.categoryName());
        setInteger(s, index++, product.categoryId());
        s.setString(index++, product.brandName());
        setInteger(s, index++, product.brandId());
        setInteger(s, index++, product.clicks());
        setInteger(s, index++, product.impressions());
        setBigDecimal(s, index++, product.ctr());
        setBigDecimal(s, index++, product.effectiveCpc());
        setBigDecimal(s, index++, product.spent());
        setBigDecimal(s, index++, product.averageCostOfSale());
        setBigDecimal(s, index++, product.sales());
        setInteger(s, index++, product.soldUnits());
        setInteger(s, index++, product.salesCount());
        s.setString(index++, product.imageUrl());
        setBigDecimal(s, index++, product.returnOnAdvertisingSpend());
        setBigDecimal(s, index, product.conversionRate());
    }

    private static int bindCampaignKey(PreparedStatement s, int index, Campaign campaign, int campaignId) throws SQLException {
        setDate(s, index++, Objects.requireNonNull(campaign.reportDate(), "reportDate"));
        s.setInt(index++, campaignId);
        return index;
    }

    private static int bindSummary(PreparedStatement s, int index, AdsPerformanceSummary summary) throws SQLException {
        setBigDecimal(s, index++, summary == null ? null : summary.averageCostOfSale());
        setInteger(s, index++, summary == null ? null : summary.clicks());
        setBigDecimal(s, index++, summary == null ? null : summary.ctr());
        setBigDecimal(s, index++, summary == null ? null : summary.effectiveCpc());
        setInteger(s, index++, summary == null ? null : summary.impressions());
        setBigDecimal(s, index++, summary == null ? null : summary.sales());
        setInteger(s, index++, summary == null ? null : summary.salesCount());
        setInteger(s, index++, summary == null ? null : summary.soldUnits());
        setBigDecimal(s, index++, summary == null ? null : summary.spent());
        setInteger(s, index++, summary == null ? null : summary.activeOfferCount());
        setInteger(s, index++, summary == null ? null : summary.offerCount());
        setInteger(s, index++, summary == null ? null : summary.pausedOfferCount());
        setInteger(s, index++, summary == null ? null : summary.adsetCount());
        setInteger(s, index++, summary == null ? null : summary.keywordCount());
        setInteger(s, index++, summary == null ? null : summary.productTargetCount());
        setBigDecimal(s, index++, summary == null ? null : summary.conversionRate());
        setBigDecimal(s, index++, summary == null ? null : summary.returnOnAdvertisingSpend());
        return index;
    }

    private static int campaignId(Campaign campaign) {
        return Objects.requireNonNull(Objects.requireNonNull(campaign.campaign(), "campaign").id(), "campaign.id");
    }

    private static int adsetId(AdsAdset adset) {
        return Objects.requireNonNull(Objects.requireNonNull(adset, "adset").id(), "adset.id");
    }

    private static BigDecimal recommendedBid(AdsRecommendedBid recommendedBid) {
        return recommendedBid == null ? null : recommendedBid.bid();
    }

    private static void rejectMismatchedAdsetId(Integer rowAdsetId, int expectedAdsetId, String label) throws SQLException {
        if (rowAdsetId != null && rowAdsetId != expectedAdsetId) {
            throw new SQLException("Unexpected adset id for " + label + ": expected " + expectedAdsetId + ", got " + rowAdsetId + ".");
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static int changedRows(int[] batchResult) {
        int rows = 0;
        for (int result : batchResult) {
            if (result == Statement.SUCCESS_NO_INFO) {
                rows++;
            } else if (result > 0) {
                rows += result;
            }
        }
        return rows;
    }

    private static AdsCampaignColumn column(String key, String label, boolean numeric) {
        return new AdsCampaignColumn(key, label, numeric);
    }

    private static AdsAdsetColumn adsetColumn(String key, String label, boolean numeric) {
        return new AdsAdsetColumn(key, label, numeric);
    }

    private static AdsSearchPhraseColumn searchPhraseColumn(String key, String label, boolean numeric) {
        return new AdsSearchPhraseColumn(key, label, numeric);
    }

    private static AdsTargetedProductColumn targetedProductColumn(String key, String label, boolean numeric) {
        return new AdsTargetedProductColumn(key, label, numeric);
    }

    private static AdsCampaignRow readCampaignRow(ResultSet rs) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        for (var column : ADS_CAMPAIGN_COLUMNS) {
            values.put(column.key(), displayValue(rs.getObject(column.key())));
        }
        return new AdsCampaignRow(rs.getInt("campaign_id"), values);
    }

    private static AdsAdsetRow readAdsetRow(ResultSet rs) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        for (var column : ADS_ADSET_COLUMNS) {
            values.put(column.key(), displayValue(rs.getObject(column.key())));
        }
        return new AdsAdsetRow(rs.getInt("campaign_id"), rs.getInt("adset_id"), values);
    }

    private static AdsSearchPhraseRow readSearchPhraseRow(ResultSet rs) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        for (var column : ADS_SEARCH_PHRASE_COLUMNS) {
            values.put(column.key(), displayValue(rs.getObject(column.key())));
        }
        return new AdsSearchPhraseRow(values);
    }

    private static AdsTargetedProductRow readTargetedProductRow(ResultSet rs) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        for (var column : ADS_TARGETED_PRODUCT_COLUMNS) {
            values.put(column.key(), displayValue(rs.getObject(column.key())));
        }
        return new AdsTargetedProductRow(values);
    }

    private static String getCampaignName(Connection db, int campaignId, LocalDate reportDate) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT name
                FROM ads_campaign
                WHERE campaign_id = ?
                  AND report_date = ?
                """)) {
            s.setInt(1, campaignId);
            s.setDate(2, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return stringOrFallback(rs.getString("name"), "campaign ID " + campaignId);
                }
            }
        }
        return "campaign ID " + campaignId;
    }

    private static AdsNames getCampaignAndAdsetNames(Connection db,
                                                     int campaignId,
                                                     int adsetId,
                                                     LocalDate reportDate) throws SQLException {
        try (var s = db.prepareStatement("""
                SELECT c.name AS campaign_name,
                       a.name AS adset_name
                FROM ads_campaign AS c
                LEFT JOIN ads_adset AS a
                  ON a.report_date = c.report_date
                 AND a.campaign_id = c.campaign_id
                 AND a.adset_id = ?
                WHERE c.campaign_id = ?
                  AND c.report_date = ?
                """)) {
            s.setInt(1, adsetId);
            s.setInt(2, campaignId);
            s.setDate(3, Date.valueOf(reportDate));
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    return new AdsNames(
                            stringOrFallback(rs.getString("campaign_name"), "campaign ID " + campaignId),
                            stringOrFallback(rs.getString("adset_name"), "adset ID " + adsetId)
                    );
                }
            }
        }
        return new AdsNames("campaign ID " + campaignId, "adset ID " + adsetId);
    }

    private static String stringOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String displayValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toString().replace('T', ' ');
        }
        return value.toString();
    }

    private static void setDate(PreparedStatement s, int index, LocalDate value) throws SQLException {
        s.setDate(index, Date.valueOf(value));
    }

    private static void setTimestamp(PreparedStatement s, int index, LocalDateTime value) throws SQLException {
        s.setTimestamp(index, toTimestamp(value));
    }

    private static void setBigDecimal(PreparedStatement s, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.NUMERIC);
        } else {
            s.setBigDecimal(index, value);
        }
    }

    private static void setInteger(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.INTEGER);
        } else {
            s.setInt(index, value);
        }
    }

    private static void setBoolean(PreparedStatement s, int index, Boolean value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.BOOLEAN);
        } else {
            s.setBoolean(index, value);
        }
    }
}
