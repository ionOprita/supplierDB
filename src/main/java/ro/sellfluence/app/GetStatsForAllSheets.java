package ro.sellfluence.app;

import ro.sellfluence.googleapi.SheetsAPI;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

public class GetStatsForAllSheets {

    private static final String statisticSheetName = "Statistici/luna";

    public record Statistic(int index, String produs, String pnk, LocalDate lastUpdate) { }

    private static final DateTimeFormatter sheetDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Retrieve the relevant information from the monthly statistic page for all sheets.
     *
     * @param sheets collection of sheets.
     * @return List with mappings of PNK to product name and last update date.
     */
    public static List<Statistic> getStatistics(Collection<SheetsAPI> sheets) {
        return sheets.stream()
                .flatMap(spreadSheet ->
                        spreadSheet.getRowsInColumnRange(statisticSheetName, "A", "E").stream()
                                .skip(6)
                                .filter(row -> row.size() >= 5 && row.getFirst().matches("\\d+") && !row.get(2).isEmpty())
                                .map(
                                        row -> new Statistic(
                                                Integer.parseInt(row.getFirst()),
                                                row.get(1),
                                                row.get(2),
                                                LocalDate.parse(row.get(4), sheetDateFormat)
                                        )
                                )
                )
                .toList();
    }
}
