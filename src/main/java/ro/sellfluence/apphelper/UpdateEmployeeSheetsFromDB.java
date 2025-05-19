package ro.sellfluence.apphelper;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

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

    private static final Logger logger = Logger.getLogger(UpdateEmployeeSheetsFromDB.class.getName());

    private final String appName;
    private final DriveAPI drive;
    private Map<String, SheetsAPI> pnkToSpreadSheet;
    private Set<String> relevantProducts;
    private LocalDateTime startTime;
    private LocalDateTime endTime = LocalDate.now().minusDays(13).atStartOfDay();
    private Map<String, GetStatsForAllSheets.Statistic> pnkToStatistic;


    public UpdateEmployeeSheetsFromDB(String appName) {
        drive = DriveAPI.getDriveAPI(appName);
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

    public void transferFromDBToSheet(EmagMirrorDB mirrorDB) throws SQLException, IOException {
        loadOverview(mirrorDB);
        if (relevantProducts.isEmpty()) {
            throw new RuntimeException("No valid products found in database.");
        }
        loadAllStatistics();
        // Map OrderId to sheet containing it.
        var existingOrderAssignments = new HashMap<String, SheetsAPI>();
        // Map SheetData to OrderId
        var newAssignments = new HashMap<EmployeeSheetData, String>();
        for (var entry : pnkToStatistic.entrySet()) {
            var pnk = entry.getKey();
            var statistic = entry.getValue();
            logger.log(INFO, () -> "Processing statistic for PNK %s: %s".formatted(pnk, statistic));
            var spreadSheet = pnkToSpreadSheet.get(pnk);
            if (spreadSheet == null) {
                logger.log(WARNING, "No spreadsheet found for PNK %s".formatted(pnk));
            } else {
                accumulateExistingOrders(spreadSheet, statistic.sheetName(), existingOrderAssignments);
                var newOrdersForProduct = mirrorDB.readOrderData(pnk, startTime, endTime);
                newOrdersForProduct.stream()
                        .forEach(it -> newAssignments.put(it, statistic.sheetName()));
            }
        }
        System.out.println("Existing orders: " + existingOrderAssignments.size());
        System.out.println("New orders: " + newAssignments.size());
/*
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
                                .sorted(comparing(SheetData::orderDate).thenComparing(SheetData::orderId))
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
                                            .map(SheetData::orderId)
                                            .collect(Collectors.joining(","))
                            )
                    );
                }
            });
        }
*/
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
                        "Order %s assigned to %s, is also assigned to %s".formatted(
                                orderId, oldAssignment.getSpreadSheetName(), sheet.getSpreadSheetName()
                        )
                );
            }
        }
    }

    /**
     * Load the mapping between PNKs and employee sheets.
     */
    private void loadOverview(EmagMirrorDB mirrorDB) throws SQLException, IOException {
        var products = mirrorDB.readProducts();
        pnkToSpreadSheet = products.stream()
                .filter(it -> it.product().employeeSheetName() != null)
                .map(it -> {
                    var product = it.product();
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