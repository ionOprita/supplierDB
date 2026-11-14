package ro.sellfluence.db;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Product metadata and reference values for the category profit calculator. */
public final class ProfitCalculatorTable {
    private static final String STRIP_CHARACTERS = stripCharacters();

    private ProfitCalculatorTable() {
    }

    public record Overrides(BigDecimal salePriceRon, BigDecimal commissionRate, BigDecimal purchasePriceUsd,
                            BigDecimal transportPriceUsd, BigDecimal usdRonRate, String fulfillmentSource) {
        private static final Set<String> FIELDS = Set.of("salePriceRon", "commissionRate", "purchasePriceUsd",
                "transportPriceUsd", "usdRonRate", "fulfillmentSource");

        public Overrides {
            nonnegative("Selling price", salePriceRon);
            nonnegative("Commission", commissionRate);
            if (commissionRate != null && commissionRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Commission must be between 0% and 100%.");
            }
            nonnegative("Purchase price", purchasePriceUsd);
            nonnegative("Transport price", transportPriceUsd);
            if (usdRonRate != null && usdRonRate.signum() <= 0) {
                throw new IllegalArgumentException("USD/RON exchange rate must be positive.");
            }
            fulfillmentSource = fulfillmentSource == null || fulfillmentSource.isBlank()
                    ? null : fulfillmentSource.strip();
            if (fulfillmentSource != null && !Set.of("eMAG", "Minulescu").contains(fulfillmentSource)) {
                throw new IllegalArgumentException("Unknown fulfillment source.");
            }
        }

        public static Overrides empty() {
            return new Overrides(null, null, null, null, null, null);
        }

        /** Preserve decimal input exactly through browser JSON parsing, including very small rates. */
        @JsonValue
        public Map<String, String> jsonValues() {
            var values = new LinkedHashMap<String, String>();
            values.put("salePriceRon", plainDecimal(salePriceRon));
            values.put("commissionRate", plainDecimal(commissionRate));
            values.put("purchasePriceUsd", plainDecimal(purchasePriceUsd));
            values.put("transportPriceUsd", plainDecimal(transportPriceUsd));
            values.put("usdRonRate", plainDecimal(usdRonRate));
            values.put("fulfillmentSource", fulfillmentSource);
            return values;
        }

        private static String plainDecimal(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }

        /** Null or blank clears a supplied field; omitted fields retain their saved value. */
        public Overrides applyChanges(Map<String, String> values) {
            if (values == null || values.keySet().stream().anyMatch(field -> field == null || !FIELDS.contains(field))) {
                throw new IllegalArgumentException("Unknown or missing override fields.");
            }
            return new Overrides(decimalChange(values, "salePriceRon", salePriceRon),
                    decimalChange(values, "commissionRate", commissionRate),
                    decimalChange(values, "purchasePriceUsd", purchasePriceUsd),
                    decimalChange(values, "transportPriceUsd", transportPriceUsd),
                    decimalChange(values, "usdRonRate", usdRonRate),
                    values.containsKey("fulfillmentSource") ? values.get("fulfillmentSource") : fulfillmentSource);
        }

        private static BigDecimal decimalChange(Map<String, String> values, String field, BigDecimal saved) {
            if (!values.containsKey(field)) return saved;
            var input = values.get(field);
            if (input == null || input.isBlank()) return null;
            var value = input.strip();
            if (value.length() > 64 || !value.matches("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
                throw new IllegalArgumentException("Invalid decimal value for " + field + ".");
            }
            return new BigDecimal(value);
        }

        private static void nonnegative(String label, BigDecimal value) {
            if (value != null && value.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
        }
    }

    public record OverrideChange(String productCode, long expectedRevision, Map<String, String> values) {
    }

    public static final class OverrideConflictException extends SQLException {
        private final String productCode;

        public OverrideConflictException(String productCode) {
            super("Overrides for product " + productCode + " changed. Reload saved values before saving again.");
            this.productCode = productCode;
        }

        public String productCode() {
            return productCode;
        }
    }

    public record CategoryProduct(String productCode, String name, String pnk, boolean retracted,
                                  BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer,
                                  String companyName, Overrides overrides, long overrideRevision) {
        public CategoryProduct {
            overrides = overrides == null ? Overrides.empty() : overrides;
        }

        public CategoryProduct(String productCode, String name, String pnk, boolean retracted) {
            this(productCode, name, pnk, retracted, null, null, null);
        }

        public CategoryProduct(String productCode, String name, String pnk, boolean retracted,
                               BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer) {
            this(productCode, name, pnk, retracted, emagSalePriceRon, incomeProfitTax, vatPayer,
                    null, Overrides.empty(), 0);
        }
    }

    static List<CategoryProduct> getCategoryProducts(Connection db, String vendorGroup, String category)
            throws SQLException {
        var products = new ArrayList<CategoryProduct>();
        try (var statement = db.prepareStatement("""
                SELECT p.product_code, p.name, p.emag_pnk, p.retracted,
                       p.emag_sale_price_ron, p.income_profit_tax, p.vat_payer, v.company_name,
                       pc.sale_price_ron, pc.commission_rate, pc.purchase_price_usd,
                       pc.transport_price_usd, pc.usd_ron_rate, pc.fulfillment_source,
                       COALESCE(pc.revision, 0) AS override_revision
                FROM product p
                JOIN vendor v ON p.vendor = v.id
                LEFT JOIN profit_calculator_override pc ON pc.product_code = p.product_code
                WHERE btrim(v.vendor_group, ?) = ? AND btrim(p.category, ?) = ?
                """)) {
            statement.setString(1, STRIP_CHARACTERS);
            statement.setString(2, vendorGroup);
            statement.setString(3, STRIP_CHARACTERS);
            statement.setString(4, category);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    products.add(new CategoryProduct(result.getString("product_code"), result.getString("name"),
                            result.getString("emag_pnk"), result.getBoolean("retracted"),
                            result.getBigDecimal("emag_sale_price_ron"), result.getString("income_profit_tax"),
                            result.getObject("vat_payer", Boolean.class), result.getString("company_name"),
                            readOverrides(result), result.getLong("override_revision")));
                }
            }
        }
        return products;
    }

    /** Save one batch inside the caller's transaction, retaining its earlier work on a rejected batch. */
    static void saveOverrides(Connection db, List<OverrideChange> changes) throws SQLException {
        if (db.getAutoCommit()) throw new SQLException("Saving overrides requires an explicit transaction.");
        if (changes == null) throw new IllegalArgumentException("Override changes are required.");
        var productCodes = new HashSet<String>();
        for (var change : changes) {
            if (change == null || change.productCode() == null || change.productCode().isBlank()
                    || change.expectedRevision() < 0 || change.values() == null || change.values().isEmpty()
                    || !productCodes.add(change.productCode())) {
                throw new IllegalArgumentException("Each override change needs a unique product, revision and changed fields.");
            }
            Overrides.empty().applyChanges(change.values());
        }
        if (changes.isEmpty()) return;
        var savepoint = db.setSavepoint();
        try {
            for (var change : changes.stream().sorted(Comparator.comparing(OverrideChange::productCode)).toList()) {
                saveOverride(db, change);
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

    private static void saveOverride(Connection db, OverrideChange change) throws SQLException {
        try (var statement = db.prepareStatement("SELECT product_code FROM product WHERE product_code = ? FOR KEY SHARE")) {
            statement.setString(1, change.productCode());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Unknown product " + change.productCode() + ".");
            }
        }
        if (change.expectedRevision() == 0) {
            var values = Overrides.empty().applyChanges(change.values());
            try (var statement = db.prepareStatement("""
                    INSERT INTO profit_calculator_override
                        (sale_price_ron, commission_rate, purchase_price_usd, transport_price_usd,
                         usd_ron_rate, fulfillment_source, product_code, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (product_code) DO NOTHING
                    """)) {
                bindOverrides(statement, values);
                statement.setString(7, change.productCode());
                if (statement.executeUpdate() != 1) throw new OverrideConflictException(change.productCode());
            }
            return;
        }
        Overrides saved;
        try (var statement = db.prepareStatement("SELECT * FROM profit_calculator_override WHERE product_code = ? FOR UPDATE")) {
            statement.setString(1, change.productCode());
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getLong("revision") != change.expectedRevision()) {
                    throw new OverrideConflictException(change.productCode());
                }
                saved = readOverrides(result);
            }
        }
        var values = saved.applyChanges(change.values());
        try (var statement = db.prepareStatement("""
                UPDATE profit_calculator_override
                SET sale_price_ron = ?, commission_rate = ?, purchase_price_usd = ?, transport_price_usd = ?,
                    usd_ron_rate = ?, fulfillment_source = ?, revision = revision + 1
                WHERE product_code = ? AND revision = ?
                """)) {
            bindOverrides(statement, values);
            statement.setString(7, change.productCode());
            statement.setLong(8, change.expectedRevision());
            if (statement.executeUpdate() != 1) throw new OverrideConflictException(change.productCode());
        }
    }

    private static Overrides readOverrides(ResultSet result) throws SQLException {
        return new Overrides(result.getBigDecimal("sale_price_ron"), result.getBigDecimal("commission_rate"),
                result.getBigDecimal("purchase_price_usd"), result.getBigDecimal("transport_price_usd"),
                result.getBigDecimal("usd_ron_rate"), result.getString("fulfillment_source"));
    }

    private static void bindOverrides(java.sql.PreparedStatement statement, Overrides values) throws SQLException {
        statement.setBigDecimal(1, values.salePriceRon());
        statement.setBigDecimal(2, values.commissionRate());
        statement.setBigDecimal(3, values.purchasePriceUsd());
        statement.setBigDecimal(4, values.transportPriceUsd());
        statement.setBigDecimal(5, values.usdRonRate());
        statement.setString(6, values.fulfillmentSource());
    }

    private static String stripCharacters() {
        // Selector options use String.strip(); PostgreSQL's default btrim only removes spaces.
        var characters = new StringBuilder();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.isWhitespace(codePoint)) characters.appendCodePoint(codePoint);
        }
        return characters.toString();
    }
}
