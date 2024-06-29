package ro.sellfluence.app;

import ro.sellfluence.googleapi.SheetsAPI;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class GetStatsForAllSheets {

    private static final String statisticSheetName = "Statistici/luna";
    private static final String setariSheetName = "Setari";

    public record Statistic(int index, String produs, String pnk, LocalDate lastUpdate, String sheetName) {
    }

    private static final DateTimeFormatter sheetDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Retrieve the relevant information from the monthly statistic page for all sheets.
     *
     * @param sheets collection of sheets.
     * @return List with mappings of PNK to product name and last update date.
     */
    public static List<Statistic> getStatistics(Collection<SheetsAPI> sheets) {

        return sheets.stream()
                .flatMap(spreadSheet -> {
                            var pnkToSheetName = spreadSheet.getMultipleColumns(setariSheetName, "C", "E").stream()
                                    .skip(2)
                                    .map(row -> {
                                        if (row.get(0) instanceof String pnk && row.get(1) instanceof String sheetName) {
                                            return new String[]{pnk, sheetName};
                                        } else {
                                            return null;
                                        }
                                    })
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toMap(row -> row[0]
                                            , row -> row[1]));

                            return spreadSheet.getRowsInColumnRange(statisticSheetName, "A", "E").stream()
                                    .skip(6)
                                    .filter(row -> row.size() >= 5 && row.getFirst().matches("\\d+") && !row.get(2).isEmpty())
                                    .map(
                                            row -> {
                                                String pnk = row.get(2);
                                                return new Statistic(
                                                        Integer.parseInt(row.getFirst()),
                                                        row.get(1),
                                                        pnk,
                                                        LocalDate.parse(row.get(4), sheetDateFormat),
                                                        pnkToSheetName.get(pnk)
                                                );
                                            }
                                    );
                        }
                )
                .toList();
    }
}
