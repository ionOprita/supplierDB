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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class AdsCampaignTable {

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
                    report_start_date,
                    report_end_date,
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_start_date, report_end_date, campaign_id) DO UPDATE SET
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
                    report_start_date,
                    report_end_date,
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_start_date, report_end_date, campaign_id, adset_id) DO UPDATE SET
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
                    report_start_date,
                    report_end_date,
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (
                    report_start_date,
                    report_end_date,
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
                    report_start_date,
                    report_end_date,
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
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (report_start_date, report_end_date, campaign_id, adset_id, doc_id) DO UPDATE SET
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
        setDate(s, index++, Objects.requireNonNull(campaign.reportStartDate(), "reportStartDate"));
        setDate(s, index++, Objects.requireNonNull(campaign.reportEndDate(), "reportEndDate"));
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
