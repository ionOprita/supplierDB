package ro.sellfluence.app;

import ro.sellfluence.app.GetCustomerData.SheetData;
import ro.sellfluence.googleapi.SheetsAPI;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheet;

//TODO: Check column 7 of a sheet before inserting, to verify that PNK matches.
public class TransferFromEmagToSheets {

    private final String appName;
    private final String overviewSheetName;
    private Map<String, SheetsAPI> pnkToSpreadSheet;
    private List<SheetsAPI> requiredSpreadsheets;
    private Set<String> relevantProducts;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, GetStatsForAllSheets.Statistic> pnkToStatistic;

    public TransferFromEmagToSheets(String appName, String overviewSheetName) {
        this.appName = appName;
        this.overviewSheetName = overviewSheetName;

    }

    public void transferFromEmagToSheet() {
        loadOverview();
        loadAllStatistics();
        final var emagEntries = GetCustomerData.getByProduct(startTime, endTime);
        emagEntries.forEach((pnk, orderEntries) -> {
            if (relevantProducts.contains(pnk)) {
                final var statistic = pnkToStatistic.get(pnk);
                final var productName = statistic.produs();
                final var rowsToAdd = orderEntries.stream()
                        .filter(emagEntry -> emagEntry.orderDate().isAfter(statistic.lastUpdate().plusDays(1).atStartOfDay()))
                        // Sort by date and within the same date by order ID.
                        .sorted(comparing(SheetData::orderDate).thenComparing(SheetData::orderId))
                        .map(data -> mapEmagToRow(data, productName))
                        .toList();
                if (!rowsToAdd.isEmpty()) {
                    addToSheet(pnk, rowsToAdd);
                }
            } else {
                //TODO: Print warning if PNK in emag is not on sheet.
            }
        });
    }

    private void loadOverview() {
        var overviewResult = new GetOverview(appName, overviewSheetName).getWorkSheets();
        pnkToSpreadSheet = overviewResult.stream()
                .collect(Collectors.toMap(GetOverview.SheetData::pnk, product -> getSpreadSheet(appName, product.spreadSheetId())));
        requiredSpreadsheets = pnkToSpreadSheet.values().stream().distinct().toList();
        relevantProducts = pnkToSpreadSheet.keySet();

    }

    private void loadAllStatistics() {
        var statisticsFromAllSheets = GetStatsForAllSheets.getStatistics(requiredSpreadsheets).stream()
                .filter(it -> relevantProducts.contains(it.pnk()))
                .toList();
        var smallestUpdateDate = statisticsFromAllSheets.stream()
                .min(comparing(GetStatsForAllSheets.Statistic::lastUpdate))
                .get()
                .lastUpdate();
        startTime = smallestUpdateDate.plusDays(1).atStartOfDay();
        endTime = LocalDate.now().minusDays(13).atStartOfDay();
        pnkToStatistic = statisticsFromAllSheets.stream()
                .collect(Collectors.toMap(GetStatsForAllSheets.Statistic::pnk, statistic -> statistic));
    }

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static List<Object> mapEmagToRow(SheetData data, String productName) {
        var row = new ArrayList<>();
        row.add(data.orderId());
        row.add(data.quantity());
        //TODO: Check if .2f is rounding in a sensible way.
        row.add("%.2f".formatted(data.price().doubleValue()*1.19));
        row.add(data.isCompany() ? "Yes" : "No");
        row.add(data.orderDate().format(dateFormat));
        row.add(productName);
        row.add(data.partNumberKey());
        row.add(data.billingName());
        row.add(data.billingPhone());
        row.add(data.userName());
        row.add(data.clientName());
        row.add(data.clientPhone());
        row.add(data.deliveryAddress());
        row.add(data.deliveryMode());
        return row;
    }

    private void addToSheet(String pnk, List<List<Object>> rowsToAdd) {
        var sheet = pnkToSpreadSheet.get(pnk);
        //TODO: Instead of mapping from index maybe a better logic which is based on names.
        var sheetName = sheet.getNameFromIndex(pnkToStatistic.get(pnk).index());
        //TODO: Store the whole column A which is order number in a set and then check the rowsToAdd if the number
        // is already there, and exclude these (duplicates) and write a warning.
        var lastRowNumber = sheet.getLastRow(sheetName, "A");
        System.out.printf("Adding %d rows after row %d to tab %s of some spreadsheet.%n", rowsToAdd.size(), lastRowNumber, sheetName);
        sheet.updateRange("%s!A%d:N%d".formatted(sheetName, lastRowNumber + 1, lastRowNumber + rowsToAdd.size()), rowsToAdd);
    }
}
