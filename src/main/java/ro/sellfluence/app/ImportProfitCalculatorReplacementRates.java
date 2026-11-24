package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;

/** Reads the central replacement-rate overview into PostgreSQL. */
public final class ImportProfitCalculatorReplacementRates {
    private static final Logger logger = Logs.getConsoleAndFileLogger(
            "ImportProfitCalculatorReplacementRates", INFO, 10, 1_000_000);
    private static final String spreadsheetName = "Cent. - Ret. Sto. Ref. Inl.";
    private static final String sheetName = "(GLB) Cent. Prod. (%)";
    private static final int firstDataRow = 8;
    private static final int pnkColumn = 4;
    private static final int rateColumn = 10;

    private ImportProfitCalculatorReplacementRates() {
    }

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var capturedAt = Instant.now();
        var rates = readRates();
        var db = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        save(db, rates, capturedAt);
    }

    public static int refresh(EmagMirrorDB db, Clock clock) throws SQLException {
        Objects.requireNonNull(db, "db");
        var capturedAt = clock.instant();
        return save(db, readRates(), capturedAt);
    }

    private static Map<String, BigDecimal> readRates() {
        var sheet = SheetsAPI.getSpreadSheetByName(defaultGoogleApp, spreadsheetName);
        if (sheet == null) throw new IllegalStateException("Could not find spreadsheet " + spreadsheetName);
        var grid = sheet.getSheetGrids().stream()
                .filter(tab -> tab.title().equals(sheetName))
                .findFirst().orElseThrow(() -> new IllegalStateException("Could not find the central replacement-rate tab."));
        if (grid.rows() < firstDataRow || grid.columns() < rateColumn) {
            throw new IllegalStateException("The central replacement-rate tab has unexpected bounds.");
        }
        return parse(sheet.getBoundedValues(sheetName, firstDataRow, grid.rows(), pnkColumn, rateColumn, false));
    }

    private static int save(EmagMirrorDB db, Map<String, BigDecimal> rates, Instant capturedAt) throws SQLException {
        var saved = db.replaceProfitCalculatorReplacementRates(rates, capturedAt);
        logger.info(() -> saved == 0 ? "Kept the current profit calculator replacement-rate snapshot."
                : "Saved " + saved + " profit calculator replacement rates.");
        return saved;
    }

    /** Maps source columns D and J. A blank rate has no entry in the snapshot. */
    static Map<String, BigDecimal> parse(List<List<Object>> rows) {
        var rates = new LinkedHashMap<String, BigDecimal>();
        var seen = new HashSet<String>();
        for (var i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var rowNumber = firstDataRow + i;
            var pnkCell = cell(row, 0);
            var rateCell = cell(row, rateColumn - pnkColumn);
            if (isBlank(pnkCell) && isBlank(rateCell)) continue;
            if (!(pnkCell instanceof String pnk) || pnk.isBlank() || pnk.startsWith("#")) {
                throw new IllegalArgumentException("Missing or invalid PNK in central overview row " + rowNumber + '.');
            }
            if (!seen.add(pnk)) {
                throw new IllegalArgumentException("Duplicate PNK in central overview row " + rowNumber + '.');
            }
            if (!isBlank(rateCell)) rates.put(pnk, parseRate(rateCell, rowNumber));
        }
        if (rates.isEmpty()) throw new IllegalArgumentException("The central overview contains no replacement rates.");
        return Map.copyOf(rates);
    }

    private static BigDecimal parseRate(Object value, int rowNumber) {
        if (value instanceof Number number) {
            try {
                var rate = new BigDecimal(number.toString());
                if (rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0) return rate;
            } catch (NumberFormatException ignored) {
                // Non-finite numbers are invalid source values.
            }
        }
        throw new IllegalArgumentException("Invalid replacement rate in central overview row " + rowNumber + '.');
    }

    private static Object cell(List<Object> row, int index) {
        return index < row.size() ? row.get(index) : null;
    }

    private static boolean isBlank(Object cell) {
        return cell == null || cell instanceof String text && text.isBlank();
    }
}
