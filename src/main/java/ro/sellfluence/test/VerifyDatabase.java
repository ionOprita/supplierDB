package ro.sellfluence.test;

import org.jspecify.annotations.NonNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagOrder.ExtendedOrder;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.support.Arguments;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ro.sellfluence.apphelper.Defaults.databaseOptionName;
import static ro.sellfluence.apphelper.Defaults.defaultDatabase;
import static ro.sellfluence.support.UsefulMethods.require;

/**
 * The {@code VerifyDatabase} class provides functionality for verifying and analysing data in the database
 * regarding orders, vendors, and associated products. This class performs operations such as collecting,
 * grouping, and analysing order data by vendors and statuses, and verifies the consistency of orders and products.
 */
public class VerifyDatabase {

    /**
     * The typical set of status inconsistency.
     */
    private static final Map<@NonNull Integer, @NonNull Integer> set145 = Map.of(1, 1, 4, 0, 5, 1);

    /**
     * Collect orders with missing products in the finalised order, grouped by vendors.
     */
    private static final Map<String, List<String>> collectOrderNumber = new HashMap<>();

    /**
     * SQL delete statements for the orders that are missing a product.
     */
    private static final List<String> sqlDeletes = new ArrayList<>();

    /**
     * Main method for running the application.
     *
     * @param args this application honours the --db option for specifying the database to use.
     * @throws SQLException if an error occurs while accessing the database.
     * @throws IOException if an error occurs while reading the database.
     */
    public static void main(String[] args) throws SQLException, IOException {
        var allOrders = readAllOrdersFromDB(args);
        var statusCombinations = new HashMap<String, Integer>();
        for (var order : allOrders.entrySet()) {
            var orderId = order.getKey();
            var byVendor = order.getValue().stream().collect(Collectors.groupingBy(orderResult -> orderResult.order().vendor_name()));
            if (byVendor.size() > 1) {
                //System.out.println(orderId + " has " + byVendor.size() + " vendors");
                if (byVendor.size() > 2) {
                     //System.out.println("WOW " + orderId + " has " + byVendor.size() + " vendors!");
                }
            }
            for (var vendorOrder : byVendor.entrySet()) {
               // var vendorName = vendorOrder.getKey();
                var orderLines = vendorOrder.getValue();
                String stati = orderLines.stream().map(t -> t.order().status())
                        .sorted()
                        .map(Objects::toString)
                        .collect(Collectors.joining("\t"));
                statusCombinations.compute(stati, (_, v) -> v == null ? 1 : v + 1);
                // Look only at orders in different state.
                if (orderLines.size() > 1) {
                    var orderLinesByStatus = orderLines.stream().collect(Collectors.groupingBy(t -> t.order().status()));
                    var productsByStatus = getProductsByStatus(orderLinesByStatus, orderId);
                    verifyProducts(orderLinesByStatus, productsByStatus);
                }
            }
        }
        var mapOrderToVendor = new HashMap<String, String>();
        System.out.printf("Summary:");
        collectOrderNumber.forEach((vendor, orderList) -> {
                    System.out.printf("Vendor %s has a missing product in the finalised order in orders: %s.%n", vendor, orderList);
                    for (String orderId : orderList) {
                        var old = mapOrderToVendor.put(orderId, vendor);
                        if (old != null) {
                            System.out.printf("Order %s has products from both vendors %s and %s.%n", orderId, vendor, old);
                        }
                    }
                }
        );
        System.out.println();
        System.out.println("Use these delete statements to remove the incomplete order in status 4.");
        System.out.println();
        System.out.println(String.join("\n", sqlDeletes));

//        System.out.println(
//                statusCombinations.entrySet().stream()
//                        .sorted(comparingByKey())
//                        .map(e -> "%6d\t%s".formatted(e.getValue(), e.getKey()))
//                        .collect(Collectors.joining("\n"))
//        );
    }

    /**
     * Verifies the consistency of products by status against the provided orders and products data.
     * Ensures that product sizes match across statuses or identifies inconsistencies
     * and performs comparisons of product details if sizes are consistent.
     *
     * @param ordersByStatus a map where the key represents an order status,
     *                       and the value is a list of ExtendedOrder objects grouped by the respective status.
     * @param productsByStatus a map where the key represents a product status,
     *                         and the value is a list of Product objects grouped by the respective status.
     */
    private static void verifyProducts(Map<Integer, List<ExtendedOrder>> ordersByStatus, HashMap<Integer, List<Product>> productsByStatus) {
        var entriesByStatus = productsByStatus.entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
        var sizeSet = new HashSet<>(entriesByStatus.values());
        var orderId = getString(ordersByStatus, orderResult -> orderResult.order().id());
        var vendorName = getString(ordersByStatus, orderResult -> orderResult.order().vendor_name());
        if (sizeSet.size() > 1) {
            if (set145.equals(entriesByStatus)) {
                collectOrderNumber
                        .computeIfAbsent(vendorName, _ -> new ArrayList<>())
                        .add(orderId);
                var order4 = ordersByStatus.get(4);
                if (order4.size() != 1) {
                    throw new IllegalStateException("order4.size()!=1");
                }
                var order = order4.getFirst();
                var sql = "delete from emag_order where id = '%s' and vendor_id = '%s' and status = 4 and surrogate_id = %d;".formatted(order.order().id(), order.vendorId(), order.surrogateId());
                sqlDeletes.add(sql);
                System.out.printf("Order %s by %s has inconsistent product sizes in the order, 1 in status 1 and 5, 0 in status 4:%n%s.%n%n", orderId, vendorName, format(ordersByStatus));
            } else {
                System.out.printf("Order %s by %s has inconsistent product sizes in the order: %s.%n", orderId, vendorName, entriesByStatus);
            }
        } else {
            var products = productsByStatus.entrySet().stream().toList();
            for (int i = 1; i < entriesByStatus.size(); i++) {
                var status1 = products.get(i - 1).getKey();
                var status2 = products.get(i).getKey();
                var products1 = products.get(i - 1).getValue();
                var products2 = products.get(i).getValue();
                compareProducts(orderId, vendorName, status1, products1, status2, products2);
            }
        }
    }

    private static String format(Map<Integer, List<ExtendedOrder>> ordersByStatus) {
        return ordersByStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    var orders = e.getValue();
                    require(orders.size() == 1, "Only one order expected!");
                    var order = orders.getFirst();
                    var products = orders.getFirst().order().products();
                    require(products.size() <= 1, "Maximal one product expected!");
                    if (products.isEmpty()) {
                        return "Status %d of order %s%n no product in order%n"
                                .formatted(e.getKey(), order.order().id());
                    } else {
                        var product = products.getFirst();
                        return "Status %d of order %s%n product %s qty=%d, init_qty=%d, storno_qty=%d.%n"
                                .formatted(e.getKey(), order.order().id(), product.part_number_key(), product.quantity(), product.initial_qty(), product.storno_qty());
                    }
                })
                .collect(Collectors.joining("\n"));
    }

    private static String getString(Map<Integer, List<ExtendedOrder>> ordersByStatus, final Function<ExtendedOrder, String> fieldExtractor) {
        var orderIdSet = ordersByStatus.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(fieldExtractor))
                .collect(Collectors.toSet());
        if (orderIdSet.size() != 1) {
            System.out.println("What?! " + orderIdSet);
        }
        return orderIdSet.iterator().next();
    }

    private static @NonNull HashMap<String, List<ExtendedOrder>> readAllOrdersFromDB(String[] args) throws SQLException, IOException {
        var arguments = new Arguments(args);
        String databaseAlias = arguments.getOption(databaseOptionName, defaultDatabase);
        System.out.printf("Verifying database %s...%n", databaseAlias);
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseAlias);
        System.out.println("... read vendors ...");
        var allVendors = mirrorDB.readVendors();
        System.out.println("... read products ...");
        var allProducts = mirrorDB.readAllProducts();
        System.out.println("Products in the database: " + allProducts.size());
        System.out.println("... read orders ...");
        var allOrders = mirrorDB.readAllOrders(allProducts, allVendors);
        System.out.println("Orders in the database: " + allOrders.size());
        return allOrders;
    }

    private static @NonNull HashMap<Integer, List<Product>> getProductsByStatus(Map<Integer, @NonNull List<ExtendedOrder>> orderLinesByStatus, String orderId) {
        var productsByStatus = new HashMap<Integer, List<Product>>();
        for (var entry : orderLinesByStatus.entrySet()) {
            if (entry.getValue().size() > 1) {
                System.out.println("What?! " + orderId + " has " + entry.getValue().size() + " lines in status " + entry.getKey() + "?");
                System.out.println("Order lines: " + entry.getValue());
            } else {
                var orderWithStatus = entry.getValue().getFirst().order();
                productsByStatus.put(orderWithStatus.status(), orderWithStatus.products().stream().sorted(Comparator.comparing(Product::product_id)).toList());
            }
        }
        return productsByStatus;
    }


    private static void compareProducts(String orderId, String vendorName, int status1, List<Product> products1, int status2, List<Product> products2) {
        if (status1 == status2) {
            throw new IllegalStateException("status1==status2");
        }
        if (products1.size() != products2.size()) {
            System.out.printf("Order %s by %s has inconsistent product sizes: status %d has %d products, status %d has %d products.%n", orderId, vendorName, status1, products1.size(), status2, products2.size());
        } else {
            for (int i = 0; i < products1.size(); i++) {
                var product1 = products1.get(i);
                var product2 = products2.get(i);
                if (!normalize(status1, product1).equals(normalize(status2, product2))) {
                    System.out.printf("Order %s by %s has inconsistent products:%n status %d has %s,%n status %d has %s.%n%n", orderId, vendorName, status1, product1, status2, product2);
                }
            }
        }
    }

    private static Product normalize(int status, Product p) {
        return new Product(
                p.id(),
                p.product_id(),
                p.mkt_id(),
                p.name(),
                p.product_voucher_split() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(p.product_voucher_split()),
                p.status(),
                p.ext_part_number(),
                null, //p.part_number(),
                p.part_number_key(),
                p.currency(),
                p.vat(),
                p.retained_amount(),
                p.quantity() + p.storno_qty(),
                p.initial_qty(),
                0,
                p.reversible_vat_charging(),
                p.sale_price(),
                p.original_price(),
                p.created(),
                null, //p.modified(),
                p.details() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(p.details()),
                p.recycle_warranties() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(p.recycle_warranties()),
                p.attachments() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(p.attachments()),
                p.serial_numbers() == null ? "" : p.serial_numbers()
        );
    }
}