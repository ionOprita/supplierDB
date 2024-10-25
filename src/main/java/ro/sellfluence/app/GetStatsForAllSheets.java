package ro.sellfluence.app;

import ro.sellfluence.googleapi.SheetsAPI;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;


public class GetStatsForAllSheets {

    private static final String statisticSheetName = "Statistici/luna";
    private static final String setariSheetName = "Setari";

    private static final Logger logger = Logger.getLogger(GetStatsForAllSheets.class.getName());

    public record Statistic(int index, String produs, String pnk, LocalDate lastUpdate, String sheetName) {
    }

    private static final DateTimeFormatter sheetDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private record PNKSheetName(String pnk, String sheetName) {
    }

    /**
     * Retrieve the relevant information from the monthly statistic page for all sheets.
     *
     * @param pnkToSpreadSheet map from PNK to the spreadsheet of the worker assigned to this PNK.
     * @return List with mappings of PNK to product name and last update date.
     */
    public static List<Statistic> getStatistics(Map<String, SheetsAPI> pnkToSpreadSheet) {
        var sheetsToPNK = pnkToSpreadSheet.entrySet().stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));
        return sheetsToPNK.entrySet().stream()
                .flatMap(sheetToPNKList -> {
                            var spreadSheet = sheetToPNKList.getKey();
                            var pnkOnSheet = sheetToPNKList.getValue();
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
                                    .collect(Collectors.joining("\n ", "%s setari PNK mapping:\n ".formatted(spreadSheet.getTitle()), "\n")));
                            List<Statistic> statistics = spreadSheet.getRowsInColumnRange(statisticSheetName, "A", "E").stream()
                                    .skip(6)
                                    .filter(row -> row.size() > 2 && row.getFirst().matches("\\d+") && !row.get(2).isEmpty())
                                    .map(
                                            row -> {
                                                String pnk = row.get(2);
                                                String dateAsString = (row.size() > 4) ? row.get(4) : "";
                                                LocalDate date = (dateAsString.isBlank()) ? LocalDate.now().minusYears(1) : LocalDate.parse(dateAsString, sheetDateFormat);
                                                return new Statistic(
                                                        Integer.parseInt(row.getFirst()),
                                                        row.get(1),
                                                        pnk,
                                                        date,
                                                        pnkToSheetName.get(pnk)
                                                );
                                            }
                                    )
                                    .filter(it -> pnkOnSheet.contains(it.pnk))
                                    .toList();
                            logger.log(INFO, () -> statistics.stream()
                                    .map(st -> "%s %s".formatted(st.pnk, st.produs))
                                    .collect(Collectors.joining("\n ", "%s statistics PNK found\n ".formatted(spreadSheet.getTitle()), "\n")));
                            return statistics.stream();
                        }
                )
                .toList();
    }
}
