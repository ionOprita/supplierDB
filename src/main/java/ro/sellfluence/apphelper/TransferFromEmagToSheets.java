package ro.sellfluence.apphelper;

import ro.sellfluence.googleapi.SheetsAPI;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class TransferFromEmagToSheets {

    private static final Logger logger = Logger.getLogger(TransferFromEmagToSheets.class.getName());

    private final String appName;
    private final String overviewSpreadSheetName;
    private final String overviewSheetName;
    private Map<String, SheetsAPI> pnkToSpreadSheet;
    private Set<String> relevantProducts;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Map<String, GetStatsForAllSheets.Statistic> pnkToStatistic;

    public TransferFromEmagToSheets(String appName, String spreadSheetName, String overviewSheetName) {
        this.appName = appName;
        this.overviewSpreadSheetName = spreadSheetName;
        this.overviewSheetName = overviewSheetName;
    }

    public void transferFromEmagToSheet(String... emagAccounts) {
        loadOverview();
        if (relevantProducts.isEmpty()) {
            throw new RuntimeException("No valid products found for accounts %s.".formatted(emagAccounts));
        }
        loadAllStatistics();
        for (String emagAccount : emagAccounts) {
            final var emagEntries = GetCustomerData.getByProduct(startTime, endTime, emagAccount);
            emagEntries.forEach((pnk, orderEntries) -> {
                if (relevantProducts.contains(pnk)) {
                    final var statistic = pnkToStatistic.get(pnk);
                    if (statistic != null) {
                        final var productName = statistic.produs();
                        final var rowsToAdd = orderEntries.stream()
                                .filter(emagEntry -> emagEntry.orderDate().isAfter(statistic.lastUpdate().plusDays(1).atStartOfDay()))
                                // Sort by date and within the same date by order ID.
                                .sorted(comparing(EmployeeSheetData::orderDate).thenComparing(EmployeeSheetData::orderId))
                                .map(data -> mapEmagToRow(data, productName))
                                .toList();
                        if (!rowsToAdd.isEmpty()) {
                            addToSheet(pnk, rowsToAdd);
                        }
                    } else {
                        logger.log(WARNING, () -> "Product with PNK %s doesn't have an entry in statistici/lune or setari in the spreadsheet %s."
                                .formatted(pnk, pnkToSpreadSheet.get(pnk).getTitle()));
                    }
                } else {
                    logger.log(
                            WARNING,
                            () -> "Following order entries aren't stored because no sheet found with PNK %s: %s.".formatted(
                                    pnk,
                                    orderEntries.stream()
                                            .map(EmployeeSheetData::orderId)
                                            .collect(Collectors.joining(","))
                            )
                    );
                }
            });
        }
    }

    private void loadOverview() {
        var overviewResult = new GetOverview(appName, overviewSpreadSheetName, overviewSheetName).getWorkSheets();
        logger.log(INFO, () -> overviewResult.stream()
                .map(sheetData -> "%s -> %s".formatted(sheetData.pnk(), sheetData.spreadSheetName()))
                .sorted()
                .collect(Collectors.joining("\n ", "PNK to Spreadsheet mapping:\n ", "\n")));
        pnkToSpreadSheet = overviewResult.stream()
                .collect(Collectors.toMap(GetOverview.SheetData::pnk, product -> SheetsAPI.getSpreadSheetById(appName, product.spreadSheetId())));
        var nullPNK = pnkToSpreadSheet.entrySet().stream().filter(it -> it.getValue() == null).map(Map.Entry::getKey).toList();
        if (!nullPNK.isEmpty()) {
            logger.warning("Spreadsheet lookup issue for PNKs %s".formatted(nullPNK));
        }
        relevantProducts = pnkToSpreadSheet.keySet();
    }

    private void loadAllStatistics() {
        var statisticsFromAllSheets = GetStatsForAllSheets.getStatistics(pnkToSpreadSheet).stream()
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

    private static List<Object> mapEmagToRow(EmployeeSheetData data, String productName) {
        var row = new ArrayList<>();
        row.add(data.orderId());
        row.add(data.quantity());
        row.add(String.format(Locale.US, "%.2f", data.price().doubleValue() * 1.19)); // TODO: Proper handling of VAT required
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

    /**
     * Add new orders for a product to its assigned sheet.
     *
     * @param pnk       Product identification
     * @param rowsToAdd Additional rows.
     */
    private void addToSheet(String pnk, List<List<Object>> rowsToAdd) {
        var sheet = pnkToSpreadSheet.get(pnk);
        var sheetName = pnkToStatistic.get(pnk).sheetName();
        List<String> orderIdColumn = sheet.getColumn(sheetName, "A");
        var mapOrderToColumn = new HashMap<String, List<Integer>>();
        for (var columnNumber = 0; columnNumber < orderIdColumn.size(); columnNumber++) {
            var orderId = orderIdColumn.get(columnNumber);
            var columnList = mapOrderToColumn.getOrDefault(orderId, new ArrayList<>());
            columnList.add(columnNumber);
            mapOrderToColumn.put(orderId, columnList);
        }
        mapOrderToColumn.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(entry ->
                        logger.log(
                                WARNING,
                                "Found order %s in multiple columns: %s".formatted(
                                        entry.getKey(),
                                        String.join(
                                                ", ",
                                                entry.getValue().stream()
                                                        .map(columnIndex -> Integer.toString(columnIndex + 1))
                                                        .toList()
                                        )
                                )
                        )
                );
        var lastRowNumber = orderIdColumn.size();
        var processedOrderIds = new HashSet<>(orderIdColumn);   // Drops duplicates.
        var withoutDuplicates = rowsToAdd.stream()
                .filter(row -> !processedOrderIds.contains(((String) row.getFirst())))
                .toList();
        System.out.printf("Adding %d rows after row %d to tab %s of some spreadsheet.%n", withoutDuplicates.size(), lastRowNumber, sheetName);
        var pnksInSheet = sheet.getColumn(sheetName, "G").stream().skip(3).filter(x -> !x.isBlank()).collect(Collectors.toSet());
        if (pnksInSheet.size() > 1) {
            logger.log(WARNING, "Sheet '%s' in Spreadsheet '%s' contains multiple PNKs in column 7: %s.".formatted(sheetName, sheet.getTitle(), pnksInSheet));
        } else if (pnksInSheet.size() == 1 && !Objects.equals(pnksInSheet.iterator().next(), pnk)) {
            logger.log(WARNING, "Expected PNK '%s', but Sheet '%s' in Spreadsheet '%s' contains different PNK in column 7: %s.".formatted(pnk, sheetName, sheet.getTitle(), pnksInSheet));
        } else {
            sheet.updateRange("%s!A%d:N%d".formatted(sheetName, lastRowNumber + 1, lastRowNumber + withoutDuplicates.size()), withoutDuplicates);
        }
    }
}