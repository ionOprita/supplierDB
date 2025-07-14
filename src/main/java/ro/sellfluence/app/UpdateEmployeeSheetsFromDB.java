package ro.sellfluence.app;

import ro.sellfluence.apphelper.EmployeeSheetData;
import ro.sellfluence.apphelper.GetStatsForAllSheets;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
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
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Update the employee sheets with new orders for them to call users for feedback.
 */
public class UpdateEmployeeSheetsFromDB {

    private static final Logger logger = Logs.getConsoleAndFileLogger("UpdateEmployeeSheetsWarnings", WARNING, 10, 1_000_000);
    private static final Logger progressLogger = Logs.getConsoleLogger("UpdateEmployeeSheetsProgress", INFO);

    private final String appName;
    private Map<String, SheetsAPI> pnkToSpreadSheet;
    private Set<String> relevantProducts;
    private final LocalDateTime endTime = LocalDate.now().minusDays(13).atStartOfDay();
    private Map<String, GetStatsForAllSheets.Statistic> pnkToStatistic;


    public UpdateEmployeeSheetsFromDB(String appName) {
        this.appName = appName;
    }

    public static void main(String[] args) {
        try {
            var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
            var updateSheets = new UpdateEmployeeSheetsFromDB("sellfluence1");
            updateSheets.transferFromDBToSheet(mirrorDB);
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main method that will transfer new orders to the appropriate employee sheet depending on the products.
     *
     * @param mirrorDB database to read.
     * @throws SQLException on database errors.
     */
    public void transferFromDBToSheet(EmagMirrorDB mirrorDB) throws SQLException {
        loadOverview(mirrorDB);
        if (relevantProducts.isEmpty()) {
            throw new RuntimeException("No valid product found in the database.");
        }
        loadAllStatistics();
        // Map OrderId to spreadsheet containing it.
        var existingOrderAssignments = new HashMap<String, SheetsAPI>();
        // Map SheetData to OrderId
        var newAssignments = new HashMap<EmployeeSheetData, String>();
        for (var entry : pnkToStatistic.entrySet()) {
            var pnk = entry.getKey();
            var statistic = entry.getValue();
            progressLogger.log(INFO, () -> "Processing statistic for PNK %s: %s".formatted(pnk, statistic));
            var spreadSheet = pnkToSpreadSheet.get(pnk);
            if (spreadSheet == null) {
                logger.log(WARNING, "No spreadsheet found for PNK %s".formatted(pnk));
            } else {
                progressLogger.log(INFO, () -> "Read orders from the spreadsheet %s tab %s for PNK %s.".formatted(spreadSheet.getSpreadSheetName(), statistic.sheetName(), pnk));
                accumulateExistingOrders(spreadSheet, statistic.sheetName(), existingOrderAssignments);
                var startTime = statistic.lastUpdate().atStartOfDay();
                var newOrdersForProduct = mirrorDB.readOrderData(pnk, startTime, endTime).stream().filter(it -> it.quantity() > 0).toList();
                for (EmployeeSheetData it : newOrdersForProduct) {
                    newAssignments.put(it, statistic.sheetName());
                }
            }
        }
        progressLogger.log(INFO, () -> "Existing orders: " + existingOrderAssignments.size());
        progressLogger.log(INFO, () -> "New orders: " + newAssignments.size());
        var newOrdersWithoutOldOnes = newAssignments.entrySet().stream()
                .filter(it -> !existingOrderAssignments.containsKey(it.getKey().orderId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        progressLogger.log(INFO, () -> "New orders without old ones: " + newOrdersWithoutOldOnes.size());
        var groupedByOrderId = newAssignments.keySet().stream()
                .collect(Collectors.groupingBy(EmployeeSheetData::orderId));
        var onlyDuplicates = groupedByOrderId.entrySet().stream()
                .filter(it -> it.getValue().size() > 1)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,                                     // preserve the orderId as the key
                        Map.Entry::getValue                                    // preserve the List<EmployeeSheetData>
                ));
        progressLogger.log(INFO, () -> "Only duplicates: " + onlyDuplicates.size());
        // Set the called flag on all entries except for the first one.
        var withCalledSetOnDuplicates = groupedByOrderId.entrySet().stream()
                .map(it -> {
                    var modifiedList = new ArrayList<EmployeeSheetData>();
                    int i = 0;
                    for (EmployeeSheetData sheetData : it.getValue()) {
                        EmployeeSheetData employeeSheetData = sheetData.withCalledSet(i != 0);
                        modifiedList.add(employeeSheetData);
                        i++;
                    }
                    return new AbstractMap.SimpleEntry<>(it.getKey(), modifiedList) {
                    };
                }).collect(Collectors.toMap(Map.Entry::getKey, // preserve the orderId as the key
                        Map.Entry::getValue));
        var reorderedByEmployeeSheet = withCalledSetOnDuplicates.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(employeeSheetData -> pnkToSpreadSheet.get(employeeSheetData.partNumberKey())
                ));
        for (Map.Entry<SheetsAPI, List<EmployeeSheetData>> entry : reorderedByEmployeeSheet.entrySet()) {
            var sheet = entry.getKey();
            var groupedBySheet = entry.getValue().stream().collect(Collectors.groupingBy(it ->pnkToStatistic.get(it.partNumberKey()).sheetName()));
            for (var entry1 : groupedBySheet.entrySet()) {
                var sheetName = entry1.getKey();
                var orders = entry1.getValue();
                if (!orders.isEmpty()) {
                    var rowData = orders.stream()
                            .sorted(comparing(EmployeeSheetData::orderDate))
                            .map(UpdateEmployeeSheetsFromDB::mapEmagToRow)
                            .toList();
                    addToSheet(orders.getFirst().partNumberKey(), sheet, sheetName,  rowData);
                }
            }
        }
        //TODO: We currently look at orders but shouldn't we rather look at customers?
    }

    /**
     * Read the ID of the orders already recorded in this sheet.
     *
     * @param sheet                    spreadsheet.
     * @param sheetName                name of the sheet within the spreadsheet.
     * @param existingOrderAssignments map of existing order IDs to sheets.
     */
    private static void accumulateExistingOrders(SheetsAPI sheet, final String sheetName, HashMap<String, SheetsAPI> existingOrderAssignments) {
        var orderIdColumn = sheet.getColumn(sheetName, "A").stream().skip(4).toList();
        for (var orderId : orderIdColumn) {
            var oldAssignment = existingOrderAssignments.put(orderId, sheet);
            if (oldAssignment != null) {
                logger.log(
                        WARNING,
                        "The order %s assigned to %s is also assigned to %s.".formatted(
                                orderId, oldAssignment.getSpreadSheetName(), sheet.getSpreadSheetName()
                        )
                );
            }
        }
    }

    /**
     * Load the mapping between PNKs and employee sheets.
     */
    private void loadOverview(EmagMirrorDB mirrorDB) throws SQLException {
        var products = mirrorDB.readProducts();
        // To be commented in only to filter for a single PNK for debugging purpose
        // products = products.stream().filter(product -> product.pnk().equals("D2HG3PMBM")).toList();
        pnkToSpreadSheet = products.stream()
                .filter(it -> it.employeeSheetName() != null)
                .map(product -> {
                    var pnk = product.pnk();
                    var sheetName = product.employeeSheetName();
                    var sheet = getSpreadSheetByName(appName, sheetName);
                    if (sheet == null) {
                        logger.log(WARNING,
                                "Spreadsheet %s for the product %s with PNK %s was not found, it will be ignored."
                                        .formatted(sheetName, product.name(), pnk)
                        );
                        // return a null‐marker entry
                        return null;
                    }
                    return new AbstractMap.SimpleEntry<>(pnk, sheet);
                })
                // drop the null markers
                .filter(Objects::nonNull)
                // build your Map
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
        relevantProducts = pnkToSpreadSheet.keySet();
    }

    /**
     * Load the statistics for all relevant products.
     */
    private void loadAllStatistics() {
        var statisticsFromAllSheets = GetStatsForAllSheets.getStatistics(pnkToSpreadSheet).stream()
                .filter(it -> relevantProducts.contains(it.pnk()))
                .toList();
        pnkToStatistic = statisticsFromAllSheets.stream()
                .collect(Collectors.toMap(GetStatsForAllSheets.Statistic::pnk, statistic -> statistic));
    }

    /**
     * Format of date as used in the spreadsheet.
     */
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Create a row of data suitable to be inserted into the sheet out of the collected data.
     *
     * @param data collected data for the order and product.
     * @return row that needs to be inserted in the sheet.
     */
    private static List<Object> mapEmagToRow(EmployeeSheetData data) {
        var row = new ArrayList<>();
        row.add(data.orderId());
        row.add(data.quantity());
        row.add(String.format(Locale.US, "%.2f", data.price()));
        row.add(data.isCompany() ? "Yes" : "No");
        row.add(data.orderDate().format(dateFormat));
        row.add(data.productName());
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
    private void addToSheet(String pnk, SheetsAPI sheet, String sheetName, List<List<Object>> rowsToAdd) {
        List<String> orderIdColumn = sheet.getColumn(sheetName, "A");
        var mapOrderToColumn = new HashMap<String, List<Integer>>();
        for (var rowNumber = 0; rowNumber < orderIdColumn.size(); rowNumber++) {
            var orderId = orderIdColumn.get(rowNumber);
            var columnList = mapOrderToColumn.getOrDefault(orderId, new ArrayList<>());
            columnList.add(rowNumber);
            mapOrderToColumn.put(orderId, columnList);
        }
        mapOrderToColumn.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(entry ->
                        logger.log(
                                WARNING,
                                "Found order %s in multiple rows: %s".formatted(
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
        if (!withoutDuplicates.isEmpty()) {
            progressLogger.log(INFO,
                    "Adding %d rows after row %d to tab %s of spreadsheet %s."
                            .formatted( withoutDuplicates.size(), lastRowNumber, sheetName, sheet.getSpreadSheetName())
            );
            var pnksInSheet = sheet.getColumn(sheetName, "G").stream().skip(3).filter(x -> !x.isBlank()).collect(Collectors.toSet());
            if (pnksInSheet.size() > 1 && !pnksInSheet.contains(pnk)) {
                logger.log(WARNING, "Sheet '%s' in Spreadsheet '%s' contains multiple PNKs in column 7: %s.".formatted(sheetName, sheet.getTitle(), pnksInSheet));
            } else if (pnksInSheet.size() == 1 && !Objects.equals(pnksInSheet.iterator().next(), pnk)) {
                logger.log(WARNING, "Expected PNK '%s', but Sheet '%s' in Spreadsheet '%s' contains different PNK in column 7: %s.".formatted(pnk, sheetName, sheet.getTitle(), pnksInSheet));
            } else {
                if (pnksInSheet.size()>1) {
                    logger.log(WARNING, "Adding even though sheet '%s' in Spreadsheet '%s' contains multiple PNKs in column 7: %s.".formatted(sheetName, sheet.getTitle(), pnksInSheet));
                }
                updateRangeInChunks(sheet, sheetName, lastRowNumber + 1, withoutDuplicates);
            }
        }
    }

    private static final int chunkSize = 100;

    /**
     * Split the rows into chunks of size chunkSize and call sheet.updateRange for each.
     *
     * @param sheet the SheetsAPI instance
     * @param sheetName target sheet name
     * @param startRow 1-based row number where the first chunk should be inserted
     * @param rows the full list of rows to insert
     */
    private void updateRangeInChunks(SheetsAPI sheet,
                                     String sheetName,
                                     int startRow,
                                     List<List<Object>> rows) {
        for (int offset = 0; offset < rows.size(); offset += chunkSize) {
            int endIndex = Math.min(rows.size(), offset + chunkSize);
            List<List<Object>> chunk = rows.subList(offset, endIndex);
            int chunkStart = startRow + offset;
            int chunkEnd = startRow + endIndex - 1;
            String range = "%s!A%d:N%d".formatted(sheetName, chunkStart, chunkEnd);
            sheet.updateRange(range, chunk);
        }
    }
}