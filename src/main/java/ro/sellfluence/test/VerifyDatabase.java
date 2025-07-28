package ro.sellfluence.test;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.stream.Collectors;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;

public class VerifyDatabase {
    public static void main(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        String databaseAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        System.out.printf("Verifying database %s...%n", databaseAlias);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseAlias);
        System.out.println("... read vendors ...");
        var allVendors = mirrorDB.readVendors();
        System.out.println("... read products ...");
        var allProducts = mirrorDB.readAllProducts();
        System.out.println("Products in database: " + allProducts.size());
        System.out.println("... read orders ...");
        var allOrders = mirrorDB.readAllOrders(allProducts, allVendors);
        System.out.println("Orders in database: " + allOrders.size());
        for (var order : allOrders.entrySet()) {
            var orderId = order.getKey();
            var byVendor = order.getValue().stream().collect(Collectors.groupingBy(OrderResult::vendor_name));
            if (byVendor.size()>1) {
                //System.out.println(orderId + " has " + byVendor.size() + " vendors");
                if (byVendor.size() > 2) {
                    // System.out.println("WOW " + orderId + " has " + byVendor.size() + " vendors!");
                }
            }
            for (var vendorOrder: byVendor.entrySet()) {
                var orderLines = vendorOrder.getValue();
                // Look only at orders in different state.
                if (orderLines.size() > 1) {
                    var stateSet = orderLines.stream().map(it -> it.status()).collect(Collectors.toSet());
                    if (stateSet.size() != orderLines.size()) {
                        System.out.println("What?! " + orderId + " has " + orderLines.size() + " lines but only the states " + stateSet + "?");
                        System.out.println("Order lines: " + orderLines);
                    }
                    var orderLinesByStatus = orderLines.stream().collect(Collectors.groupingBy(OrderResult::status));
                    for (var entry : orderLinesByStatus.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            System.out.println("What?! " + orderId + " has " + entry.getValue().size() + " lines in status " + entry.getKey() + "?");
                            System.out.println("Order lines: " + entry.getValue());
                        }
                    }
                }
            }
        }
    }
}