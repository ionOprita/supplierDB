package ro.sellfluence.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Replacement rate snapshot used by both saved calculations and previews. */
final class ProfitCalculatorReplacementRateTable {
    private ProfitCalculatorReplacementRateTable() {
    }

    static Map<String, BigDecimal> read(Connection db, List<ProfitCalculatorTable.CategoryProduct> products)
            throws SQLException {
        var pnks = products.stream()
                .filter(product -> !product.retracted() && product.pnk() != null && !product.pnk().isBlank())
                .map(ProfitCalculatorTable.CategoryProduct::pnk).distinct().toArray(String[]::new);
        if (pnks.length == 0) return Map.of();
        var rates = new HashMap<String, BigDecimal>();
        var keys = db.createArrayOf("text", pnks);
        try (var statement = db.prepareStatement("""
                SELECT pnk, rate FROM profit_calculator_replacement_rate WHERE pnk = ANY(?)
                """)) {
            statement.setArray(1, keys);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) rates.put(rows.getString("pnk"), rows.getBigDecimal("rate"));
            }
        } finally {
            keys.free();
        }
        return rates;
    }

    static int replace(Connection db, Map<String, BigDecimal> rates, Instant capturedAt) throws SQLException {
        if (rates.isEmpty()) throw new IllegalArgumentException("Replacement rate snapshot is empty.");
        var captured = Objects.requireNonNull(capturedAt).atOffset(ZoneOffset.UTC);
        try (var statement = db.createStatement()) {
            // Serialize manual and scheduled refreshes. Readers can continue using the committed snapshot.
            statement.execute("LOCK TABLE profit_calculator_replacement_rate IN SHARE ROW EXCLUSIVE MODE");
            try (var row = statement.executeQuery("SELECT max(captured_at) FROM profit_calculator_replacement_rate")) {
                row.next();
                var previous = row.getObject(1, OffsetDateTime.class);
                if (previous != null && !previous.isBefore(captured)) return 0;
            }
            statement.executeUpdate("DELETE FROM profit_calculator_replacement_rate");
        }
        try (var statement = db.prepareStatement("""
                INSERT INTO profit_calculator_replacement_rate (pnk, rate, captured_at) VALUES (?, ?, ?)
                """)) {
            for (var entry : rates.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setBigDecimal(2, entry.getValue());
                statement.setObject(3, captured);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return rates.size();
    }
}
