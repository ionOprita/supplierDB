package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads daily Ads primitives without losing the snapshot identity needed for validation. */
public final class ProductPerformanceTable {
    private ProductPerformanceTable() {
    }

    /** Nullable source values are preserved; missing measurements are not measured zeroes. */
    public record Primitives(Long impressions, Long clicks, BigDecimal spend, BigDecimal sales,
                             Long units, Long salesCount) {
    }

    /** Multiple candidate PNKs or keyword match types are returned for the calculator to report. */
    public record DailyAdset(AdsCampaignTable.AdsAdsetKey key, String targeting,
                             List<String> matchTypes, List<String> matchedPnks, Primitives metrics) {
        public DailyAdset {
            Objects.requireNonNull(key, "key");
            matchTypes = List.copyOf(matchTypes);
            matchedPnks = List.copyOf(matchedPnks);
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    /**
     * Returns one row per daily adset snapshot for this product and vendor.
     *
     * <p>Both statuses are checked on the same report date. An adset named after any known vendor
     * product takes precedence over its campaign name, even when it names a different product.
     * Otherwise complete, case-sensitive alphanumeric PNK tokens in the campaign name supply the
     * candidate products; multiple candidates remain visible rather than silently allocating the
     * full adset to several products. Blank product PNKs cannot match.</p>
     *
     * <p>Keyword rows only classify keyword-targeted adsets. Negative entries are ignored, while
     * absent or conflicting classifications remain available for validation. SQL NULL match types
     * become an explicit marker so DISTINCT cannot hide missing data. Classification is aggregated
     * to the full vendor/date/campaign/adset key before joining, so any number of keywords contributes
     * each adset's numeric summary exactly once. No detail-row performance values are used.</p>
     */
    static List<DailyAdset> getDailyAdsets(Connection db, UUID vendorId, String pnk) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(vendorId, "vendorId");
        if (pnk == null || pnk.isBlank()) return List.of();

        var result = new ArrayList<DailyAdset>();
        try (var statement = db.prepareStatement("""
                WITH requested AS (
                    SELECT ?::uuid AS vendor_id, ?::text AS pnk
                ), known_products AS (
                    SELECT DISTINCT regexp_replace(p.emag_pnk,
                        '^[[:space:]]+|[[:space:]]+$', '', 'g') AS pnk
                    FROM product AS p
                    JOIN requested AS r ON r.vendor_id = p.vendor
                    WHERE p.emag_pnk ~ '[^[:space:]]'
                ), active_adsets AS (
                    SELECT a.*, c.name AS campaign_name
                    FROM ads_adset AS a
                    JOIN requested AS r ON r.vendor_id = a.vendor_id
                    JOIN ads_campaign AS c ON c.vendor_id = a.vendor_id
                        AND c.report_date = a.report_date AND c.campaign_id = a.campaign_id
                    WHERE a.status = 'active' AND c.status = 'active'
                ), matched_adsets AS (
                    SELECT a.*,
                           CASE WHEN named.pnk IS NOT NULL THEN ARRAY[named.pnk]
                                ELSE ARRAY(
                                    SELECT p.pnk FROM known_products AS p
                                    WHERE p.pnk = ANY(regexp_split_to_array(
                                        COALESCE(a.campaign_name, ''), '[^[:alnum:]]+'))
                                    ORDER BY p.pnk
                                )
                           END AS matched_pnks
                    FROM active_adsets AS a
                    LEFT JOIN known_products AS named ON named.pnk = regexp_replace(a.name,
                        '^[[:space:]]+|[[:space:]]+$', '', 'g')
                ), selected_adsets AS (
                    SELECT a.* FROM matched_adsets AS a
                    JOIN requested AS r ON r.pnk = ANY(a.matched_pnks)
                ), keyword_types AS (
                    SELECT k.vendor_id, k.report_date, k.campaign_id, k.adset_id,
                           array_agg(DISTINCT COALESCE(k.match_type, '(missing)')
                                     ORDER BY COALESCE(k.match_type, '(missing)')) AS match_types
                    FROM ads_keyword AS k
                    JOIN selected_adsets AS a USING (vendor_id, report_date, campaign_id, adset_id)
                    WHERE a.targeting = 'keywords' AND k.status = 'active' AND k.match_type IS DISTINCT FROM 'negative'
                    GROUP BY k.vendor_id, k.report_date, k.campaign_id, k.adset_id
                )
                SELECT a.report_date, a.campaign_id, a.adset_id, a.targeting, a.matched_pnks,
                       COALESCE(k.match_types, ARRAY[]::text[]) AS match_types,
                       a.summary_impressions, a.summary_clicks, a.summary_spent,
                       a.summary_sales, a.summary_sold_units, a.summary_sales_count
                FROM selected_adsets AS a
                LEFT JOIN keyword_types AS k USING (vendor_id, report_date, campaign_id, adset_id)
                ORDER BY a.report_date, a.campaign_id, a.adset_id
                """)) {
            statement.setObject(1, vendorId);
            statement.setString(2, pnk.strip());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var key = new AdsCampaignTable.AdsAdsetKey(vendorId,
                            rows.getDate("report_date").toLocalDate(),
                            rows.getInt("campaign_id"), rows.getInt("adset_id"));
                    var metrics = new Primitives(nullableLong(rows, "summary_impressions"),
                            nullableLong(rows, "summary_clicks"), rows.getBigDecimal("summary_spent"),
                            rows.getBigDecimal("summary_sales"), nullableLong(rows, "summary_sold_units"),
                            nullableLong(rows, "summary_sales_count"));
                    result.add(new DailyAdset(key, rows.getString("targeting"),
                            stringArray(rows, "match_types"), stringArray(rows, "matched_pnks"), metrics));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static List<String> stringArray(ResultSet rows, String column) throws SQLException {
        var array = rows.getArray(column);
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
