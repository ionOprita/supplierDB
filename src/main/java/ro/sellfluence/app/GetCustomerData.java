package ro.sellfluence.app;

import com.google.api.services.sheets.v4.model.ProtectedRange;
import com.google.api.services.sheets.v4.model.RowData;
import jdk.jshell.EvalException;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.googleapi.DriveAPI;
import ro.sellfluence.googleapi.SheetsAPI;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.time.LocalDate.now;
import static ro.sellfluence.emagapi.EmagApi.statusFinalized;

public class GetCustomerData {

    private static final String googleAppName = "sellfluence1";
    private static final String fileName = "Testing Coding Purposes 2 - Neagu Lavinia - Feedback clienti";
    private static final String statisticSheetName = "Statistici/luna";

    record SheetData(
            String orderId,
            int quantity,
            BigDecimal price,
            boolean isCompany,
            LocalDateTime orderDate,
            String productName,
            String partNumberKey,
            String billingName,
            String billingPhone,
            String billingAddress,
            String clientName,
            String clientPhone,
            String deliveryAddress,
            String deliveryMode,
            boolean contacted
    ) {
    }

    static Map<String, List<SheetData>> getByProduct(LocalDate startTime) {
        var emagCredentials = UserPassword.findAlias("emag");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var endTime = now().minusDays(13).atStartOfDay();
        try {
            var responses = emag.readRequest("order",
                    Map.of("status",
                            statusFinalized,
                            "createdAfter",
                            startTime,
                            "createdBefore",
                            endTime),
                    null,
                    OrderResult.class);
            Map<String, List<SheetData>> orderedProductsByPNK = new HashMap<>();
            for (OrderResult order : responses) {
                if (order.customer != null) {
                    for (Product product : order.products) {
                        List<SheetData> list = orderedProductsByPNK.getOrDefault(product.part_number_key, new ArrayList<>());
                        list.add(new SheetData(
                                        order.id,
                                        product.quantity,
                                        product.sale_price,
                                        order.isCompany(),
                                        order.date,
                                        product.name, // TODO: This might not be correct.
                                        product.part_number_key,
                                        order.customer.billing_name,
                                        order.customer.billing_phone,
                                        order.customer.getBillingAddress(),
                                        order.customer.name,
                                        order.customer.phone_1,
                                        order.customer.getShippingAddress(),
                                        order.getDeliveryMode(),
                                        false
                                )
                        );
                        orderedProductsByPNK.put(product.part_number_key, list);
                    }
                } else {
                    System.out.printf("WARNING: order %s ignored because customer is null%n", order.id);
                }
            }
            return orderedProductsByPNK;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static record Statistic(int index, String produs, String pnk, LocalDate lastUpdate) {
    }

    private static final DateTimeFormatter sheetDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static void main(String[] args) {
        String spreadSheetId = new DriveAPI(googleAppName).getFileId(fileName);
        SheetsAPI sheets = new SheetsAPI(googleAppName, spreadSheetId);
/*
        List<ProtectedRange> ranges = sheets.getProtectedRanges("1. Ice cool negru");
        System.out.println(ranges);
        sheets.setRangeWarningOnly("1. Ice cool negru", 0, true);
        ranges = sheets.getProtectedRanges("1. Ice cool negru");
        System.out.println(ranges);
        sheets.setRangeWarningOnly("1. Ice cool negru", 0, false);
        ranges = sheets.getProtectedRanges("1. Ice cool negru");
        System.out.println(ranges);

        // TODO: Maybe I can set the range to warningOnly and then back to allow the app to change.
*/
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        var statistics = sheets.getRowsInColumnRange(statisticSheetName, "A", "E").stream()
                .skip(6)
                .filter(row -> row.size() >= 5 && row.getFirst().matches("\\d+") && !row.get(2).isEmpty())
                .map(row -> new Statistic(Integer.parseInt(row.get(0)), row.get(1), row.get(2), LocalDate.parse(row.get(4), sheetDateFormat)))
                //TODO: Replace this quick hack with a proper solution, that removes hidden rows.
                .filter(row -> row.lastUpdate.isAfter(LocalDate.of(2024, 1, 1)))
                .toList();
        final var pnkToProduct = statistics.stream()
                .collect(Collectors.toMap(statistic -> statistic.pnk, statistic -> statistic.produs));
        final var smallestUpdateDate = statistics.stream().min((a, b) -> a.lastUpdate.compareTo(b.lastUpdate)).get().lastUpdate;
        final var emagEntries = getByProduct(smallestUpdateDate);
        final var sheetMetaData = sheets.getSheetProperties();
        statistics.forEach(statistic -> {
            final var sheetData = emagEntries.get(statistic.pnk);
            if (sheetData != null) {
                final var rowsToAdd = sheetData.stream()
                        .filter(emageEntry -> statistic.pnk.equals(emageEntry.partNumberKey) && emageEntry.orderDate.isAfter(statistic.lastUpdate.atStartOfDay()))
                        // Sort by date and within same date by order id
                        .sorted((a, b) -> {
                            var c = a.orderDate.compareTo(b.orderDate);
                            if (c == 0) c = a.orderId.compareTo(b.orderId);
                            return c;
                        })
                        .map(data -> {
                            var row = new ArrayList<Object>();
                            var identicalBillingAndCustomer = isIdenticalBillingAndCustomer(data);
                            var billingName = identicalBillingAndCustomer ? "Date facturare si livrare identice" : data.billingName;
                            var billingPhone = identicalBillingAndCustomer? "" : data.billingPhone;
                            var billingAddress = identicalBillingAndCustomer? "" : data.billingAddress;
                            row.add(data.orderId);
                            row.add(data.quantity);
                            row.add(data.price.toString());
                            row.add(data.isCompany ? "Da" : "Nu");
                            row.add(data.orderDate.format(dateFormat));
                            row.add(pnkToProduct.get(data.partNumberKey));
                            row.add(data.partNumberKey);
                            row.add(billingName);
                            row.add(billingPhone);
                            row.add(billingAddress);
                            row.add(data.clientName);
                            row.add(data.clientPhone);
                            row.add(data.deliveryAddress);
                            row.add(data.deliveryMode);
                            row.add("");
                            row.add(data.contacted ? "Da" : "Nu");
                            return (List<Object>) row;
                        })
                        .toList();
                if (!rowsToAdd.isEmpty()) {
                    final var sheetName = sheetMetaData.stream().filter(m -> m.index() == statistic.index - 1).toList().getFirst().title();
                    var lastRowNumber = sheets.getLastRow(sheetName, "A");
                    sheets.updateRange("%s!A%d:P%d".formatted(sheetName, lastRowNumber + 1, lastRowNumber + rowsToAdd.size()), rowsToAdd);
                }
            }
        });
    }

    private static boolean isIdenticalBillingAndCustomer(SheetData data) {
        var noBillingName = data.billingName == null || data.billingName.isEmpty();
        var noBillingPhone = data.billingPhone == null || data.billingPhone.isEmpty();
        var noBillingAddress = data.billingAddress == null || data.billingAddress.isEmpty();
        var identicalBillingAndCustomer = (noBillingName || data.billingName.equals(data.clientName))
                                          && (noBillingAddress || (data.billingAddress).equals(data.deliveryAddress))
                                          && (noBillingPhone || data.billingPhone.equals(data.clientPhone));
        return identicalBillingAndCustomer;
    }

}
