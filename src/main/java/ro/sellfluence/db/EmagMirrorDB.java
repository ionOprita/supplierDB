package ro.sellfluence.db;

import ch.claudio.db.DB;
import org.jspecify.annotations.NonNull;
import ro.sellfluence.apphelper.EmployeeSheetData;
import ro.sellfluence.db.EmagFetchLog.EmagFetchHistogram;
import ro.sellfluence.db.EmagOrder.ExtendedOrder;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.versions.SetupDB;
import ro.sellfluence.emagapi.CancellationReason;
import ro.sellfluence.emagapi.LockerDetails;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.sheetSupport.Conversions;
import ro.sellfluence.support.Logs;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.math.RoundingMode.HALF_EVEN;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static ro.sellfluence.db.EmagFetchLog.deleteFetchLogsBefore;
import static ro.sellfluence.db.EmagFetchLog.getEmagLog;
import static ro.sellfluence.db.EmagFetchLog.insertEmagLog;
import static ro.sellfluence.db.EmagFetchLog.updateEmagLog;
import static ro.sellfluence.db.EmagOrder.addOrderResult;
import static ro.sellfluence.db.GMV.computeAndStoreGMVForProduct;
import static ro.sellfluence.db.GMV.getGMVByMonth;
import static ro.sellfluence.db.GMV.getGMVByProductId;
import static ro.sellfluence.db.ProductTable.getProductCodes;
import static ro.sellfluence.db.ProductTable.getProducts;
import static ro.sellfluence.db.ProductTable.insertOrUpdateProduct;
import static ro.sellfluence.db.RMA.addRMAResult;
import static ro.sellfluence.db.Vendor.insertOrUpdateVendor;
import static ro.sellfluence.db.Vendor.selectFetchTimeByAccount;
import static ro.sellfluence.db.Vendor.selectVendorIdByName;
import static ro.sellfluence.db.Vendor.updateFetchTimeByAccount;
import static ro.sellfluence.support.UsefulMethods.toLocalDate;
import static ro.sellfluence.support.UsefulMethods.toLocalDateTime;
import static ro.sellfluence.support.UsefulMethods.toTimestamp;

public class EmagMirrorDB {

    private static final Map<String, EmagMirrorDB> openDatabases = new HashMap<>();

    private static final Logger logger = Logs.getConsoleLogger("EmagMirrorDB", INFO);

    private final DB database;

    private EmagMirrorDB(DB database) {
        this.database = database;
    }

    /**
     * Get a database by alias. This will also update the database to the latest version.
     *
     * @param alias name of the database.
     * @return instance of this class.
     * @throws SQLException on database errors.
     * @throws IOException on alias lookup.
     */
    public static EmagMirrorDB getEmagMirrorDB(String alias) throws SQLException, IOException {
        var mirrorDB = openDatabases.get(alias);
        if (mirrorDB == null) {
            var db = new DB(alias);
            try {
                SetupDB.setupAndUpdateDB(db);
            } catch (SQLException e) {
                String message = "Unable to setup database %s.".formatted(alias);
                logger.log(SEVERE, message, e);
                throw new IOException(message);
            }
            mirrorDB = new EmagMirrorDB(db);
            openDatabases.put(alias, mirrorDB);
        }
        return mirrorDB;
    }

    /**
     * Add or update the order in the database.
     *
     * @param order   as received from eMAG.
     * @param account The account name associated with the vendor.
     * @throws SQLException if something goes wrong.
     */
    public void addOrder(OrderResult order, String account) throws SQLException {
        database.writeTX(db -> {
            var vendorId = insertOrUpdateVendor(db, order.vendor_name(), account);
            return addOrderResult(order, db, vendorId, order.vendor_name());
        });
    }

    /**
     * Return all orders that are open, grouped by the vendor.
     *
     * @return Map from the eMAG account to new orders associated with that account.
     * @throws SQLException on database issues.
     */
    public Map<String, List<String>> readOrderIdForOpenOrdersByVendor() throws SQLException {
        return database.singleReadTX(db -> {
            var result = new HashMap<String, List<String>>();
            try (var s = db.prepareStatement("""
                    SELECT o.id, v.account
                    FROM emag_order AS o
                    INNER JOIN vendor AS v
                    ON o.vendor_id = v.id
                    WHERE status IN (1,2,3)
                    """)) {
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(2), _ -> new ArrayList<>()).add(rs.getString(1));
                    }
                }
            }
            return result;
        });
    }

    @FunctionalInterface
    public interface SQLFunction<R> {
        R apply(Connection db) throws SQLException;
    }

    /**
     * Execute a read operation on this database.
     *
     * @param readOp operation that reads the database.
     * @return result of read operation.
     * @param <T> type of result.
     * @throws SQLException if a database error occurs in readOp.
     */
    public <T> T read(SQLFunction<T> readOp) throws SQLException {
        return database.readTX(readOp::apply);
    }

    public List<EmagFetchHistogram> readFetchHistogram() throws SQLException {
        return database.readTX(EmagFetchLog::getFetchHistogram);
    }

    /**
     * Read orders in a format that is useful to update the employee sheets.
     *
     * @param pnk Product identifier.
     * @param startTime start time of the period to read orders for.
     * @param endTime end time of the period to read orders for.
     * @return list of orders in a format that is useful to update the employee sheets.
     * @throws SQLException if anything goes wrong with the database.
     */
    public List<EmployeeSheetData> readOrderData(String pnk, LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        return database.readTX(db -> getOrderDataByProductAndTime(db, pnk, startTime, endTime));
    }

    /**
     * Adds an RMA record to the database.
     *
     * @param rmaResult the RMA details to be added.
     * @throws SQLException if an error occurs during the database operation.
     */
    public void addRMA(RMAResult rmaResult) throws SQLException {
        database.writeTX(db -> addRMAResult(db, rmaResult));
    }


    public void addOrUpdateProduct(ProductInfo productInfo) throws SQLException {
        database.writeTX(db -> insertOrUpdateProduct(db, productInfo));
    }

    public void addEmagLog(String account, LocalDate date, LocalDateTime fetchTime, String error) throws SQLException {
        database.writeTX(db -> {
            var insertedRows = insertEmagLog(db, account, date, fetchTime, error);
            if (insertedRows == 0) {
                updateEmagLog(db, account, date, fetchTime, error);
            }
            return "";
        });
    }

    public LocalDateTime getLastFetchTimeByAccount(String account) throws SQLException {
        return database.singleReadTX(db -> selectFetchTimeByAccount(db, account));
    }

    public int saveLastFetchTime(String account, LocalDateTime fetchTime) throws SQLException {
        return database.writeTX(db -> updateFetchTimeByAccount(db, account, fetchTime));
    }

    public Optional<EmagFetchLog> getFetchStatus(String account, LocalDate date) throws SQLException {
        return database.readTX(db -> Optional.ofNullable(getEmagLog(db, account, date)));
    }

    public int deleteFetchLogsOlderThan(LocalDate oldestDay) throws SQLException {
        return database.writeTX(db -> deleteFetchLogsBefore(db, oldestDay));
    }

    public List<POInfo> readProductInOrderByProductAndMonth(String productCode, YearMonth yearMonth) throws SQLException {
        return database.readTX(db -> POInfo.getByProductAndMonth(db, productCode, yearMonth));
    }

    public List<ProductInfo> readProducts() throws SQLException {
        return database.readTX(ProductTable::getProducts);
    }

    /**
     * Update the GMV table based on all orders in the database.
     *
     * @throws SQLException on database errors.
     */
    public void updateGMVTable() throws SQLException {
        database.writeTX(EmagMirrorDB::computeGMV);
    }

    /**
     * Retrieve GMVs for a particular month.
     *
     * @param month to retrieve.
     * @return map with the product name as the key and the GMV as the value.
     * @throws SQLException on database error.
     */
    public Map<String, BigDecimal> readGMVByMonth(YearMonth month) throws SQLException {
        return database.readTX(db -> getGMVByMonth(db, month));
    }

    public SortedMap<ProductInfo, SortedMap<YearMonth, BigDecimal>> getGMVTable() throws SQLException {
        return database.readTX(EmagMirrorDB::getGMV);
    }

    /**
     * Retrieve the full GMV table.
     *
     * @param db database connection.
     * @return map from productInfo to map of month to GMV.
     * @throws SQLException on database error
     */
    public static SortedMap<ProductInfo, SortedMap<YearMonth, BigDecimal>> getGMV(Connection db) throws SQLException {
        var rows = new TreeMap<ProductInfo, SortedMap<YearMonth, BigDecimal>>(
                Comparator.comparing(ProductInfo::name)
        );
        for (ProductInfo product : getProducts(db)) {
            var gmvs = getGMVByProductId(db, product.productCode());
            rows.put(product, new TreeMap<>(gmvs));
        }
        return rows;
    }

    /**
     * Read database information and prepare them for inclusion in the spreadsheet.
     * Only orders with status finalised or returned matching the year are provided.
     *
     * @param year Return only orders where the date has this as its year value.
     * @return list of rows containing a list of cell groups. Each cell group is a list of cells.
     * @throws SQLException on database error
     */
    public List<List<List<Object>>> readForSheet(int year) throws SQLException {
        return database.readTX(db -> {
            List<List<List<Object>>> rows = new ArrayList<>();
            try (var s = db.prepareStatement(
                    //language=sql
                    """
                            SELECT
                              o.date,
                              o.id,
                              o.status,
                              pi.name,
                              p.quantity,
                              p.sale_price,
                              o.delivery_mode,
                              c.name,
                              c.shipping_phone,
                              c.billing_name,
                              c.billing_phone,
                              c.code,
                              o.observation,
                              v.vendor_name,
                              v.isFBE,
                              pi.message_keyword,
                              p.currency,
                              pi.product_code,
                              pi.emag_pnk,
                              p.vat,
                              o.detailed_payment_method,
                              o.payment_status,
                              CONCAT_WS(', ', c.billing_locality_id, c.billing_street, c.billing_country, c.billing_postal_code, c.billing_suburb, c.billing_city) AS billing_address,
                              CONCAT_WS(', ', c.shipping_locality_id, c.shipping_street, c.shipping_country, c.shipping_postal_code, c.shipping_suburb, c.shipping_city) AS shipping_address,
                              p.storno_qty as storno_quantity
                            FROM emag_order as o
                            LEFT JOIN customer as c
                            ON o.customer_id = c.id
                            LEFT JOIN vendor as v
                            ON o.vendor_id = v.id
                            INNER JOIN product_in_order as p
                            ON p.emag_order_surrogate_id = o.surrogate_id
                            LEFT JOIN product as pi
                            ON p.part_number_key = pi.emag_pnk
                            WHERE EXTRACT(YEAR FROM o.date) = ? AND (o.status = 4 OR o.status = 5)
                            ORDER BY o.date
                            """)) {
                s.setInt(1, year);
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        var priceWithoutVAT = rs.getBigDecimal(6);
                        var vatRate = new BigDecimal(rs.getString("vat"));
                        var vat = priceWithoutVAT.multiply(vatRate);
                        var priceWithVAT = priceWithoutVAT.add(vat);
                        String customerName = rs.getString(8);
                        boolean isFBE = rs.getBoolean(15);
                        String modPlata = rs.getString(21);
                        String statusPlata = modPlata.equals("RAMBURS") ? "Ramburs" : rs.getInt(22) == 1 ? "Incasata" : "Neincasata";
                        int status = rs.getInt(3);
                        int quantity = status == 5 ? -rs.getInt("storno_quantity") : rs.getInt(5);
                        var row = List.of(
                                // Group 0
                                Stream.of(
                                        toLocalDateTime(rs.getTimestamp(1)).toString(), // creation date
                                        rs.getString(2), // id
                                        Conversions.statusToString(status), // status
                                        rs.getString(18), // product code
                                        rs.getString(19), // PNK
                                        rs.getString(4), // Product name
                                        quantity, // quantity
                                        priceWithoutVAT.setScale(2, HALF_EVEN),
                                        priceWithVAT.setScale(2, HALF_EVEN),
                                        rs.getString(17), // Currency
                                        rs.getString(20), // TVA
                                        modPlata, // Mod plata
                                        statusPlata,
                                        rs.getString(7), // deliver mode
                                        customerName, // customerName
                                        customerName, // customer shipment name
                                        rs.getString(9), // customer shipping phone
                                        rs.getString("shipping_address"),
                                        rs.getString(10), // customer billing name
                                        rs.getString(11), // customer billing phone
                                        rs.getString("billing_address"),
                                        rs.getString(12), // company code
                                        rs.getString(13) // observation
                                ).map(Object.class::cast).toList(),
                                // Group 1
                                Stream.of(rs.getString(14), // vendor
                                        Conversions.booleanToFBE(isFBE) // platform
                                ).map(Object.class::cast).toList(),
                                // Group 2
                                Stream.of(isFBE, isFBE, isFBE, false).map(Object.class::cast).toList(),
                                // Group 3
                                Stream.of(rs.getString(16)).map(Object.class::cast).toList());
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    /**
     * Given the vendor name, find the UUID.
     *
     * @param vendorName vendor name
     * @return UUID for vendor
     * @throws SQLException for any database errors
     */
    public UUID getVendorByName(String vendorName) throws SQLException {
        return database.readTX(db -> selectVendorIdByName(db, vendorName));
    }

    /**
     * <b>DEBUG ONLY!</b>
     *
     * <p>
     *     This returns the orders, which are not fully filled for a given vendor, time range, and status.
     * </p>
     * @param startTime start time inclusive
     * @param endTime end time exclusive
     * @param vendorId vendor UUID
     * @return Orders that are missing all dependents like products, attachments, and so on.
     * @throws SQLException for database errors.
     */
    public List<OrderResult> readOrderByDateAndHardCodedStatus(LocalDateTime startTime, LocalDateTime endTime, UUID vendorId) throws SQLException {
        return database.readTX(db -> {
            List<OrderResult> results = new ArrayList<>();
            try (var s = db.prepareStatement("SELECT * FROM emag_order WHERE vendor_id=? and modified >= ? and modified <? and status in (5)")) {
                OrderResult order;
                s.setObject(1, vendorId);
                s.setTimestamp(2, toTimestamp(startTime));
                s.setTimestamp(3, toTimestamp(endTime));
                //s.setObject(4, statusList);
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        order = new OrderResult(
                                "", rs.getString("id"), rs.getInt("status"), rs.getInt("is_complete"), rs.getInt("type"), rs.getString("payment_mode"),
                                rs.getInt("payment_mode_id"), rs.getString("delivery_payment_mode"), rs.getString("delivery_mode"), rs.getString("observation"),
                                new LockerDetails(rs.getString("details_id"), null, 0, null), // Assuming LockerDetails has a constructor that takes locker_id
                                toLocalDateTime(rs.getTimestamp("date")), rs.getInt("payment_status"), rs.getBigDecimal("cashed_co"), rs.getBigDecimal("cashed_cod"),
                                rs.getBigDecimal("shipping_tax"),
                                null, null, null, null, null,
                                rs.getBoolean("is_storno"),
                                rs.getBigDecimal("refunded_amount"),
                                rs.getString("refund_status"),
                                toLocalDateTime(rs.getTimestamp("maximum_date_for_shipment")),
                                toLocalDateTime(rs.getTimestamp("finalization_date")),
                                rs.getString("parent_id"),
                                rs.getString("detailed_payment_method"),
                                Arrays.asList(rs.getString("proforms").split("\n")), // Split the string back into a list
                                rs.getString("cancellation_request"),
                                rs.getInt("has_editable_products"),
                                new CancellationReason(rs.getObject("cancellation_reason", Integer.class),
                                        rs.getString("cancellation_reason_text")),
                                rs.getObject("late_shipment", Integer.class), // Assuming late_shipment is a Timestamp
                                null, rs.getInt("emag_club"),
                                rs.getInt("weekend_delivery"),
                                toLocalDateTime(rs.getTimestamp("created")),
                                toLocalDateTime(rs.getTimestamp("modified")),
                                null
                        );
                        results.add(order);
                    }
                }
            }
            return results;
        });
    }

    /**
     * Read database information and prepare them for inclusion in the spreadsheet.
     *
     * @return list of rows containing a list of cell groups. Each cell group is a list of cells.
     * @throws SQLException on database error
     */
    public List<List<Object>> readForComparisonApp() throws SQLException {
        return database.readTX(db -> {
            List<List<Object>> rows = new ArrayList<>();
            try (var s = db.prepareStatement(
                    //language=sql
                    """
                            SELECT
                              o.id,
                              v.vendor_name,
                              v.isFBE,
                              p.part_number_key,
                              o.date,
                              o.status,
                              o.type,
                              pi.name,
                              p.quantity,
                              p.sale_price,
                              o.delivery_mode,
                              c.name,
                              c.shipping_phone,
                              c.billing_name,
                              c.billing_phone,
                              c.code,
                              o.observation,
                              pi.message_keyword,
                              p.vat
                            FROM emag_order as o
                            LEFT JOIN customer as c
                            ON o.customer_id = c.id
                            LEFT JOIN vendor as v
                            ON o.vendor_id = v.id
                            LEFT JOIN product_in_order as p
                            ON p.emag_order_surrogate_id = o.surrogate_id
                            LEFT JOIN product as pi
                            ON p.part_number_key = pi.emag_pnk
                            """)) {
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        var priceWithoutVAT = rs.getBigDecimal(6);
                        var vatRate = new BigDecimal(rs.getString("vat"));
                        var vat = priceWithoutVAT.multiply(vatRate);
                        var priceWithVAT = priceWithoutVAT.add(vat);
                        String customerName = rs.getString(8);
                        var row = Arrays.<Object>asList(rs.getString(1), // id
                                rs.getString(2), // company name
                                rs.getBoolean(3), // platform
                                rs.getString(4), // PNK
                                toLocalDateTime(rs.getTimestamp(5)), // creation date
                                rs.getInt(6), // status
                                rs.getInt(7) // type
                                        /*
                                        // Group 0
                                        Stream.of(
                                                toLocalDateTime(rs.getTimestamp(1)).format(formatDate), // creation date
                                                statusToString(rs.getInt(3)) // status
                                        ).map(Object.class::cast).toList(),
                                        // Group 1
                                        Stream.of(
                                                rs.getString(4),
                                                rs.getInt(5), // quantity
                                                priceWithoutVAT.setScale(2, HALF_EVEN),
                                                priceWithVAT.setScale(2, HALF_EVEN)
                                        ).map(Object.class::cast).toList(),
                                        // Group 2
                                        Stream.of(
                                                rs.getString(7), // deliver mode
                                                customerName, // customerName
                                                customerName, // customer shipment name
                                                rs.getString(9) // customer shipping phone
                                        ).map(Object.class::cast).toList(),
                                        // Group 3
                                        Stream.of(
                                                rs.getString(10), // customer billing name
                                                rs.getString(11) // customer billing phone
                                        ).map(Object.class::cast).toList(),
                                        // Group 4
                                        Stream.of(
                                                rs.getString(12), // company code
                                                rs.getString(13) // observation
                                        ).map(Object.class::cast).toList(),
                                        // Group 5
                                        Stream.of(
                                        ).map(Object.class::cast).toList(),
                                        // Group 6
                                        Stream.of(
                                                rs.getBoolean(2),
                                                rs.getBoolean(2),
                                                rs.getBoolean(2),
                                                false
                                        ).map(Object.class::cast).toList(),
                                        // Group 7
                                        Stream.of(
                                                rs.getString(16)
                                        ).map(Object.class::cast).toList()

                                        */);
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    public Map<Integer, List<Product>> readAllProducts() throws SQLException {
        return database.readTX(EmagOrder::selectAllProduct);
    }

    public HashMap<String, List<ExtendedOrder>> readAllOrders(Map<Integer, List<Product>> allProducts, Map<UUID, String> allVendors) throws SQLException {
        return database.readTX(db -> EmagOrder.selectAllOrders(db, allProducts, allVendors));
    }

    public @NonNull Map<UUID, String> readVendors() throws SQLException {
        return database.readTX(Vendor::selectAllVendors);
    }


    private static boolean computeGMV(Connection db) throws SQLException {
        var products = getProductCodes(db);
        for (String productCode : products) {
            var ordersWithProduct = getOrderDataByProduct(db, productCode);
            computeAndStoreGMVForProduct(db, productCode, ordersWithProduct);
        }
        return true;
    }

    private List<EmployeeSheetData> getOrderDataByProductAndTime(Connection db, String pnk, LocalDateTime startTime, LocalDateTime endTime) throws SQLException {
        var list = new ArrayList<EmployeeSheetData>();
        try (var s = db.prepareStatement(
                //language=postgres-sql
                """
                        SELECT
                          o.id,
                          p.quantity,
                          p.sale_price,
                          c.legal_entity,
                          o.date,
                          pi.name AS product_name,
                          p.part_number_key,
                          c.name,
                          c.billing_name,
                          c.billing_phone,
                          CONCAT_WS(', ', c.billing_locality_id, c.billing_street, c.billing_country, c.billing_postal_code, c.billing_suburb, c.billing_city) AS billing_address,
                          c.name AS customer_name,
                          c.shipping_phone,
                          CONCAT_WS(', ', c.shipping_locality_id, c.shipping_street, c.shipping_country, c.shipping_postal_code, c.shipping_suburb, c.shipping_city) AS shipping_address,
                          o.delivery_mode,
                          p.currency,
                          p.vat,
                          o.status,
                          v.vendor_name,
                          c.shipping_suburb,
                          c.shipping_city
                        FROM emag_order as o
                        LEFT JOIN customer as c
                        ON o.customer_id = c.id
                        LEFT JOIN vendor as v
                        ON o.vendor_id = v.id
                        INNER JOIN product_in_order as p
                        ON p.emag_order_surrogate_id = o.surrogate_id
                        LEFT JOIN product as pi
                        ON p.part_number_key = pi.emag_pnk
                        WHERE (o.status = 4 OR o.status = 5) AND p.part_number_key = ? AND ? < o.date AND o.date < ?
                        ORDER BY o.date
                        """)) {
            s.setString(1, pnk);
            s.setTimestamp(2, toTimestamp(startTime));
            s.setTimestamp(3, toTimestamp(endTime));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var priceWithoutVAT = rs.getBigDecimal("sale_price");
                    var vatRate = new BigDecimal(rs.getString("vat"));
                    var vat = priceWithoutVAT.multiply(vatRate);
                    var priceWithVAT = priceWithoutVAT.add(vat);
                    int status = rs.getInt("status");
                    var data = new EmployeeSheetData(
                            rs.getString("id"),
                            rs.getString("vendor_name"),
                            rs.getInt("quantity"),
                            priceWithVAT,
                            rs.getInt("legal_entity") > 0,
                            toLocalDateTime(rs.getTimestamp("date")),
                            rs.getString("product_name"),
                            rs.getString("part_number_key"),
                            rs.getString("customer_name"),
                            rs.getString("billing_name"),
                            rs.getString("billing_phone"),
                            rs.getString("billing_address"),
                            rs.getString("shipping_suburb"),
                            rs.getString("shipping_city"),
                            rs.getString("customer_name"),
                            rs.getString("shipping_phone"),
                            rs.getString("shipping_address"),
                            rs.getString("delivery_mode"),
                            false
                    );
                    list.add(data);

                }
            }
        }
        return list;
    }

    private static Map<String, List<POInfo>> getOrderDataByProduct(Connection db, String productCode) throws SQLException {
        var result = new HashMap<String, List<POInfo>>();
        try (var s = db.prepareStatement("""
                SELECT p.name AS productName,
                pio.quantity AS quantity,
                pio.initial_qty AS initialQuantity,
                pio.storno_qty AS stornoQuantity,
                pio.sale_price AS salePrice,
                pio.vat AS vat,
                pio.created, pio.modified,
                o.status AS orderStatus,
                o.date AS orderDate,
                o.modified,
                o.id AS orderId,
                o.surrogate_id AS orderSurrogateId,
                pio.id AS pioId
                FROM product_in_order AS pio
                INNER JOIN product AS p ON p.emag_pnk = pio.part_number_key
                INNER JOIN emag_order AS o ON pio.emag_order_surrogate_id = o.surrogate_id
                WHERE p.product_code = ? AND (o.status = 4 OR o.status = 5)
                ORDER BY o.id, o.status
                """)) {
            s.setObject(1, productCode);
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var orderId = rs.getString("orderId");
                    var poInfos = result.getOrDefault(orderId, new ArrayList<>());
                    var quantity = rs.getInt("quantity");
                    var initialQuantity = rs.getInt("initialQuantity");
                    var stornoQuantity = rs.getInt("stornoQuantity");
                    var salePrice = rs.getBigDecimal("salePrice");
                    var vat = new BigDecimal(rs.getString("vat"));
                    var price = vat.add(BigDecimal.ONE).multiply(salePrice).setScale(2, HALF_EVEN);
                    var poInfo = new POInfo(
                            orderId,
                            rs.getInt("orderSurrogateId"),
                            toLocalDate(rs.getTimestamp("orderDate")),
                            rs.getInt("orderStatus"),
                            rs.getInt("pioId"),
                            rs.getString("productName"),
                            quantity,
                            initialQuantity,
                            stornoQuantity,
                            price
                    );
                    poInfos.add(poInfo);
                    result.put(orderId, poInfos);
                }

            }
        }
        return result;
    }
}