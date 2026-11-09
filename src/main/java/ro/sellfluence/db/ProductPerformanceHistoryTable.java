package ro.sellfluence.db;

import ro.sellfluence.db.versions.EmagMirrorDBVersion49;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable spreadsheet evidence, kept separate from native orders, offers and Ads observations. */
public final class ProductPerformanceHistoryTable {
    private static final int INSERT_BATCH_SIZE = 500;
    private static final Set<String> DOMAINS = Set.of("organic", "ads", "reviews", "rating");
    private static final Set<String> ORGANIC_METRICS = Set.of(
            "stock", "gmv", "clicks", "salesPrice", "sourceConversion", "performance");
    private static final Set<String> ADS_CATEGORIES = Set.of("auto", "broad", "exact", "product");
    private static final Set<String> ADS_PRIMITIVES = Set.of(
            "impressions", "clicks", "spend", "sales", "units", "salesCount");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();
    private static final TypeReference<Map<String, Object>> VALUES_TYPE = new TypeReference<>() {};

    private ProductPerformanceHistoryTable() {}

    /** The observation date is the original source date, never the date of this import. */
    public record Observation(UUID vendorId, String productCode, String pnk, LocalDate observationDate,
                              String domain, Map<String, Object> values, String sourceSpreadsheetId,
                              int sourceSheetId, String sourceSheet, String sourceRange) {
        public Observation {
            Objects.requireNonNull(vendorId, "vendorId");
            requireText(productCode, "productCode");
            Objects.requireNonNull(observationDate, "observationDate");
            if (!DOMAINS.contains(domain)) throw new IllegalArgumentException("Unsupported history domain: " + domain);
            Objects.requireNonNull(values, "values");
            var copy = new LinkedHashMap<String, Object>();
            values.forEach((key, value) -> {
                requireText(key, "metric key");
                validateValue(value);
                copy.put(key, value);
            });
            values = Collections.unmodifiableMap(copy);
            requireText(sourceSpreadsheetId, "sourceSpreadsheetId");
            if (sourceSheetId < 0) throw new IllegalArgumentException("sourceSheetId must not be negative");
            requireText(sourceSheet, "sourceSheet");
            requireText(sourceRange, "sourceRange");
        }

        /** Empty corrections remain stored as evidence, but cannot supply report metrics or date anchors. */
        public boolean hasMetricValues() {
            return values.entrySet().stream().anyMatch(entry -> isMetricKey(domain, entry.getKey())
                    && entry.getValue() != null
                    && !(entry.getValue() instanceof String text && text.isBlank()));
        }
    }

    private static boolean isMetricKey(String domain, String key) {
        return switch (domain) {
            case "organic" -> ORGANIC_METRICS.contains(key);
            case "reviews", "rating" -> domain.equals(key);
            case "ads" -> {
                int separator = key.lastIndexOf('_');
                yield separator > 0 && ADS_CATEGORIES.contains(key.substring(0, separator))
                        && ADS_PRIMITIVES.contains(key.substring(separator + 1));
            }
            default -> false;
        };
    }

    /**
     * The importer hashes its canonical source, mapping and parser version. The raw payload also
     * retains unmapped rows and diagnostics; those rows need not become normalized observations.
     */
    public record ImportBatch(String contentHash, String sourcePayloadJson, List<Observation> observations) {
        public ImportBatch {
            if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex digest");
            }
            requireText(sourcePayloadJson, "sourcePayloadJson");
            var payload = JSON.readTree(sourcePayloadJson);
            if (payload == null || (!payload.isObject() && !payload.isArray())) {
                throw new IllegalArgumentException("sourcePayloadJson must contain a JSON object or array");
            }
            observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
            var identities = new HashSet<ObservationKey>();
            for (var observation : observations) {
                var key = new ObservationKey(observation.vendorId(), observation.productCode(),
                        observation.observationDate(), observation.domain());
                if (!identities.add(key)) {
                    throw new IllegalArgumentException("Duplicate source observation for "
                            + observation.productCode() + " on " + observation.observationDate()
                            + " in domain " + observation.domain());
                }
            }
        }
    }

    public record StoreResult(UUID batchId, boolean inserted, int observationCount) {}

    private record ObservationKey(UUID vendorId, String productCode, LocalDate observationDate, String domain) {}

    /**
     * CLI-only migration entry point: apply just this feature's migration, in the caller's
     * transaction. Dry runs must inspect the schema without calling this method.
     *
     * @return true when version 49 was applied, false when it was already installed
     */
    public static boolean prepareSchema(Connection db) throws SQLException {
        Objects.requireNonNull(db, "db");
        if (db.getAutoCommit()) throw new SQLException("History migration requires an explicit transaction");
        try (var statement = db.createStatement()) {
            statement.execute("LOCK TABLE version_info IN EXCLUSIVE MODE");
            int version;
            try (var rows = statement.executeQuery("SELECT MAX(version) FROM version_info")) {
                rows.next();
                version = rows.getInt(1);
            }
            if (version == 49) return false;
            if (version != 48) throw new SQLException("History import requires database version 48 or 49; found " + version);
            EmagMirrorDBVersion49.version49(db);
            statement.executeUpdate("INSERT INTO version_info (version, date) VALUES (49, CURRENT_TIMESTAMP)");
            return true;
        }
    }

    /**
     * Inserts one immutable revision atomically. The caller owns commit/rollback; this method never
     * opens an application database or runs migrations. Repeating a content hash performs no updates.
     */
    public static StoreResult storeBatch(Connection db, ImportBatch batch) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(batch, "batch");
        if (db.getAutoCommit()) throw new SQLException("History imports require an explicit transaction");
        var batchId = UUID.randomUUID();
        try (var statement = db.prepareStatement("""
                INSERT INTO product_performance_history_batch
                    (id, content_hash, source_payload, observation_count)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (content_hash) DO NOTHING
                RETURNING id
                """)) {
            statement.setObject(1, batchId);
            statement.setString(2, batch.contentHash());
            statement.setString(3, batch.sourcePayloadJson());
            statement.setInt(4, batch.observations().size());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return findBatch(db, batch.contentHash());
            }
        }
        try (var statement = db.prepareStatement("""
                INSERT INTO product_performance_history_observation
                    (batch_id, observation_index, vendor_id, product_code, pnk, observation_date, domain,
                     metric_values, source_spreadsheet_id, source_sheet_id, source_sheet, source_range)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM product WHERE product_code = ? AND vendor = ?)
                """)) {
            int index = 0;
            var pending = new ArrayList<Observation>(INSERT_BATCH_SIZE);
            for (var observation : batch.observations()) {
                statement.setObject(1, batchId);
                statement.setInt(2, index++);
                statement.setObject(3, observation.vendorId());
                statement.setString(4, observation.productCode());
                statement.setString(5, observation.pnk());
                statement.setObject(6, observation.observationDate());
                statement.setString(7, observation.domain());
                statement.setString(8, JSON.writeValueAsString(observation.values()));
                statement.setString(9, observation.sourceSpreadsheetId());
                statement.setInt(10, observation.sourceSheetId());
                statement.setString(11, observation.sourceSheet());
                statement.setString(12, observation.sourceRange());
                statement.setString(13, observation.productCode());
                statement.setObject(14, observation.vendorId());
                statement.addBatch();
                pending.add(observation);
                if (pending.size() == INSERT_BATCH_SIZE) {
                    insertObservations(statement, pending);
                    pending.clear();
                }
            }
            if (!pending.isEmpty()) insertObservations(statement, pending);
        }
        return new StoreResult(batchId, true, batch.observations().size());
    }

    /** Require an exact ownership-confirming insert count for every row; the caller rolls back all batches on failure. */
    private static void insertObservations(PreparedStatement statement, List<Observation> observations)
            throws SQLException {
        var counts = statement.executeBatch();
        if (counts.length != observations.size()) {
            throw new SQLException("History insert did not report a result for every observation");
        }
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] != 1) {
                // Zero denotes a failed account ownership check. Unknown/failed JDBC counts are
                // also unsafe: they cannot prove that every selected product was inserted once.
                throw new SQLException("History product ownership or insert count could not be verified: "
                        + observations.get(index).productCode() + " (update count " + counts[index] + ")");
            }
        }
        statement.clearBatch();
    }

    private static StoreResult findBatch(Connection db, String hash) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT id, observation_count
                FROM product_performance_history_batch
                WHERE content_hash = ?
                """)) {
            statement.setString(1, hash);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Concurrent history import is not visible; retry the transaction");
                return new StoreResult(rows.getObject("id", UUID.class), false, rows.getInt("observation_count"));
            }
        }
    }

    /** Latest revision for each product/date/domain, including corrections from a different source. */
    public static List<Observation> getProductPerformanceHistory(Connection db, UUID vendorId, String productCode)
            throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(vendorId, "vendorId");
        requireText(productCode, "productCode");
        var result = new ArrayList<Observation>();
        try (var statement = db.prepareStatement("""
                SELECT * FROM (
                    SELECT DISTINCT ON (o.observation_date, o.domain)
                           o.*
                    FROM product_performance_history_observation AS o
                    JOIN product_performance_history_batch AS b ON b.id = o.batch_id
                    WHERE o.vendor_id = ? AND o.product_code = ?
                    ORDER BY o.observation_date, o.domain, b.revision DESC, o.observation_index DESC
                ) AS latest
                ORDER BY observation_date, domain, source_spreadsheet_id, source_sheet_id
                """)) {
            statement.setObject(1, vendorId);
            statement.setString(2, productCode);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Observation(rows.getObject("vendor_id", UUID.class),
                            rows.getString("product_code"), rows.getString("pnk"),
                            rows.getObject("observation_date", LocalDate.class), rows.getString("domain"),
                            JSON.readValue(rows.getString("metric_values"), VALUES_TYPE),
                            rows.getString("source_spreadsheet_id"), rows.getInt("source_sheet_id"),
                            rows.getString("source_sheet"), rows.getString("source_range")));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Saved campaign observations, not proof that a successful empty report was captured. */
    public static Set<LocalDate> getProductPerformanceAdsReportDates(
            Connection db, UUID vendorId, LocalDate start, LocalDate end) throws SQLException {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(vendorId, "vendorId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) throw new IllegalArgumentException("end must not precede start");
        var result = new HashSet<LocalDate>();
        try (var statement = db.prepareStatement("""
                SELECT DISTINCT report_date
                FROM ads_campaign
                WHERE vendor_id = ? AND report_date BETWEEN ? AND ?
                """)) {
            statement.setObject(1, vendorId);
            statement.setObject(2, start);
            statement.setObject(3, end);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getObject("report_date", LocalDate.class));
            }
        }
        return Set.copyOf(result);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void validateValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal
                || value instanceof BigInteger || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return;
        if (value instanceof Double number && Double.isFinite(number)) return;
        if (value instanceof Float number && Float.isFinite(number)) return;
        throw new IllegalArgumentException("History metric values must be finite scalar values or null");
    }
}
