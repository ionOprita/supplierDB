package ro.sellfluence.db;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current Date Calcul inputs, with one independently versioned row per group and section. */
public final class ProfitCalculatorSettingsTable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfitCalculatorSettingsTable() {
    }

    public record Field(String name, String column) {
    }

    public enum Section {
        GENERAL("general", "profit_calculator_general_settings", List.of(
                new Field("geniusEasyboxRate", "genius_easybox_rate"),
                new Field("monthlyCapitalRate", "monthly_capital_rate"),
                new Field("monthlyInflationRate", "monthly_inflation_rate"),
                new Field("averageDaysPerMonth", "average_days_per_month"),
                new Field("vatRate", "vat_rate"),
                new Field("incomeTaxRate", "income_tax_rate"),
                new Field("profitTaxRate", "profit_tax_rate"),
                new Field("dividendTaxRate", "dividend_tax_rate"),
                new Field("unaccountedStornoRate", "unaccounted_storno_rate"))),
        GENERAL_COSTS("generalCosts", "profit_calculator_general_costs", List.of(
                new Field("packingCostRon", "packing_cost_ron"),
                new Field("courierCostRon", "courier_cost_ron"),
                new Field("geniusCostRon", "genius_cost_ron"),
                new Field("monthlyAccountingCostRon", "monthly_accounting_cost_ron"),
                new Field("warehousePreparationCostRon", "warehouse_preparation_cost_ron"))),
        EMPLOYEES("employees", "profit_calculator_employee_costs", List.of()),
        MISCELLANEOUS("miscellaneous", "profit_calculator_misc_costs", List.of());

        private final String id;
        private final String table;
        private final List<Field> fields;

        Section(String id, String table, List<Field> fields) {
            this.id = id;
            this.table = table;
            this.fields = fields;
        }

        public static Section fromId(String id) {
            for (var section : values()) {
                if (section.id.equals(id)) return section;
            }
            throw new IllegalArgumentException("Unknown Date Calcul section.");
        }

        public List<Field> fields() {
            return fields;
        }

        public boolean hasItems() {
            return fields.isEmpty();
        }
    }

    public record DecimalSection(long revision, Map<String, String> values) {
        private BigDecimal decimal(String name) {
            var value = values.get(name);
            return value == null ? null : new BigDecimal(value);
        }
    }

    public record NamedCost(String label, String amountRon) {
    }

    public record ItemSection(long revision, List<NamedCost> items) {
    }

    public record Data(DecimalSection general, DecimalSection generalCosts, ItemSection employees,
                       ItemSection miscellaneous, Long previousMonthUnits) {
        public OperatingCosts operatingCosts() {
            var employeeTotal = sum(employees.items());
            var miscellaneousTotal = sum(miscellaneous.items());
            return new OperatingCosts(employeeTotal, miscellaneousTotal,
                    perUnit(generalCosts.decimal("monthlyAccountingCostRon"), previousMonthUnits),
                    perUnit(employeeTotal, previousMonthUnits), perUnit(miscellaneousTotal, previousMonthUnits));
        }
    }

    public record OperatingCosts(BigDecimal employeeTotalRon, BigDecimal miscellaneousTotalRon,
                                 BigDecimal accountingCostPerUnitRon, BigDecimal employeeCostPerUnitRon,
                                 BigDecimal miscellaneousCostPerUnitRon) {
    }

    public record CategorySettings(BigDecimal vatRate, BigDecimal warehousePreparationCostRon,
                                   BigDecimal geniusEasyboxRate, BigDecimal geniusCostRon,
                                   BigDecimal accountingCostPerUnitRon, BigDecimal employeeCostPerUnitRon,
                                   BigDecimal miscellaneousCostPerUnitRon, BigDecimal monthlyCapitalRate,
                                   BigDecimal turnoverTaxVatRate, BigDecimal courierCostRon, BigDecimal incomeTaxRate,
                                   BigDecimal packingCostRon, BigDecimal monthlyInflationRate) {
        public CategorySettings(BigDecimal vatRate, BigDecimal warehousePreparationCostRon,
                                BigDecimal geniusEasyboxRate, BigDecimal geniusCostRon,
                                BigDecimal accountingCostPerUnitRon, BigDecimal employeeCostPerUnitRon,
                                BigDecimal miscellaneousCostPerUnitRon, BigDecimal monthlyCapitalRate,
                                BigDecimal turnoverTaxVatRate, BigDecimal courierCostRon, BigDecimal incomeTaxRate) {
            this(vatRate, warehousePreparationCostRon, geniusEasyboxRate, geniusCostRon, accountingCostPerUnitRon,
                    employeeCostPerUnitRon, miscellaneousCostPerUnitRon, monthlyCapitalRate, turnoverTaxVatRate,
                    courierCostRon, incomeTaxRate, null, null);
        }

        public CategorySettings(BigDecimal vatRate, BigDecimal warehousePreparationCostRon,
                                BigDecimal geniusEasyboxRate, BigDecimal geniusCostRon,
                                BigDecimal accountingCostPerUnitRon, BigDecimal employeeCostPerUnitRon,
                                BigDecimal miscellaneousCostPerUnitRon) {
            this(vatRate, warehousePreparationCostRon, geniusEasyboxRate, geniusCostRon, accountingCostPerUnitRon,
                    employeeCostPerUnitRon, miscellaneousCostPerUnitRon, null, null, null, null);
        }

        public static CategorySettings empty() {
            return new CategorySettings(null, null, null, null, null, null, null);
        }
    }

    public record SharedSettings(long revision, BigDecimal turnoverTaxVatRate) {
    }

    public record Change(String vendorGroup, Section section, long expectedRevision,
                         Map<String, BigDecimal> values, List<NamedCost> items) {
    }

    public static final class ConflictException extends SQLException {
        public ConflictException() {
            super("Saved settings changed. Reload the section before saving again.");
        }
    }

    static Data read(Connection db, String vendorGroup, YearMonth previousMonth) throws SQLException {
        requireVendorGroup(db, vendorGroup);
        return readData(db, vendorGroup, previousMonth);
    }

    private static Data readData(Connection db, String vendorGroup, YearMonth previousMonth) throws SQLException {
        return new Data(readDecimals(db, vendorGroup, Section.GENERAL),
                readDecimals(db, vendorGroup, Section.GENERAL_COSTS), readItems(db, vendorGroup, Section.EMPLOYEES),
                readItems(db, vendorGroup, Section.MISCELLANEOUS), readPreviousMonthUnits(db, vendorGroup, previousMonth));
    }

    static CategorySettings readCategorySettings(Connection db, String vendorGroup, YearMonth previousMonth)
            throws SQLException {
        var data = readData(db, vendorGroup, previousMonth);
        var costs = data.operatingCosts();
        return new CategorySettings(data.general().decimal("vatRate"), data.generalCosts().decimal("warehousePreparationCostRon"),
                data.general().decimal("geniusEasyboxRate"), data.generalCosts().decimal("geniusCostRon"),
                costs.accountingCostPerUnitRon(), costs.employeeCostPerUnitRon(), costs.miscellaneousCostPerUnitRon(),
                data.general().decimal("monthlyCapitalRate"), readSharedSettings(db).turnoverTaxVatRate(),
                data.generalCosts().decimal("courierCostRon"), data.general().decimal("incomeTaxRate"),
                data.generalCosts().decimal("packingCostRon"), data.general().decimal("monthlyInflationRate"));
    }

    static SharedSettings readSharedSettings(Connection db) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT revision, turnover_tax_vat_rate FROM profit_calculator_shared_settings WHERE id = 1
                """); var row = statement.executeQuery()) {
            if (!row.next()) throw new SQLException("Shared Date Calcul settings are missing.");
            return new SharedSettings(row.getLong("revision"), row.getBigDecimal("turnover_tax_vat_rate"));
        }
    }

    static void saveSharedSettings(Connection db, long expectedRevision, BigDecimal turnoverTaxVatRate)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                UPDATE profit_calculator_shared_settings
                SET turnover_tax_vat_rate = ?, revision = revision + 1
                WHERE id = 1 AND revision = ?
                """)) {
            statement.setBigDecimal(1, turnoverTaxVatRate);
            statement.setLong(2, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConflictException();
        }
    }

    private static BigDecimal sum(List<NamedCost> items) {
        var sum = BigDecimal.ZERO;
        for (var item : items) {
            // Sheet SUM ignores blank amounts, and an empty section totals zero.
            if (item.amountRon() != null) sum = sum.add(new BigDecimal(item.amountRon()));
        }
        return sum;
    }

    private static BigDecimal perUnit(BigDecimal amount, Long units) {
        return amount == null || units == null || units <= 0 ? null
                : amount.divide(BigDecimal.valueOf(units), MathContext.DECIMAL128);
    }

    private static DecimalSection readDecimals(Connection db, String vendorGroup, Section section) throws SQLException {
        var select = "SELECT * FROM " + section.table + " WHERE vendor_group = ?";
        try (var statement = db.prepareStatement(select)) {
            statement.setString(1, vendorGroup);
            try (var row = statement.executeQuery()) {
                var present = row.next();
                var values = new LinkedHashMap<String, String>();
                for (var field : section.fields) {
                    var value = present ? row.getBigDecimal(field.column()) : null;
                    values.put(field.name(), value == null ? null : value.toPlainString());
                }
                return new DecimalSection(present ? row.getLong("revision") : 0, Collections.unmodifiableMap(values));
            }
        }
    }

    private static ItemSection readItems(Connection db, String vendorGroup, Section section) throws SQLException {
        var select = "SELECT revision, items FROM " + section.table + " WHERE vendor_group = ?";
        try (var statement = db.prepareStatement(select)) {
            statement.setString(1, vendorGroup);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return new ItemSection(0, List.of());
                try {
                    var items = MAPPER.readValue(row.getString("items"), NamedCost[].class);
                    return new ItemSection(row.getLong("revision"), List.of(items));
                } catch (RuntimeException error) {
                    throw new SQLException("Invalid stored Date Calcul cost items.", error);
                }
            }
        }
    }

    private static Long readPreviousMonthUnits(Connection db, String vendorGroup, YearMonth previousMonth) throws SQLException {
        // C8 uses the product owner's group across all selling accounts. Keep every qualifying line, including withdrawn products.
        try (var statement = db.prepareStatement("""
                SELECT CASE WHEN COUNT(line.quantity) = COUNT(*)
                            THEN COALESCE(SUM(line.quantity), 0)::bigint END AS gross_units
                FROM emag_order sale
                JOIN product_in_order line ON line.emag_order_surrogate_id = sale.surrogate_id
                WHERE sale.status = 4 AND sale.date >= ? AND sale.date < ?
                  AND line.part_number_key IN (
                      SELECT catalog.emag_pnk
                      FROM product catalog JOIN vendor owner ON owner.id = catalog.vendor
                      WHERE btrim(owner.vendor_group, ?) = ? AND btrim(catalog.emag_pnk, ?) <> ''
                  )
                """)) {
            // The mirror stores Romanian local order timestamps without a time zone.
            statement.setTimestamp(1, Timestamp.valueOf(previousMonth.atDay(1).atStartOfDay()));
            statement.setTimestamp(2, Timestamp.valueOf(previousMonth.plusMonths(1).atDay(1).atStartOfDay()));
            statement.setString(3, ProfitCalculatorTable.STRIP_CHARACTERS);
            statement.setString(4, vendorGroup);
            statement.setString(5, ProfitCalculatorTable.STRIP_CHARACTERS);
            try (var row = statement.executeQuery()) {
                row.next();
                return row.getObject("gross_units", Long.class);
            }
        }
    }

    static void save(Connection db, Change change) throws SQLException {
        requireVendorGroup(db, change.vendorGroup());
        var section = change.section();
        var columns = section.hasItems() ? List.of("items") : section.fields.stream().map(Field::column).toList();
        var placeholders = section.hasItems() ? List.of("?::jsonb") : Collections.nCopies(columns.size(), "?");
        var insert = change.expectedRevision() == 0;
        final String sql;
        if (insert) {
            sql = "INSERT INTO " + section.table + " (" + String.join(", ", columns) + ", vendor_group, revision)"
                    + " VALUES (" + String.join(", ", placeholders) + ", ?, 1) ON CONFLICT (vendor_group) DO NOTHING";
        } else {
            var assignments = new ArrayList<String>();
            for (int i = 0; i < columns.size(); i++) assignments.add(columns.get(i) + " = " + placeholders.get(i));
            sql = "UPDATE " + section.table + " SET " + String.join(", ", assignments)
                    + ", revision = revision + 1 WHERE vendor_group = ? AND revision = ?";
        }
        try (var statement = db.prepareStatement(sql)) {
            var offset = 1;
            if (section.hasItems()) {
                statement.setString(offset++, MAPPER.writeValueAsString(change.items()));
            } else {
                for (var field : section.fields) statement.setBigDecimal(offset++, change.values().get(field.name()));
            }
            statement.setString(offset++, change.vendorGroup());
            if (!insert) statement.setLong(offset, change.expectedRevision());
            if (statement.executeUpdate() != 1) throw new ConflictException();
        }
    }

    private static void requireVendorGroup(Connection db, String vendorGroup) throws SQLException {
        try (var statement = db.prepareStatement("SELECT 1 FROM vendor WHERE btrim(vendor_group, ?) = ? LIMIT 1")) {
            statement.setString(1, ProfitCalculatorTable.STRIP_CHARACTERS);
            statement.setString(2, vendorGroup);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Unknown vendor group.");
            }
        }
    }
}
