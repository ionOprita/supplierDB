package ro.sellfluence.app;

import org.jetbrains.annotations.NotNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.DAY_OF_WEEK;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

/**
 * Read data from the comenzi sheet and compare it to the database data.
 * Find orders that are only in one place.
 * Find differences in orders that are in both places.
 */
public class CompareDBWithDataComenzi {

    private static final String appName = "sellfluence1";
    private static final String spreadSheetName = "Testing Coding 2024 - Date comenzi";
    private static final String sheetName = "Date";
    private static final String databaseName = "emagLocal";
    private static final DateTimeFormatter rfc1123_2digit_year;
    private static final DateTimeFormatter isoLikeLocalDateTime;

    static {
        isoLikeLocalDateTime = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .append(ISO_LOCAL_DATE)
                .appendLiteral(' ')
                .append(ISO_LOCAL_TIME)
                .toFormatter(Locale.ENGLISH);
        Map<Long, String> dow = new HashMap<>();
        dow.put(1L, "Mon");
        dow.put(2L, "Tue");
        dow.put(3L, "Wed");
        dow.put(4L, "Thu");
        dow.put(5L, "Fri");
        dow.put(6L, "Sat");
        dow.put(7L, "Sun");
        Map<Long, String> moy = new HashMap<>();
        moy.put(1L, "Jan");
        moy.put(2L, "Feb");
        moy.put(3L, "Mar");
        moy.put(4L, "Apr");
        moy.put(5L, "May");
        moy.put(6L, "Jun");
        moy.put(7L, "Jul");
        moy.put(8L, "Aug");
        moy.put(9L, "Sep");
        moy.put(10L, "Oct");
        moy.put(11L, "Nov");
        moy.put(12L, "Dec");
        rfc1123_2digit_year = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .parseLenient()
                .optionalStart()
                .appendText(DAY_OF_WEEK, dow)
                .appendLiteral(", ")
                .optionalEnd()
                .appendValue(DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
                .appendLiteral(' ')
                .appendText(MONTH_OF_YEAR, moy)
                .appendLiteral(' ')
                .appendValueReduced(YEAR, 2, 2, LocalDate.of(1970, 1, 1))
                .appendLiteral(", ")
                .appendValue(HOUR_OF_DAY, 2)
                .appendLiteral(':')
                .appendValue(MINUTE_OF_HOUR, 2)
                .appendLiteral(':')
                .appendValue(SECOND_OF_MINUTE, 2)
                .toFormatter(Locale.ENGLISH);
    }

    public static void main(String[] args) {
        final DriveAPI drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        System.out.println("Reading spreadsheet ...");
        var dataFromSheet = sheetDataToOrderList(spreadSheet.getRowsInColumnRange(sheetName, "A", "AF").stream().skip(3).toList());
        try {
            System.out.println("Reading database ...");
            var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseName);
            var dataFromDB = dbDataToOrderList(mirrorDB.readForComparisonApp());
            System.out.println("Compare data ...");
            compare(dataFromDB, dataFromSheet);
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    enum Vendor {
        judios,
        koppel,
        koppelfbe,
        sellfluence,
        sellfusion,
        zoopieconcept,
        zoopieinvest,
        zoopiesolutions;

        static Vendor fromSheet(String name, boolean isFBE) {
            return switch (name) {
                case "Judios Concept SRL", "Judios RO FBE", "Judios Concept" -> judios;
                case "Koppel SRL", "Koppel" -> isFBE ? koppelfbe : koppel;
                case "Koppel FBE" -> koppelfbe;
                case "Sellfluence SRL", "Sellfluence FBE", "Sellfluence" -> sellfluence;
                case "Sellfusion SRL", "Sellflusion SRL", "SELLFUSION FBE" -> sellfusion;
                case "Zoopie Concept SRL", "Zoopie Concept FBE", "Zoopie Concept" -> zoopieconcept;
                case "Zoopie Invest SRL", "Zoopie Invest" -> zoopieinvest;
                case "Zoopie Solutions SRL", "Zoopie Solutions FBE", "Zoopie Solutions" -> zoopiesolutions;
                default ->
                        throw new IllegalArgumentException("Unrecognized vendor '" + name + "' " + (isFBE ? "FBE" : ""));
            };
        }
    }

    /**
     * Store all data belonging to a single order line as found in the Google sheet.
     */
    record OrderLine(
            String orderId,
            Vendor vendor,
            String PNK,
            LocalDateTime date,
            int status
    ) {
        public void println() {
            System.out.printf("%tF %-10s %-20s %s%n", date(), orderId(), vendor.name(), PNK());
        }

        public boolean equalExceptStatus(Object o) {
            if (!(o instanceof OrderLine orderLine)) return false;
            return Objects.equals(PNK, orderLine.PNK)
                   && vendor == orderLine.vendor
                   && Objects.equals(orderId, orderLine.orderId)
                   && Objects.equals(date, orderLine.date);
        }

        public boolean equalExceptStatusAndPNK(Object o) {
            if (!(o instanceof OrderLine orderLine)) return false;
            return vendor == orderLine.vendor
                   && Objects.equals(orderId, orderLine.orderId)
                   && Objects.equals(date, orderLine.date);
        }

        public boolean equalsExceptStatusAndVendor(Object o) {
            if (!(o instanceof OrderLine orderLine)) return false;
            return Objects.equals(PNK, orderLine.PNK)
                   && Objects.equals(orderId, orderLine.orderId)
                   && Objects.equals(date, orderLine.date);
        }
    }

    /**
     * Convert the data from the database into a list of OrderLine.
     *
     * @param dataFromDB list of database rows.
     * @return OrderLine list
     */
    private static List<OrderLine> dbDataToOrderList(List<List<Object>> dataFromDB) {
        return dataFromDB.stream().map(row ->
                new OrderLine(
                        (String) row.get(0),
                        Vendor.fromSheet((String) row.get(1), (Boolean) row.get(2)),
                        (String) row.get(3),
                        (LocalDateTime) row.get(4),
                        (Integer) row.get(5)
                        // , (Integer) row.get(6)
                )
        ).toList();
    }

    /**
     * Convert data from the spreadsheet into a list of OrderLine.
     *
     * @param dataFromSheet the data as list of rows.
     * @return OrderLine list
     */
    private static List<OrderLine> sheetDataToOrderList(List<List<String>> dataFromSheet) {
        return dataFromSheet.stream().<OrderLine>mapMulti((row, next) -> {
            if (!isTemplate(row)) {
                try {
                    String vendorName = row.get(23);
                    Vendor vendor = Vendor.fromSheet(vendorName, isEMAGFbe(row.get(24)));
                    next.accept(new OrderLine(row.get(1), vendor, row.get(4), toLocalDateTime(row.get(0)), toNumericStatus(row.get(2))));
                } catch (Exception e) {
                    throw new RuntimeException("Error in row " + Arrays.toString(row.toArray()), e);
                }
            }
        }).toList();
    }

    /**
     * Convert test status in spreadsheet to numeric value.
     *
     * @param s status as text.
     * @return numeric status value.
     */
    private static int toNumericStatus(String s) {
        return switch (s.trim()) {
            case "Finished", "Finalizata" -> 4;
            case "Stornata" -> 5;
            default -> throw new IllegalArgumentException("Unknown status " + s);
        };
    }

    /**
     * Detect the template line so it can be excluded.
     *
     * @param row spreadsheet row.
     * @return true if it is a template line.
     */
    private static boolean isTemplate(List<String> row) {
        return row.get(7).equals("XXX.XX") && row.get(8).equals("XXX.XX");
    }

    private static LocalDateTime toLocalDateTime(String s) {
        LocalDateTime date;
        try {
            date = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            try {
                date = LocalDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            } catch (Exception ex) {
                try {
                    date = LocalDateTime.parse(s, rfc1123_2digit_year);
                } catch (Exception exc) {
                    date = LocalDateTime.parse(s, isoLikeLocalDateTime);
                }
            }
        }
        return date;
    }

    /**
     * Decodes the FBE column in the spreadsheet.
     *
     * @param fbe Textual information if it is FBE or not.
     * @return true if it is FBE, false otherwise.
     */
    private static boolean isEMAGFbe(String fbe) {
        return switch (fbe) {
            case "eMAG FBE" -> true;
            case "eMAG NON-FBE" -> false;
            default -> throw new IllegalArgumentException("Unrecognized FBE value " + fbe);
        };
    }

    private static void compare(List<OrderLine> unsortedDataFromDB, List<OrderLine> unsortedDataFromSheet) {
        var dataFromDB = sortOrders(unsortedDataFromDB, "Sort database values ...");
        var dataFromSheet = sortOrders(unsortedDataFromSheet, "Sort sheet values ...");
        printSizes(dataFromDB, dataFromSheet);
        var dbGroupedByOrderId = groupByOrderId(dataFromDB, "database");
        var orderIdsWithMultipleEntries = filterOrdersWithMultipleEntries(dbGroupedByOrderId);
        findOrdersWithMultipleVendors(orderIdsWithMultipleEntries);
        // dumpOrderLines(ordersWithMultipleVendors);
        var sheetGroupedByOrderId = groupByOrderId(dataFromSheet, "sheet");
        var onlyInSheet = getDifference(dataFromSheet, dbGroupedByOrderId, "sheet");
        findOrdersWithMismatches(onlyInSheet, dbGroupedByOrderId, sheetGroupedByOrderId);
        // Print the first ten orders that were not found.
        onlyInSheet.stream().filter(it -> it.vendor() != Vendor.judios).limit(10).forEach(System.out::println);
        // Print the number of orders that are missing by vendor
        var onlyInSheetGroupedByVendor = onlyInSheet.stream()
                //.filter(it -> it.orderId().length()!=9)
                .collect(Collectors.groupingBy(OrderLine::vendor));
        onlyInSheetGroupedByVendor.entrySet().stream()
                .sorted(Comparator.comparing(it -> it.getKey().name()))
                .forEach(it -> System.out.printf("%-20s %4d%n", it.getKey().name(), it.getValue().size()));
        //doNotDoNow(onlyInSheetGroupedByVendor);
        var onlyInDB = getDifference(dataFromDB, sheetGroupedByOrderId, "DB");
    }

    record VendorMismatch(String orderId, Vendor sheetVendor, Vendor dbVendor) {
    }

    record ComplexCase(String orderId, List<OrderLine> sheetVendor, List<OrderLine> dbVendor) {
    }

    /**
     * See if the orderId is there, but there are other mismatches.
     *
     * @param ordersOnlyInOnePlace List of orders that are only in one place.
     * @param dbGroupedByOrderId List of orders in the database grouped by orderId.
     */
    private static void findOrdersWithMismatches(List<OrderLine> ordersOnlyInOnePlace, Map<String, List<OrderLine>> dbGroupedByOrderId, Map<String, List<OrderLine>> sheetGroupedByOrderId) {
        var orderIdsWithIssues = ordersOnlyInOnePlace.stream().map(OrderLine::orderId).collect(Collectors.toSet());
        var statusFinalInsteadOfStorno = new ArrayList<OrderLine>();
        var missingInDB = new ArrayList<OrderLine>();
        var vendorMismatch = new ArrayList<VendorMismatch>();
        var blankPNKInSheet = new ArrayList<OrderLine>();
        var complexCases = new ArrayList<ComplexCase>();

        orderIdsWithIssues.stream()
                .filter(it -> it.length() == 9)
                .sorted()
                .forEach(orderId -> {
                            var sheetOrders = sheetGroupedByOrderId.getOrDefault(orderId, new ArrayList<>());
                            var dbOrders = dbGroupedByOrderId.getOrDefault(orderId, new ArrayList<>());
                            var onlyInSheet = sheetOrders.stream().filter(orderLine -> !dbOrders.contains(orderLine)).toList();
                            var onlyInDB = dbOrders.stream().filter(orderLine -> !sheetOrders.contains(orderLine)).toList();
                            if (onlyInDB.isEmpty()) {
                                missingInDB.addAll(onlyInSheet);
                                //System.out.printf("DB is missing %s%n", orderLine)
                            } else if (onlyInSheet.size() == 1 && onlyInDB.size() == 1) {
                                var sheetOrder = onlyInSheet.getFirst();
                                var dbOrder = onlyInDB.getFirst();
                                if (sheetOrder.equalExceptStatus(dbOrder)) {
                                    if (sheetOrder.status() == 4 && dbOrder.status() == 5) {
                                        statusFinalInsteadOfStorno.add(sheetOrder);
                                    } else {
                                        System.out.printf("Mismatch in order %s%n Sheet has status %d, DB has %d.%n", orderId, sheetOrder.status(), dbOrder.status());
                                    }
                                } else if (sheetOrder.equalsExceptStatusAndVendor(dbOrder)) {
                                    vendorMismatch.add(new VendorMismatch(orderId, sheetOrder.vendor(), dbOrder.vendor()));
                                } else if (sheetOrder.equalExceptStatusAndPNK(dbOrder) && sheetOrder.PNK().isBlank()) {
                                    blankPNKInSheet.add(dbOrder);
                                } else {
                                    System.out.printf("Mismatch in order %s%n Sheet has %s%n DB has %s%n", orderId, sheetOrder, dbOrder);
                                }
                            } else {
                                complexCases.add(new ComplexCase(orderId, onlyInSheet, onlyInDB));
                                //System.out.printf("Complex case %s: Sheet has %d entries and DB has %d entries that do not match.%n", orderId, onlyInSheet.size(), onlyInDB.size());
                            }
                        }
                );
        System.out.printf(
                """
                        Sheet status is final, emag reports storno: %d
                        Order is missing in the database: %d
                        Vendor in spreadsheet does not match vendor reported by emag: %d
                        PNK is empty in spreadsheet: %d
                        More complex cases to be analyzed by hand: %d
                        """,
                statusFinalInsteadOfStorno.size(),
                missingInDB.size(),
                vendorMismatch.size(),
                blankPNKInSheet.size(),
                complexCases.size()
        );
        dumpVendorMismatchByCase(vendorMismatch);
        //generateDeleteStatements(missingInDB);
        checkMissingWithEmag(missingInDB);
    }

    record OrderLineAndResponse(OrderLine orderLine, List<OrderResult> response) {
    }

    /**
     * Check the missing orders with emag.
     *
     * @param missingInDB list of orders to check.
     */
    private static void checkMissingWithEmag(List<OrderLine> missingInDB) {
        var missingOrderId = new ArrayList<OrderLine>();
        var foundOrderId = new ArrayList<OrderLineAndResponse>();
        missingInDB.forEach(orderLine -> {
            var response = fetchFromEmag(orderLine);
            if (response.isEmpty()) {
                missingOrderId.add(orderLine);
            } else {
                foundOrderId.add(new OrderLineAndResponse(orderLine, response));
            }
        });
        System.out.printf("""
                        Orders not in emag %d
                        Orders missing from db but in emag %d
                        """,
                missingOrderId.size(),
                foundOrderId.size()
        );
        foundOrderId.forEach(it ->
                System.out.printf("""
                                Order %s was found in emag. Response is
                                 %s
                                """,
                        it.orderLine,
                        it.response));
        System.out.println("Days having orders that are found if searched by orderId");
        generateDeleteStatements(foundOrderId.stream().map(OrderLineAndResponse::orderLine).toList());
        missingOrderId.stream()
                .sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId))
                .forEach(orderLine -> System.out.printf("%s\t%s%n", orderLine.orderId(), orderLine.vendor()));
    }

    private static void dumpVendorMismatchByCase(List<VendorMismatch> vendorMismatch) {
        vendorMismatch.stream()
                .collect(Collectors.groupingBy(x -> "Sheet: %s, DB: %s".formatted(x.sheetVendor, x.dbVendor)))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(it ->
                        System.out.printf("%s: %d, example orderId: %s%n", it.getKey(), it.getValue().size(), it.getValue().getFirst().orderId())
                );
    }

    private static void generateDeleteStatements(List<OrderLine> missingInDB) {
        missingInDB.stream().map(it -> "delete from emag_fetch_log where emag_login = '%s' and order_start = '%04d-%02d-%02d';".formatted(
                        it.vendor.name(), it.date.getYear(), it.date.getMonthValue(), it.date.getDayOfMonth()))
                .sorted()
                .distinct()
                .forEach(System.out::println);
    }

    private static void checkVendor(OrderLine orderFromSheet, OrderLine orderFromDB) {
        if (orderFromSheet.vendor() != orderFromDB.vendor()) {
            var resultWithSheetVendor = fetchFromEmag(orderFromSheet);
            var resultWithDBVendor = fetchFromEmag(orderFromDB);
            if (!resultWithSheetVendor.isEmpty()) {
                System.out.printf("Emag confirms Sheet correct: %s%n", resultWithSheetVendor);
            } else if (resultWithDBVendor.isEmpty()) {
                System.out.printf("No result from emag with eithor vendor.%n sheet: %s%n DB: %s%n", orderFromSheet, orderFromDB);
            } else {
                System.out.printf("Database is correct on order %s%n", orderFromSheet.orderId());
            }
        } else {
            System.out.printf(
                    "!!! %s differs from %s but has same orderId %s",
                    orderFromSheet,
                    orderFromDB,
                    orderFromSheet.orderId()
            );
        }
    }

    private static void doNotDoNow(Map<Vendor, @NotNull List<OrderLine>> onlyInSheetGroupedByVendor) {
        var notFound = new ArrayList<OrderLine>();
        var found = new ArrayList<OrderLine>();
        onlyInSheetGroupedByVendor.entrySet().stream()
                .sorted(Comparator.comparing(it -> it.getKey().name()))
                .forEach(entry -> {
                    entry.getValue().stream()
                            .filter(orderLine -> orderLine.date().getYear() == 2024 && orderLine.date().getMonth() == Month.AUGUST)
                            //.filter(orderLine -> orderLine.orderId().length() == 9)
                            //.min(Comparator.comparing(OrderLine::date));
                            .forEach(orderLine -> {
                                var emagCredentials = UserPassword.findAlias(orderLine.vendor().name());
                                var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
                                try {
                                    var response = emag.readRequest("order", Map.of("id", orderLine.orderId()), null, OrderResult.class);
                                    if (response.isEmpty()) {
                                        notFound.add(orderLine);
                                    } else {
                                        found.add(orderLine);
                                    }
                                } catch (IOException | InterruptedException e) {
                                    throw new RuntimeException(e);
                                }

                            });
                    //exampleOrder.ifPresent(OrderLine::println);
                    //exampleOrder.ifPresent(orderLine -> System.out.printf("fetchOrder(\"%s\",\"%s\");%n", orderLine.vendor(),orderLine.orderId()));
                });
        generateDeleteStatements(found);
    }

    /**
     * Fetch order from emag.
     *
     * @return All orders found.
     */
    private static List<OrderResult> fetchFromEmag(OrderLine orderLine) {
        var emagCredentials = UserPassword.findAlias(orderLine.vendor().name());
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        List<OrderResult> response;
        try {
            response = emag.readRequest("order", Map.of("id", orderLine.orderId()), null, OrderResult.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    /**
     * Get the difference between two order lists.
     *
     * @param firstList list of orders
     * @param secondList map of orders grouped by orderId
     * @param sourceOfFirst text used in output to indicate the origin of the orders in firstList.
     * @return the orders from firstList not found in secondList.
     */
    private static @NotNull List<OrderLine> getDifference(List<OrderLine> firstList, Map<String, List<OrderLine>> secondList, final String sourceOfFirst) {
        System.out.println("Find ones only in " + sourceOfFirst + " ...");
        var onlyInFirst = firstList.stream().filter(it -> {
            var potentialOrder = secondList.get(it.orderId);
            return potentialOrder == null || !potentialOrder.contains(it);
        }).toList();
        System.out.println("Number of elements only in " + sourceOfFirst + " " + onlyInFirst.size());
        return onlyInFirst;
    }

    /**
     * Find orders, that have products provided by different vendors.
     *
     * @param ordersByOrderId Map of orderId to the associated OrderLine.
     */
    private static void findOrdersWithMultipleVendors(Map<String, List<OrderLine>> ordersByOrderId) {
        var ordersWithMultipleVendors = ordersByOrderId.entrySet().stream()
                .filter(entry -> {
                    var orders = entry.getValue();
                    var vendors = orders.stream().map(OrderLine::vendor).collect(Collectors.toSet());
                    return vendors.size() > 1;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        System.out.println("Number of orders with different vendors " + ordersWithMultipleVendors.size());
    }

    /**
     * Filter out those orders, which have more than one product.
     *
     * @param ordersByOrderId map from orderId to OrderLine.
     * @return map containing only those entries, which map to a list with more than one element.
     */
    private static @NotNull Map<String, List<OrderLine>> filterOrdersWithMultipleEntries(Map<String, List<OrderLine>> ordersByOrderId) {
        var orderIdsWithMultipleEntries = ordersByOrderId.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1) // Filter for entries with more than one OrderLine
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        System.out.println("Number of order ID with more than one OrderLine " + orderIdsWithMultipleEntries.size());
        return orderIdsWithMultipleEntries;
    }

    /**
     * Group the orders by their order ID.
     *
     * @param orders list of orders.
     * @param source text included in output to distinguish, which data is treated.
     * @return map from orderId to list of associated OrderLine.
     */
    private static @NotNull Map<String, List<OrderLine>> groupByOrderId(List<OrderLine> orders, final String source) {
        var groupedByOrderId = orders.stream().collect(Collectors.groupingBy(OrderLine::orderId));
        System.out.println("Number of elements from " + source + " with unique order ID " + groupedByOrderId.size());
        return groupedByOrderId;
    }

    /**
     * Print the sizes of the two lists.
     *
     * @param dataFromDB list of orders from the database.
     * @param dataFromSheet list of orders from the spreadsheet.
     */
    private static void printSizes(List<OrderLine> dataFromDB, List<OrderLine> dataFromSheet) {
        System.out.println("# elements from db " + dataFromDB.size());
        System.out.println("# elements from sheet " + dataFromSheet.size());
    }

    /**
     * Create a new list where the orders are sorted first by the vendor and then by the orderId.
     * @param orders list of orders.
     * @param title Text printed to identify the step.
     * @return sorted order list.
     */
    private static @NotNull List<OrderLine> sortOrders(List<OrderLine> orders, String title) {
        System.out.println(title);
        return orders.stream()
                .sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId))
                .toList();
    }

    /**
     * Given a map from vendors to their orders, dump the orders.
     * A blank line separates the orders of different vendors, but no vendor name is printed.
     *
     * @param ordersByVendors map having the vendor as key and the list of orders as value.
     */
    private static void dumpOrderLines(Map<String, @NotNull List<OrderLine>> ordersByVendors) {
        ordersByVendors.forEach((_, orders) -> {
            orders.forEach(OrderLine::println);
            System.out.println();
        });
    }
}
/*

 */