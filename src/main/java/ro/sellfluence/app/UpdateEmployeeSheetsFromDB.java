package ro.sellfluence.app;

import ro.sellfluence.apphelper.EmployeeSheetData;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.Logs;
import ro.sellfluence.support.UsefulMethods;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
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
import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.apphelper.Defaults.defaultGoogleApp;
import static ro.sellfluence.googleapi.SheetsAPI.getSpreadSheetByName;

/**
 * Update the employee sheets with new orders for them to call users for feedback.
 */
public class UpdateEmployeeSheetsFromDB {

    private static final Logger logger = Logs.getConsoleAndFileLogger("UpdateEmployeeSheetsWarnings", WARNING, 10, 1_000_000);
    private static final Logger progressLogger = Logs.getConsoleLogger("UpdateEmployeeSheetsProgress", INFO);

    private static final String statisticSheetName = "Statistici/luna";

    private static final Set<String> suburbsToExclude = Set.of();

    private static final Set<String> citiesToExclude = Set.of("Alba Iulia", "Alexandria", "Arad", "Bacau", "Baia Mare",
            "Bistrita", "Botosani", "Braila", "Brasov", "Sectorul 1","Sectorul 2","Sectorul 3","Sectorul 4","Sectorul 5", "Sectorul 6",
            "Buzau", "Calarasi", "Cluj-Napoca", "Constanta", "Craiova", "Deva",
            "Drobeta-Turnu Severin", "Focsani", "Galati", "Giurgiu", "Iasi", "Miercurea Ciuc",
            "Oradea", "Piatra Neamt", "Pitesti", "Ploiesti", "Ramnicu Valcea",
            "Resita", "Satu Mare", "Sfantu Gheorghe", "Sibiu", "Slatina",
            "Slobozia", "Suceava", "Targoviste", "Targu Jiu", "Targu Mures", "Timisoara", "Tulcea", "Vaslui", "Zalau");

    private static final Set<String> vendorsWithExclusions = Set.of("Zoopie Concept FBE",
            "Zoopie Invest",
            "Zoopie Solutions FBE",
            "Koppel",
            "Koppel FBE");

    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(arguments.getOption(databaseOptionName, defaultDatabase));
        updateSheets(mirrorDB);
    }

    public static void updateSheets(EmagMirrorDB mirrorDB) {
        try {
            var updateSheets = new UpdateEmployeeSheetsFromDB();
            updateSheets.transferFromDBToSheet(mirrorDB);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    record FeedbackTab(SheetsAPI sheet, String tabName) {
    }

    /**
     * This method will transfer new orders to the appropriate employee sheet depending on the products.
     *
     * @param mirrorDB database to read.
     * @throws SQLException on database errors.
     */
    public void transferFromDBToSheet(EmagMirrorDB mirrorDB) throws SQLException {
        var products = mirrorDB.readProducts();
        var productsByPNK = new HashMap<String, ProductInfo>();
        var productsByEmployee = new HashMap<SheetsAPI, List<ProductInfo>>();
        var sheetsByPNK = new HashMap<String, FeedbackTab>();
        for (ProductInfo productInfo : products) {
            if (productInfo.productCode() != null && !(productInfo.productCode().isBlank())) {
                var old = productsByPNK.put(productInfo.pnk(), productInfo);
                if (old != null) {
                    logger.log(WARNING, "Duplicate PNK %s: %s and %s".formatted(productInfo.pnk(), old, productInfo));
                }
            }
            String spreadSheetName = productInfo.employeeSheetName();
            var spreadSheet = getSpreadSheetByName(defaultGoogleApp, spreadSheetName);
            if (spreadSheet == null) {
                logger.log(WARNING, "No spreadsheet found for the product %s".formatted(productInfo));
                continue;
            }
            String tabName = productInfo.emloyeSheetTab();
            if (tabName == null) {
                logger.log(WARNING, "No tab found for the product %s".formatted(productInfo));
                continue;
            }
            var feedbackTab = new FeedbackTab(spreadSheet, tabName);
            productsByEmployee.computeIfAbsent(spreadSheet, _ -> new ArrayList<>())
                    .add(productInfo);
            var pnk = productInfo.pnk();
            var oldSheet = sheetsByPNK.put(pnk, feedbackTab);
            if (oldSheet != null && !(oldSheet.sheet().getSpreadSheetName().equals(spreadSheetName) && oldSheet.tabName().equals(tabName))) {
                logger.log(
                        WARNING,
                        "PNK %s associated with two sheets: %s/%s and %s/%s".formatted(
                                pnk, oldSheet.sheet.getSpreadSheetName(), oldSheet.tabName, spreadSheetName, tabName
                        )
                );
            }
        }
        // Map OrderId to spreadsheet containing it.
        var existingOrderAssignments = new HashMap<String, FeedbackTab>();
        // Map SheetData to OrderId
        var newAssignments = new HashMap<EmployeeSheetData, String>();
        for (var entry : productsByEmployee.entrySet()) {
            var spreadSheet = entry.getKey();
            var productsForEmployee = entry.getValue();
            if (spreadSheet == null) {
                logger.log(WARNING, "No spreadsheet found for products %s".formatted(productsByEmployee));
                continue;
            }
            var dates = datesByProductForSheet(spreadSheet);
            for (ProductInfo product : productsForEmployee) {
                var pnk = product.pnk();
                if (product.employeeSheetName() == null) {
                    logger.log(WARNING, "No employee sheet found for PNK %s".formatted(pnk));
                    continue;
                }
                if (product.emloyeSheetTab() == null) {
                    logger.log(WARNING, "No product tab found for PNK %s on the sheet %s.".formatted(pnk, product.employeeSheetName()));
                    continue;
                }
                progressLogger.log(INFO, () -> "Read orders from the spreadsheet %s tab %s for PNK %s.".formatted(spreadSheet, product.emloyeSheetTab(), pnk));
                accumulateExistingOrders(spreadSheet, product.emloyeSheetTab(), existingOrderAssignments);
                LocalDate startDate = dates.get(pnk);
                if (startDate == null) {
                    startDate = LocalDate.now().minusMonths(1);
                }
                var startTime = startDate.atStartOfDay();
                var endTime = LocalDate.now().minusDays(13).atStartOfDay();
                var newOrdersForProduct = mirrorDB.readOrderData(pnk, startTime, endTime).stream().filter(it -> it.quantity() > 0).toList();
                for (EmployeeSheetData it : newOrdersForProduct) {
                    newAssignments.put(it, product.emloyeSheetTab());
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

        Map<SheetsAPI, List<EmployeeSheetData>> reorderedByEmployeeSheet = new HashMap<>();

        for (List<EmployeeSheetData> dataList : withCalledSetOnDuplicates.values()) {
            for (EmployeeSheetData data : dataList) {
                String pnk = data.partNumberKey();
                // Look up the target sheet for this PNK
                ProductInfo productInfo = productsByPNK.get(pnk);
                if (productInfo == null) {
                    logger.log(WARNING, "No product found for PNK %s".formatted(pnk));
                    continue;
                }
                String sheetName = productInfo.employeeSheetName();
                if (sheetName == null) {
                    logger.log(WARNING, "No employee sheet found for PNK %s".formatted(pnk));
                    continue;
                }
                SheetsAPI sheet = getSpreadSheetByName(defaultGoogleApp, sheetName);
                if (sheet == null) {
                    logger.log(WARNING, "No sheet named %s found in Google Drive. PNK was %s.".formatted(sheetName, pnk));
                    continue;
                }
                // add to the map, creating a new list if needed
                reorderedByEmployeeSheet
                        .computeIfAbsent(sheet, _ -> new ArrayList<>())
                        .add(data);
            }
        }
        for (Map.Entry<SheetsAPI, List<EmployeeSheetData>> entry : reorderedByEmployeeSheet.entrySet()) {
            var sheet = entry.getKey();
            var groupedBySheet = entry.getValue().stream().collect(Collectors.groupingBy(it -> productsByPNK.get(it.partNumberKey()).emloyeSheetTab()));
            for (var entry1 : groupedBySheet.entrySet()) {
                var sheetName = entry1.getKey();
                List<EmployeeSheetData> orders = entry1.getValue();
                var filteredOrders = orders.stream()
                        .filter(it ->
                                !(
                                        vendorsWithExclusions.contains(it.vendorName()) &&
                                        (
                                                suburbsToExclude.contains(it.shippingSuburb())
                                                || citiesToExclude.contains(it.shippingCity())
                                        )
                                )
                        )
                        .toList();
                var droppedOrders = new HashSet<>(orders);
                droppedOrders.removeAll(filteredOrders);
                progressLogger.log(INFO, () -> "Filtered %d orders on sheet %s.".formatted(droppedOrders.size(), sheetName));
                droppedOrders.forEach(it -> progressLogger.log(INFO, " Dropped order %s.".formatted(it)));
                if (!filteredOrders.isEmpty()) {
                    var rowData = filteredOrders.stream()
                            .sorted(comparing(EmployeeSheetData::orderDate))
                            .map(UpdateEmployeeSheetsFromDB::mapEmagToRow)
                            .toList();
                    addToSheet(filteredOrders.getFirst().partNumberKey(), sheet, sheetName, rowData);
                }
            }
        }
        //TODO: We currently look at orders but shouldn't we rather look at customers?
    }

    /**
     * Read the ID of the orders already recorded in this sheet.
     *
     * @param sheet                    spreadsheet.
     * @param tabName                name of the sheet within the spreadsheet.
     * @param existingOrderAssignments map of existing order IDs to sheets.
     */
    private static void accumulateExistingOrders(SheetsAPI sheet, final String tabName, HashMap<String, FeedbackTab> existingOrderAssignments) {
        var orderIdColumn = sheet.getColumn(tabName, "A").stream().skip(4).toList();
        for (var orderId : orderIdColumn) {
            var oldAssignment = existingOrderAssignments.put(orderId, new FeedbackTab(sheet, tabName));
            if (oldAssignment != null) {
                logger.log(
                        WARNING,
                        "The order %s assigned to %s/%s is also assigned to %s/%s.".formatted(
                                orderId, oldAssignment.sheet.getSpreadSheetName(), oldAssignment.tabName, sheet.getSpreadSheetName(), tabName
                        )
                );
            }
        }
    }

    record DateForProduct(String pnk, LocalDate date) {
    }

    private Map<String, LocalDate> datesByProductForSheet(SheetsAPI spreadSheet) {
        var map = new HashMap<String, LocalDate>();
        var rows = spreadSheet.getMultipleColumns(statisticSheetName, "C", "E").stream()
                .skip(7)
                .filter(row -> row.getFirst() instanceof String s && !s.isEmpty() && row.get(1) instanceof BigDecimal)
                .map(
                        row -> new DateForProduct((String) row.getFirst(), UsefulMethods.sheetToLocalDate((BigDecimal) row.get(1)))
                ).toList();
        for (DateForProduct row : rows) {
            Objects.requireNonNull(row.date);
            map.put(row.pnk, row.date);
        }
        return map;
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
        List<String> orderIdColumn = sheet.getColumnInChunks(sheetName, "A", 1000);
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
                            .formatted(withoutDuplicates.size(), lastRowNumber, sheetName, sheet.getSpreadSheetName())
            );
            var pnksInSheet = sheet.getColumn(sheetName, "G").stream().skip(3).filter(x -> !x.isBlank()).collect(Collectors.toSet());
            if (pnksInSheet.size() > 1 && !pnksInSheet.contains(pnk)) {
                logger.log(WARNING, "Sheet '%s' in Spreadsheet '%s' contains multiple PNKs in column 7: %s.".formatted(sheetName, sheet.getTitle(), pnksInSheet));
            } else if (pnksInSheet.size() == 1 && !Objects.equals(pnksInSheet.iterator().next(), pnk)) {
                logger.log(WARNING, "Expected PNK '%s', but Sheet '%s' in Spreadsheet '%s' contains different PNK in column 7: %s.".formatted(pnk, sheetName, sheet.getTitle(), pnksInSheet));
            } else {
                if (pnksInSheet.size() > 1) {
                    logger.log(WARNING, "Adding even though sheet '%s' in Spreadsheet '%s' contains multiple PNKs in column 7: %s.".formatted(sheetName, sheet.getTitle(), pnksInSheet));
                }
                updateRangeInChunks(sheet, sheetName, lastRowNumber + 1, withoutDuplicates);
            }
        }
    }

    private static final int chunkSize = 50;

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