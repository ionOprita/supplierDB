package ro.sellfluence.apphelper;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Logs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static ro.sellfluence.support.UsefulMethods.sheetToLocalDate;

/**
 * Retrieve the relevant information from the monthly statistic page for all employee sheets.
 */
public class GetStatsForAllSheets {

    private static final String statisticSheetName = "Statistici/luna";
    private static final String setariSheetName = "Setari";

    private static final Logger logger = Logs.getFileLogger("GetStatsForAllSheets", INFO, 10, 1_000_000);

    public record Statistic(String pnk, LocalDate lastUpdate, String sheetName) {
    }

    private static final DateTimeFormatter sheetDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private record PNKSheetName(String pnk, String sheetName) {
    }

    /**
     * Retrieve the relevant information from the monthly statistic page for all sheets.
     *
     * @param pnkToSpreadSheet map from PNK to the spreadsheet of the worker assigned to this PNK.
     * @return List with mappings of PNK to the product name and last update date.
     */
    public static List<Statistic> getStatistics(Map<String, SheetsAPI> pnkToSpreadSheet, Map<String, ProductTable.ProductInfo> productsByPNK) {
        var sheetsToPNK = pnkToSpreadSheet.entrySet().stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));
        return sheetsToPNK.entrySet().stream()
                .flatMap(sheetToPNKList -> {
                            var spreadSheet = sheetToPNKList.getKey();
                            var pnkOnSheet = sheetToPNKList.getValue();
                            // This reads the setari sheet so that we can map from PNK to the tab within the spreadsheet.
                            logger.log(INFO, () -> "Read from %s %s columns C and E\n ".formatted(spreadSheet.getSpreadSheetName(), setariSheetName));
                            // TODO: Avoid reading columns with a formula.
                            var pnkToSheetName = spreadSheet.getMultipleColumns(setariSheetName, "C", "E").stream()
                                    .skip(2)
                                    .map(row -> {
                                        if (row.get(0) instanceof String pnk && row.get(1) instanceof String sheetName) {
                                            return new PNKSheetName(pnk, sheetName);
                                        } else {
                                            return null;
                                        }
                                    })
                                    .filter(Objects::nonNull)
                                    .filter(it -> pnkOnSheet.contains(it.pnk))
                                    .collect(Collectors.toMap(it -> it.pnk, it -> it.sheetName));
                            logger.log(INFO, () -> pnkToSheetName.entrySet().stream()
                                    .map(e -> "%s -> %s".formatted(e.getKey(), e.getValue()))
                                    .sorted()
                                    .collect(Collectors.joining("\n ", "%s setari PNK mapping:\n ".formatted(spreadSheet.getSpreadSheetName()), "\n")));
                            // This reads from the statistici/luna sheet so that we can get the date of the last entry.
                            List<Statistic> statistics = spreadSheet.getMultipleColumns(statisticSheetName, "C", "E").stream()
                                    .skip(7)
                                    .filter(row -> row.getFirst() instanceof String s && !s.isEmpty() && row.get(1) instanceof BigDecimal)
                                    .map(
                                            row -> {
                                                var pnk = (String) row.getFirst();
                                                var date = sheetToLocalDate((BigDecimal)row.get(1));
                                                return new Statistic(
                                                        pnk,
                                                        date,
                                                        pnkToSheetName.get(pnk)
                                                );
                                            }
                                    )
                                    .filter(it -> pnkOnSheet.contains(it.pnk))
                                    .toList();
                            logger.log(INFO, () -> statistics.stream()
                                    .map(st -> "%s".formatted(st.pnk))
                                    .collect(Collectors.joining("\n ", "%s statistics PNK found\n ".formatted(spreadSheet.getSpreadSheetName()), "\n")));
                            return statistics.stream();
                        }
                )
                .toList();
    }
}