package ro.sellfluence.db;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Current shared FBE processing tariffs; stable IDs preserve distinct weight tiers with repeated names. */
public final class ProfitCalculatorFbeTariffTable {
    private static final Set<String> POSITIVE_FIELDS = Set.of("maxLengthMm", "maxWidthMm", "maxHeightMm",
            "maxGirthMm", "weightBandKg", "weightRangeKg");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("weightRangeKg", "extraOrderFeeRon",
            "extraReturnFeeRon", "extraRemovalFeeRon", "extraDisposalFeeRon");
    private static final Set<String> DECIMAL_FIELDS = Set.of("maxLengthMm", "maxWidthMm", "maxHeightMm",
            "maxGirthMm", "weightBandKg", "orderProcessingFeeRon", "returnProcessingFeeRon", "removalFeeRon",
            "disposalFeeRon", "weightRangeKg", "extraOrderFeeRon", "extraReturnFeeRon", "extraRemovalFeeRon",
            "extraDisposalFeeRon");

    private ProfitCalculatorFbeTariffTable() {
    }

    public record Tariff(int id, String sizeCategory, BigDecimal maxLengthMm, BigDecimal maxWidthMm,
                         BigDecimal maxHeightMm, BigDecimal maxGirthMm, BigDecimal weightBandKg,
                         BigDecimal orderProcessingFeeRon, BigDecimal returnProcessingFeeRon,
                         BigDecimal removalFeeRon, BigDecimal disposalFeeRon, BigDecimal weightRangeKg,
                         BigDecimal extraOrderFeeRon, BigDecimal extraReturnFeeRon,
                         BigDecimal extraRemovalFeeRon, BigDecimal extraDisposalFeeRon, long revision) {
        public Tariff {
            requireId(id);
            if (revision < 1) throw new IllegalArgumentException("A positive tariff revision is required.");
            sizeCategory = category(sizeCategory);
            validateDecimal("maxLengthMm", maxLengthMm);
            validateDecimal("maxWidthMm", maxWidthMm);
            validateDecimal("maxHeightMm", maxHeightMm);
            validateDecimal("maxGirthMm", maxGirthMm);
            validateDecimal("weightBandKg", weightBandKg);
            validateDecimal("orderProcessingFeeRon", orderProcessingFeeRon);
            validateDecimal("returnProcessingFeeRon", returnProcessingFeeRon);
            validateDecimal("removalFeeRon", removalFeeRon);
            validateDecimal("disposalFeeRon", disposalFeeRon);
            validateDecimal("weightRangeKg", weightRangeKg);
            validateDecimal("extraOrderFeeRon", extraOrderFeeRon);
            validateDecimal("extraReturnFeeRon", extraReturnFeeRon);
            validateDecimal("extraRemovalFeeRon", extraRemovalFeeRon);
            validateDecimal("extraDisposalFeeRon", extraDisposalFeeRon);
        }

        /** Editable decimals must survive browser JSON parsing without binary floating-point rounding. */
        @JsonValue
        public Map<String, Object> jsonValues() {
            var values = new LinkedHashMap<String, Object>();
            values.put("id", id);
            values.put("sizeCategory", sizeCategory);
            values.put("maxLengthMm", plain(maxLengthMm));
            values.put("maxWidthMm", plain(maxWidthMm));
            values.put("maxHeightMm", plain(maxHeightMm));
            values.put("maxGirthMm", plain(maxGirthMm));
            values.put("weightBandKg", plain(weightBandKg));
            values.put("orderProcessingFeeRon", plain(orderProcessingFeeRon));
            values.put("returnProcessingFeeRon", plain(returnProcessingFeeRon));
            values.put("removalFeeRon", plain(removalFeeRon));
            values.put("disposalFeeRon", plain(disposalFeeRon));
            values.put("weightRangeKg", plain(weightRangeKg));
            values.put("extraOrderFeeRon", plain(extraOrderFeeRon));
            values.put("extraReturnFeeRon", plain(extraReturnFeeRon));
            values.put("extraRemovalFeeRon", plain(extraRemovalFeeRon));
            values.put("extraDisposalFeeRon", plain(extraDisposalFeeRon));
            values.put("revision", revision);
            return values;
        }

        public Tariff applyChanges(Map<String, String> values) {
            validatePatch(values);
            return new Tariff(id, values.containsKey("sizeCategory") ? values.get("sizeCategory") : sizeCategory,
                    changed(values, "maxLengthMm", maxLengthMm), changed(values, "maxWidthMm", maxWidthMm),
                    changed(values, "maxHeightMm", maxHeightMm), changed(values, "maxGirthMm", maxGirthMm),
                    changed(values, "weightBandKg", weightBandKg),
                    changed(values, "orderProcessingFeeRon", orderProcessingFeeRon),
                    changed(values, "returnProcessingFeeRon", returnProcessingFeeRon),
                    changed(values, "removalFeeRon", removalFeeRon), changed(values, "disposalFeeRon", disposalFeeRon),
                    changed(values, "weightRangeKg", weightRangeKg),
                    changed(values, "extraOrderFeeRon", extraOrderFeeRon),
                    changed(values, "extraReturnFeeRon", extraReturnFeeRon),
                    changed(values, "extraRemovalFeeRon", extraRemovalFeeRon),
                    changed(values, "extraDisposalFeeRon", extraDisposalFeeRon), revision);
        }
    }

    public record Change(int id, long expectedRevision, Map<String, String> values) {
    }

    public static final class ConflictException extends SQLException {
        private final int tariffId;

        public ConflictException(int tariffId) {
            super("Tariff " + tariffId + " changed. Reload the saved grid before saving again.");
            this.tariffId = tariffId;
        }

        public int tariffId() {
            return tariffId;
        }
    }

    public static List<Tariff> readTariffs(Connection db) throws SQLException {
        var tariffs = new ArrayList<Tariff>();
        try (var statement = db.prepareStatement("SELECT * FROM profit_calculator_fbe_tariff ORDER BY id");
             var rows = statement.executeQuery()) {
            while (rows.next()) tariffs.add(readTariff(rows));
        }
        return List.copyOf(tariffs);
    }

    /** Structural and individual-field validation also used before opening a save transaction. */
    public static void validateChanges(List<Change> changes) {
        if (changes == null || changes.size() > 12) {
            throw new IllegalArgumentException("Provide at most 12 tariff changes.");
        }
        var ids = new HashSet<Integer>();
        for (var change : changes) {
            if (change == null) throw new IllegalArgumentException("Invalid tariff change.");
            requireId(change.id());
            if (change.expectedRevision() < 1 || !ids.add(change.id()) || change.values() == null
                    || change.values().isEmpty()) {
                throw new IllegalArgumentException("Each tariff change needs a unique ID, revision and changed fields.");
            }
            validatePatch(change.values());
        }
    }

    /** One rejected batch rolls back its own writes while retaining earlier work in the caller's transaction. */
    public static void saveChanges(Connection db, List<Change> changes) throws SQLException {
        if (db.getAutoCommit()) throw new SQLException("Saving tariffs requires an explicit transaction.");
        validateChanges(changes);
        if (changes.isEmpty()) return;
        var savepoint = db.setSavepoint();
        try {
            for (var change : changes.stream().sorted(Comparator.comparingInt(Change::id)).toList()) {
                saveChange(db, change);
            }
            db.releaseSavepoint(savepoint);
        } catch (SQLException | RuntimeException error) {
            try {
                db.rollback(savepoint);
                db.releaseSavepoint(savepoint);
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
    }

    private static void saveChange(Connection db, Change change) throws SQLException {
        Tariff saved;
        try (var statement = db.prepareStatement("SELECT * FROM profit_calculator_fbe_tariff WHERE id = ? FOR UPDATE")) {
            statement.setInt(1, change.id());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong("revision") != change.expectedRevision()) {
                    throw new ConflictException(change.id());
                }
                saved = readTariff(rows);
            }
        }
        var values = saved.applyChanges(change.values());
        try (var statement = db.prepareStatement("""
                UPDATE profit_calculator_fbe_tariff
                SET size_category = ?, max_length_mm = ?, max_width_mm = ?, max_height_mm = ?, max_girth_mm = ?,
                    weight_band_kg = ?, order_processing_fee_ron = ?, return_processing_fee_ron = ?,
                    removal_fee_ron = ?, disposal_fee_ron = ?, weight_range_kg = ?,
                    extra_order_fee_ron = ?, extra_return_fee_ron = ?, extra_removal_fee_ron = ?,
                    extra_disposal_fee_ron = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                """)) {
            bindTariff(statement, values);
            statement.setInt(16, change.id());
            statement.setLong(17, change.expectedRevision());
            if (statement.executeUpdate() != 1) throw new ConflictException(change.id());
        }
    }

    private static Tariff readTariff(ResultSet rows) throws SQLException {
        return new Tariff(rows.getInt("id"), rows.getString("size_category"), rows.getBigDecimal("max_length_mm"),
                rows.getBigDecimal("max_width_mm"), rows.getBigDecimal("max_height_mm"),
                rows.getBigDecimal("max_girth_mm"), rows.getBigDecimal("weight_band_kg"),
                rows.getBigDecimal("order_processing_fee_ron"), rows.getBigDecimal("return_processing_fee_ron"),
                rows.getBigDecimal("removal_fee_ron"), rows.getBigDecimal("disposal_fee_ron"),
                rows.getBigDecimal("weight_range_kg"), rows.getBigDecimal("extra_order_fee_ron"),
                rows.getBigDecimal("extra_return_fee_ron"), rows.getBigDecimal("extra_removal_fee_ron"),
                rows.getBigDecimal("extra_disposal_fee_ron"), rows.getLong("revision"));
    }

    private static void bindTariff(PreparedStatement statement, Tariff values) throws SQLException {
        statement.setString(1, values.sizeCategory());
        statement.setBigDecimal(2, values.maxLengthMm());
        statement.setBigDecimal(3, values.maxWidthMm());
        statement.setBigDecimal(4, values.maxHeightMm());
        statement.setBigDecimal(5, values.maxGirthMm());
        statement.setBigDecimal(6, values.weightBandKg());
        statement.setBigDecimal(7, values.orderProcessingFeeRon());
        statement.setBigDecimal(8, values.returnProcessingFeeRon());
        statement.setBigDecimal(9, values.removalFeeRon());
        statement.setBigDecimal(10, values.disposalFeeRon());
        statement.setBigDecimal(11, values.weightRangeKg());
        statement.setBigDecimal(12, values.extraOrderFeeRon());
        statement.setBigDecimal(13, values.extraReturnFeeRon());
        statement.setBigDecimal(14, values.extraRemovalFeeRon());
        statement.setBigDecimal(15, values.extraDisposalFeeRon());
    }

    private static void validatePatch(Map<String, String> values) {
        if (values == null) throw new IllegalArgumentException("Tariff fields are required.");
        for (var entry : values.entrySet()) {
            if ("sizeCategory".equals(entry.getKey())) category(entry.getValue());
            else if (entry.getKey() != null && DECIMAL_FIELDS.contains(entry.getKey())) {
                validateDecimal(entry.getKey(), decimal(entry.getKey(), entry.getValue()));
            } else throw new IllegalArgumentException("Unknown tariff field.");
        }
    }

    private static BigDecimal changed(Map<String, String> values, String key, BigDecimal saved) {
        return values.containsKey(key) ? decimal(key, values.get(key)) : saved;
    }

    private static BigDecimal decimal(String field, String input) {
        if (input == null || input.isBlank()) return null;
        var text = input.strip();
        if (text.length() > 64 || !text.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new IllegalArgumentException("Invalid decimal value for " + field + ".");
        }
        var value = new BigDecimal(text);
        if (value.toPlainString().length() > 64) {
            throw new IllegalArgumentException("Invalid decimal value for " + field + ".");
        }
        return value;
    }

    private static void validateDecimal(String field, BigDecimal value) {
        if (value == null) {
            if (!OPTIONAL_FIELDS.contains(field)) throw new IllegalArgumentException(field + " is required.");
        } else if (POSITIVE_FIELDS.contains(field) ? value.signum() <= 0 : value.signum() < 0) {
            throw new IllegalArgumentException(field + (POSITIVE_FIELDS.contains(field)
                    ? " must be greater than zero." : " cannot be negative."));
        }
    }

    private static String category(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200) {
            throw new IllegalArgumentException("A size category of at most 200 characters is required.");
        }
        return value.strip();
    }

    private static void requireId(int id) {
        if (id < 1 || id > 12) throw new IllegalArgumentException("Unknown tariff ID.");
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
