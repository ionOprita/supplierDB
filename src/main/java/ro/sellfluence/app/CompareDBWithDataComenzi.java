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
    private static final String databseName = "emagLocal";
    private static final DateTimeFormatter rfc1123_2digityear;
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
        rfc1123_2digityear = new DateTimeFormatterBuilder()
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


    // Fetch all orders from google sheet.
    // Store them in a list of objects.
    // Read from database and produse same data structure.
    // Compare to find:
    // a) items in emag db missing from google sheet.
    // b) items in google sheet and missing in emag db
    // c) orders which are in both lists but differ in data.

    public static void main(String[] args) {
        final DriveAPI drive = DriveAPI.getDriveAPI(appName);
        var spreadSheetId = drive.getFileId(spreadSheetName);
        var spreadSheet = SheetsAPI.getSpreadSheet(appName, spreadSheetId);
        System.out.println("Reading spreadsheet ...");
        var dataFromSheet = sheetDataToOrderList(spreadSheet.getRowsInColumnRange(sheetName, "A", "AF").stream().skip(3).toList());
        try {
            System.out.println("Reading database ...");
            var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databseName);
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
     * Store all data belonging to a single order line as found in the google sheet.
     */
    record OrderLine(
            String orderId,
            Vendor vendor,
            String PNK,
            LocalDateTime date
    ) {
        public void println() {
            System.out.printf("%tF %-10s %-20s %s%n", date(), orderId(), vendor.name(), PNK());
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
                        (LocalDateTime) row.get(4)
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
                    next.accept(new OrderLine(row.get(1), vendor, row.get(4), toLocalDateTime(row.get(0))));
                } catch (Exception e) {
                    throw new RuntimeException("Error in row " + Arrays.toString(row.toArray()), e);
                }
            }
        }).toList();
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
                    date = LocalDateTime.parse(s, rfc1123_2digityear);
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
     * @param fbe
     * @return
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
        // Print first ten orders that were not found.
        onlyInSheet.stream().filter(it -> it.vendor() != Vendor.judios).limit(10).forEach(System.out::println);
        // Print number of orders that are missing by vendor
        var onlyInSheetGroupedByVendor = onlyInSheet.stream()
                //.filter(it -> it.orderId().length()!=9)
                .collect(Collectors.groupingBy(OrderLine::vendor));
        onlyInSheetGroupedByVendor.entrySet().stream()
                .sorted(Comparator.comparing(it -> it.getKey().name()))
                .forEach(it -> System.out.printf("%-20s %4d%n", it.getKey().name(), it.getValue().size()));
        // Output some examples
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
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }

                            });
                    //exampleOrder.ifPresent(OrderLine::println);
                    //exampleOrder.ifPresent(orderLine -> System.out.printf("fetchOrder(\"%s\",\"%s\");%n", orderLine.vendor(),orderLine.orderId()));
                });
        found.stream()
                .map(it -> "delete from emag_fetch_log where emag_login = '%s' and order_start = '%04d-%02d-%02d';".formatted(
                        it.vendor.name(), it.date.getYear(), it.date.getMonthValue(), it.date.getDayOfMonth())
                )
                .sorted()
                .distinct()
                .forEach(System.out::println);
        var onlyInDB = getDifference(dataFromDB, sheetGroupedByOrderId, "DB");
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
        System.out.println("# elements only in " + sourceOfFirst + " " + onlyInFirst.size());
        return onlyInFirst;
    }

    /**
     * Find orders, that have products provided by different vendors.
     *
     * @param ordersByOrderId
     */
    private static void findOrdersWithMultipleVendors(Map<String, List<OrderLine>> ordersByOrderId) {
        var ordersWithMultipleVendors = ordersByOrderId.entrySet().stream()
                .filter(entry -> {
                    var orders = entry.getValue();
                    var vendors = orders.stream().map(OrderLine::vendor).collect(Collectors.toSet());
                    return vendors.size() > 1;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        System.out.println("# of orders with different vendors " + ordersWithMultipleVendors.size());
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
        System.out.println("# of order id with more than one OrderLine " + orderIdsWithMultipleEntries.size());
        return orderIdsWithMultipleEntries;
    }

    /**
     * Group the orders by their order ID.
     *
     * @param orders list of orders.
     * @param source text included in output to distinguish, which data is treated.
     * @return map from orderId to list of associated ORderLine.
     */
    private static @NotNull Map<String, List<OrderLine>> groupByOrderId(List<OrderLine> orders, final String source) {
        var groupedByOrderId = orders.stream().collect(Collectors.groupingBy(OrderLine::orderId));
        System.out.println("# elements from " + source + " with unique order id " + groupedByOrderId.size());
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