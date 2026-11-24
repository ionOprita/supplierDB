package ro.sellfluence.db;

import com.fasterxml.jackson.annotation.JsonValue;
import ro.sellfluence.db.ProfitCalculatorSettingsTable.CategorySettings;
import ro.sellfluence.support.Statistics;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Product metadata and reference values for the category profit calculator. */
public final class ProfitCalculatorTable {
    static final String STRIP_CHARACTERS = stripCharacters();

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
                                  String companyName, Overrides overrides, long overrideRevision,
                                  BigDecimal emagCommission, Long previousMonthGrossUnits,
                                  BigDecimal productLengthMm, BigDecimal productWidthMm,
                                  BigDecimal productHeightMm, BigDecimal productWeightG) {
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

        public CategoryProduct(String productCode, String name, String pnk, boolean retracted,
                               BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer,
                               String companyName, Overrides overrides, long overrideRevision) {
            this(productCode, name, pnk, retracted, emagSalePriceRon, incomeProfitTax, vatPayer,
                    companyName, overrides, overrideRevision, null, null);
        }

        public CategoryProduct(String productCode, String name, String pnk, boolean retracted,
                               BigDecimal emagSalePriceRon, String incomeProfitTax, Boolean vatPayer,
                               String companyName, Overrides overrides, long overrideRevision,
                               BigDecimal emagCommission, Long previousMonthGrossUnits) {
            this(productCode, name, pnk, retracted, emagSalePriceRon, incomeProfitTax, vatPayer,
                    companyName, overrides, overrideRevision, emagCommission, previousMonthGrossUnits,
                    null, null, null, null);
        }
    }

    public record ReturnRates(Double returnRate, Double refusalRate) {
    }

    /** Category products and calculation inputs from one database snapshot. */
    public record CategoryData(List<CategoryProduct> products, List<ProfitCalculatorFbeTariffTable.Tariff> tariffs,
                               Map<String, ReturnRates> returnRatesByPnk, CategorySettings settings,
                               Map<String, BigDecimal> replacementRatesByPnk) {
        public CategoryData {
            products = List.copyOf(products);
            tariffs = List.copyOf(tariffs);
            returnRatesByPnk = Map.copyOf(returnRatesByPnk);
            replacementRatesByPnk = Map.copyOf(replacementRatesByPnk);
        }

        public CategoryData(List<CategoryProduct> products, List<ProfitCalculatorFbeTariffTable.Tariff> tariffs,
                            Map<String, ReturnRates> returnRatesByPnk, CategorySettings settings) {
            this(products, tariffs, returnRatesByPnk, settings, Map.of());
        }

        public CategoryData(List<CategoryProduct> products, List<ProfitCalculatorFbeTariffTable.Tariff> tariffs) {
            this(products, tariffs, Map.of());
        }

        public CategoryData(List<CategoryProduct> products, List<ProfitCalculatorFbeTariffTable.Tariff> tariffs,
                            Map<String, ReturnRates> returnRatesByPnk) {
            this(products, tariffs, returnRatesByPnk, CategorySettings.empty());
        }
    }

    static Map<String, ReturnRates> readReturnRates(Connection db, List<CategoryProduct> products,
                                                   YearMonth currentMonth) throws SQLException {
        var pnks = products.stream()
                .filter(product -> !product.retracted() && product.pnk() != null && !product.pnk().isBlank())
                .map(CategoryProduct::pnk)
                .collect(Collectors.toSet());
        if (pnks.isEmpty()) return Map.of();

        // Follow the central overview's rate populations, including its account and snapshot rules.
        var longStart = currentMonth.minusMonths(12);
        var shortStart = currentMonth.minusMonths(6);
        var orders = MonthlyUnitCounts.read(db, longStart, currentMonth, OrderQuantityTable.MONTHS_SQL, pnks);
        var returns = MonthlyUnitCounts.read(db, longStart, currentMonth, MonthlyUnitCounts.RETURN_SQL, pnks);
        var stornos = MonthlyUnitCounts.read(db, longStart, currentMonth, MonthlyUnitCounts.STORNO_SQL, pnks);

        var ratesByPnk = new LinkedHashMap<String, ReturnRates>();
        for (var pnk : pnks) {
            var rates = windowRates(shortStart, currentMonth, orders.get(pnk), returns.get(pnk), stornos.get(pnk));
            if (rates.exceedsThreshold()) {
                rates = windowRates(longStart, currentMonth, orders.get(pnk), returns.get(pnk), stornos.get(pnk));
            }
            ratesByPnk.put(pnk, new ReturnRates(rates.returnRate(), rates.refusalRate()));
        }
        return ratesByPnk;
    }

    private static WindowRates windowRates(YearMonth start, YearMonth end, Map<YearMonth, Integer> orders,
                                           Map<YearMonth, Integer> returns, Map<YearMonth, Integer> stornos) {
        var ordered = Statistics.sumOver(start, end, orders);
        var returned = Statistics.sumOver(start, end, returns);
        var storned = Statistics.sumOver(start, end, stornos);
        return new WindowRates(observedRate(storned, ordered), observedRate(returned, ordered),
                observedRate((long) storned - returned, ordered));
    }

    private static Double observedRate(long count, long ordered) {
        var estimate = Statistics.estimateRateOrNull(count, ordered, 0.95);
        return estimate == null ? null : estimate.rate();
    }

    private record WindowRates(Double stornoRate, Double returnRate, Double refusalRate) {
        boolean exceedsThreshold() {
            return aboveThreshold(stornoRate) || aboveThreshold(returnRate) || aboveThreshold(refusalRate);
        }

        private static boolean aboveThreshold(Double rate) {
            return rate != null && rate > 0.25;
        }
    }

    static List<CategoryProduct> getCategoryProducts(Connection db, String vendorGroup, String category,
                                                     YearMonth previousMonth) throws SQLException {
        Objects.requireNonNull(previousMonth, "previousMonth");
        var products = new ArrayList<CategoryProduct>();
        try (var statement = db.prepareStatement("""
                WITH selected_products AS (
                    SELECT p.product_code, p.name, p.emag_pnk, p.retracted,
                           p.emag_sale_price_ron, p.income_profit_tax, p.vat_payer, p.emag_commission,
                           p.product_length_mm, p.product_width_mm, p.product_height_mm, p.product_weight_g,
                           v.company_name
                    FROM product p
                    JOIN vendor v ON p.vendor = v.id
                    WHERE btrim(v.vendor_group, ?) = ? AND btrim(p.category, ?) = ?
                ), monthly_gross_units AS (
                    SELECT pio.part_number_key AS pnk,
                           CASE WHEN COUNT(pio.quantity) = COUNT(*)
                                THEN SUM(pio.quantity)::bigint END AS gross_units
                    FROM emag_order o
                    JOIN vendor seller ON seller.id = o.vendor_id
                    JOIN product_in_order pio ON pio.emag_order_surrogate_id = o.surrogate_id
                    WHERE o.status = 4 AND o.date >= ? AND o.date < ?
                      AND btrim(seller.vendor_group, ?) = ?
                      AND pio.part_number_key IN (SELECT emag_pnk FROM selected_products)
                    GROUP BY pio.part_number_key
                )
                SELECT p.*,
                       pc.sale_price_ron, pc.commission_rate, pc.purchase_price_usd,
                       pc.transport_price_usd, pc.usd_ron_rate, pc.fulfillment_source,
                       COALESCE(pc.revision, 0) AS override_revision,
                       CASE WHEN units.pnk IS NULL THEN 0::bigint ELSE units.gross_units END
                           AS previous_month_gross_units
                FROM selected_products p
                LEFT JOIN profit_calculator_override pc ON pc.product_code = p.product_code
                LEFT JOIN monthly_gross_units units ON units.pnk = p.emag_pnk
                """)) {
            statement.setString(1, STRIP_CHARACTERS);
            statement.setString(2, vendorGroup);
            statement.setString(3, STRIP_CHARACTERS);
            statement.setString(4, category);
            // API dates are Romanian local timestamps, stored without a time zone.
            statement.setTimestamp(5, Timestamp.valueOf(previousMonth.atDay(1).atStartOfDay()));
            statement.setTimestamp(6, Timestamp.valueOf(previousMonth.plusMonths(1).atDay(1).atStartOfDay()));
            statement.setString(7, STRIP_CHARACTERS);
            statement.setString(8, vendorGroup);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    products.add(new CategoryProduct(result.getString("product_code"), result.getString("name"),
                            result.getString("emag_pnk"), result.getBoolean("retracted"),
                            result.getBigDecimal("emag_sale_price_ron"), result.getString("income_profit_tax"),
                            result.getObject("vat_payer", Boolean.class), result.getString("company_name"),
                            readOverrides(result), result.getLong("override_revision"),
                            result.getBigDecimal("emag_commission"),
                            result.getObject("previous_month_gross_units", Long.class),
                            result.getBigDecimal("product_length_mm"), result.getBigDecimal("product_width_mm"),
                            result.getBigDecimal("product_height_mm"), result.getBigDecimal("product_weight_g")));
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
