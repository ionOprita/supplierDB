package ro.sellfluence.app;

import org.jetbrains.annotations.NotNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
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

    private static boolean isEMAGFbe(String fbe) {
        return switch (fbe) {
            case "eMAG FBE" -> true;
            case "eMAG NON-FBE" -> false;
            default -> throw new IllegalArgumentException("Unrecognized FBE value " + fbe);
        };
    }

    private static void compare(List<OrderLine> unsortedDataFromDB, List<OrderLine> unsortedDataFromSheet) {
        System.out.println("Sort database values ...");
        var dataFromDB = unsortedDataFromDB.stream()
                .sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId))
                .toList();
        System.out.println("Sort sheet values ...");
        var dataFromSheet = unsortedDataFromSheet.stream().sorted(Comparator.comparing(OrderLine::vendor).thenComparing(OrderLine::orderId)).toList();
        System.out.println("# elements from db " + dataFromDB.size());
        System.out.println("# elements from sheet " + dataFromSheet.size());
        var dbGroupedByOrderId = dataFromDB.stream().collect(Collectors.groupingBy(OrderLine::orderId));
        System.out.println("# elements from database with unique order id " + dbGroupedByOrderId.size());
        var orderIdsWithMultipleEntries = dbGroupedByOrderId.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1) // Filter for entries with more than one OrderLine
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        System.out.println("# of order id with more than one OrderLine " + orderIdsWithMultipleEntries.size());
        var ordersWithMultipleVendors = orderIdsWithMultipleEntries.entrySet().stream()
                .filter(entry -> {
                    var orders = entry.getValue();
                    var vendors = orders.stream().map(OrderLine::vendor).collect(Collectors.toSet());
                    return vendors.size() > 1;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        System.out.println("# of orders with different vendors " + ordersWithMultipleVendors.size());
        // dumpOrderLines(ordersWithMultipleVendors);
        var sheetGroupedByOrderId = dataFromSheet.stream().collect(Collectors.groupingBy(OrderLine::orderId));
        System.out.println("# elements from sheet with unique order id " + sheetGroupedByOrderId.size());
        System.out.println("Find ones only in sheet ...");
        var onlyInSheet = dataFromSheet.stream().filter(it -> {
            var potentialOrder = dbGroupedByOrderId.get(it.orderId);
            return potentialOrder == null || !potentialOrder.contains(it);
        }).toList();
        System.out.println("# elements only in sheet " + onlyInSheet.size());
        onlyInSheet.stream().filter(it -> it.vendor() != Vendor.judios).limit(10).forEach(System.out::println);
        var onlyInSheetGroupedByVendor = onlyInSheet.stream().collect(Collectors.groupingBy(OrderLine::vendor));
        onlyInSheetGroupedByVendor.entrySet().stream()
                .sorted(Comparator.comparing(it->it.getKey().name()))
                .forEach(it-> System.out.printf("%-20s %4d%n", it.getKey().name(),it.getValue().size()));
        onlyInSheetGroupedByVendor.entrySet().stream()
                .sorted(Comparator.comparing(it->it.getKey().name()))
                .forEach(it-> {
                    var exampleOrder = it.getValue().stream()
                            .filter(orderLine -> orderLine.date().getYear() == 2024)
                            .min(Comparator.comparing(OrderLine::date));
                    exampleOrder.ifPresent(OrderLine::println);
                });

        System.out.println("Find ones only in DB ...");
        var onlyInDB = dataFromDB.stream().filter(it -> {
            var potentialOrder = sheetGroupedByOrderId.get(it.orderId);
            return potentialOrder == null || !potentialOrder.contains(it);
        }).toList();
        System.out.println("# elements only in db " + onlyInDB.size());
    }

    private static void dumpOrderLines(Map<String, @NotNull List<OrderLine>> ordersWithMultipleVendors) {
        ordersWithMultipleVendors.forEach((_, orders) -> {
            orders.forEach(OrderLine::println);
            System.out.println();
        });
    }
}
/*

 */