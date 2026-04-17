package ro.sellfluence.api;

import com.google.gson.Gson;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagMirrorDB.ReturnStornoOrderDetail;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.db.ProductTable.ProductWithVendor;
import ro.sellfluence.db.Task;
import ro.sellfluence.support.DoubleWindow;
import ro.sellfluence.support.Statistics;
import ro.sellfluence.support.Statistics.Estimate;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.logging.Level.SEVERE;
import static ro.sellfluence.api.API.ProductForFrontend.nameComparator;
import static ro.sellfluence.db.ProductTable.ProductInfo.nameComparatorString;

/**
 * The API for the frontend.
 */
public class API {

    private final EmagMirrorDB mirrorDB;

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(API.class.getName());

    public API(EmagMirrorDB db) {
        mirrorDB = db;
    }

    record ProductForFrontend(String name, String id) {
        public static final Comparator<ProductForFrontend> nameComparator = Comparator.comparing(ProductForFrontend::name, nameComparatorString);
    }

    record CachedDailyAmounts(LocalDateTime lastRead, Map<LocalDate, Integer> cachedData) {
    }

    private static final Map<String, CachedDailyAmounts> cachedDailyOrders = new HashMap<>();
    private static final Map<String, CachedDailyAmounts> cachedDailyStorno = new HashMap<>();
    private static final Map<String, CachedDailyAmounts> cachedDailyReturns = new HashMap<>();

    public record CountByDate(LocalDate date, int count) {
    }

    public record ValueByDate(LocalDate date, double value) {
    }

    public record SmoothedRateByDate(LocalDate date,
                                     long soldQty,
                                     long returnedQty,
                                     Double rawRate,
                                     double smoothedRate,
                                     boolean reliable) {
    }

    public record CurrentMonthRatesRow(String name, String pnk, Double returnRate, Double stornoRate) {
    }

    private interface Retriever<T, V> {
        V retrieve(T t) throws SQLException;
    }

    /**
     * Retrieves a list of products from the database and returns it as a JSON string.
     * Each product includes its name and product code.
     * In case of a database access error, the method returns null.
     *
     * @return a JSON string representing a list of products, or null if a database error occurs.
     */
    public String getProducts() {
        try {
            var productList = mirrorDB.readProducts().stream()
                    .map(it -> new ProductForFrontend(it.name(), it.productCode()))
                    .sorted(nameComparator)
                    .toList();
            return gson.toJson(productList);
        } catch (SQLException e) {
            return null;
        }
    }

    public List<ValueByDate> getRRR(String id) {
        var orders = getOrders(id);
        var startDate = orders.getFirst().date();
        var endDate = orders.getLast().date();
        var returns = getReturns(id);
        if (returns.getFirst().date().isAfter(startDate)) {
            startDate = returns.getFirst().date();
        }
        if (returns.getLast().date().isBefore(endDate)) {
            endDate = returns.getLast().date();
        }
        var ordersByDate = orders.stream().collect(Collectors.toMap(CountByDate::date, CountByDate::count));
        var returnsByDate = returns.stream().collect(Collectors.toMap(CountByDate::date, CountByDate::count));
        var orderWindow = new DoubleWindow(90);
        var returnWindow = new DoubleWindow(90);
        var date = startDate;
        var result = new ArrayList<ValueByDate>();
        while (!date.isAfter(endDate)) {
            orderWindow.add(ordersByDate.get(date));
            returnWindow.add(returnsByDate.get(date));
            if (orderWindow.isFull() && returnWindow.isFull()) {
                result.add(new ValueByDate(date, returnWindow.getSum() / orderWindow.getSum()));
            }
            date = date.plusDays(1);
        }
        return result;
    }

    /**
     * Return the rolling return rate for a product.
     *
     * @param id of the product.
     * @return The computed RRR both raw and smoothed.
     */
    public List<SmoothedRateByDate> getCohortSmoothedRRR(String id) {
        try {
            //TODO: Figure out if the parameters are appropriate.
            return mirrorDB.getCohortSmoothedRollingReturnRate(id, 90, 90, 100, 20).stream()
                    .map(it -> new SmoothedRateByDate(
                            it.date(),
                            it.soldQty(),
                            it.returnedQty(),
                            it.rawRate(),
                            it.smoothedRate(),
                            it.reliable()
                    ))
                    .toList();
        } catch (SQLException e) {
            logger.log(SEVERE, "Failed to compute cohort smoothed RRR for product " + id, e);
            return null;
        }
    }

    /**
     * Retrieves the order counts by date for a given product ID, within the past 12 months.
     * The result is returned as a JSON string containing a list of date-count pairs.
     *
     * @param id the product ID for which the order counts are to be retrieved.
     * @return a JSON string representation of order counts by date
     */
    public List<CountByDate> getOrders(String id) {
        return getCountById(id, cachedDailyOrders, mirrorDB::countOrdersByDayForProduct);
    }

    /**
     * Retrieves the number of storno orders by date for a given product ID, within the past 12 months.
     * The result is returned as a JSON string containing a list of date-count pairs.
     *
     * @param id the product ID for which the order counts are to be retrieved.
     * @return a JSON string representation of order counts by date
     */
    public List<CountByDate> getStorno(String id) {
        return getCountById(id, cachedDailyStorno, mirrorDB::countStornoByDayForProduct);
    }

    /**
     * Retrieves the number of returns by date for a given product ID, within the past 12 months.
     * The result is returned as a JSON string containing a list of date-count pairs.
     *
     * @param id the product ID for which the order counts are to be retrieved.
     * @return a JSON string representation of order counts by date
     */
    public List<CountByDate> getReturns(String id) {
        return getCountById(id, cachedDailyReturns, mirrorDB::countReturnByDayForProduct);
    }

    /**
     * Retrieves the count of the orders by month for all products, within the past 2 years.
     *
     * @return map from product to map of month to the storno count.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, Integer>> getOrdersByProductAndMonth() throws SQLException {
        var result = new LinkedHashMap<ProductWithVendor, Map<YearMonth, Integer>>();
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var end = YearMonth.now();
        var month = end.minusYears(2);
        while (month.isBefore(end)) {
            var ordersByMonth = mirrorDB.countOrdersByMonth(month);
            for (ProductWithVendor product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                map.put(month, ordersByMonth.getOrDefault(product.pnk(), 0));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    public record MonthStats(int orderCount, int returnCount, int stornoCount, int refusedCount, Estimate returnRate,
                             Estimate stornoRate, Estimate refusedRate) {
    }

    private record MonthCountMaps(
            Map<String, Map<YearMonth, Integer>> ordersByKey,
            Map<String, Map<YearMonth, Integer>> returnsByKey,
            Map<String, Map<YearMonth, Integer>> stornoByKey
    ) {
    }

    /**
     * Get statistics for each month in [startMonth, endMonth), aggregating over the preceding {@code aggregateMonths}.
     *
     * @param aggregateMonths number of months over which to aggregate the sale values.
     * @param confidenceLevel
     * @param startMonth first month for which to return an aggregated statistic.
     * @param endMonth first month after the returned range.
     * @return map from product to map of month to the monthly statistics.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, MonthStats>> getMonthStats(YearMonth startMonth, YearMonth endMonth, int aggregateMonths, double confidenceLevel) throws SQLException {
        if (aggregateMonths <= 0) {
            throw new IllegalArgumentException("aggregateMonths must be > 0");
        }
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var aggregateStartMonth = startMonth.minusMonths(aggregateMonths);
        return buildMonthStats(
                products,
                ProductWithVendor::pnk,
                getMonthCountMaps(aggregateStartMonth, endMonth),
                startMonth,
                endMonth,
                aggregateMonths,
                confidenceLevel
        );
    }

    public Map<String, Map<YearMonth, MonthStats>> getMonthStatsByCategory(YearMonth startMonth,
                                                                            YearMonth endMonth,
                                                                            int aggregateMonths,
                                                                            double confidenceLevel) throws SQLException {
        if (aggregateMonths <= 0) {
            throw new IllegalArgumentException("aggregateMonths must be > 0");
        }
        var products = mirrorDB.readProducts().stream()
                .sorted(ProductInfo.nameComparator)
                .toList();
        var aggregateStartMonth = startMonth.minusMonths(aggregateMonths);
        var countsByProduct = getMonthCountMaps(aggregateStartMonth, endMonth);
        var countsByCategory = new MonthCountMaps(
                aggregateCountsByCategory(products, countsByProduct.ordersByKey()),
                aggregateCountsByCategory(products, countsByProduct.returnsByKey()),
                aggregateCountsByCategory(products, countsByProduct.stornoByKey())
        );
        var categories = products.stream()
                .map(product -> normalizeCategory(product.category()))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return buildMonthStats(
                categories,
                Function.identity(),
                countsByCategory,
                startMonth,
                endMonth,
                aggregateMonths,
                confidenceLevel
        );
    }

    public List<ReturnStornoOrderDetail> orderDetails(String pnk, YearMonth month) throws SQLException {
        return mirrorDB.getOrderDetails(pnk, month);
    }

    /**
     * Retrieves the count of the storno by month for all products, within the past 2 years.
     *
     * @return map from product to map of month to the storno count.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, Integer>> getStornoByProductAndMonth() throws SQLException {
        var result = new LinkedHashMap<ProductWithVendor, Map<YearMonth, Integer>>();
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var end = YearMonth.now();
        var month = end.minusYears(2);
        while (month.isBefore(end)) {
            var storno = mirrorDB.countStornoByMonth(month);
            for (ProductWithVendor product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                map.put(month, storno.getOrDefault(product.pnk(), 0));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    public List<ReturnStornoOrderDetail> stornoDetails(String pnk, YearMonth month) throws SQLException {
        return mirrorDB.getStornoDetails(pnk, month);
    }

    /**
     * Retrieves the count of the returns by month for all products, within the past 2 years.
     *
     * @return map from product to map of month to the storno count.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, Integer>> getReturnsByProductAndMonth() throws SQLException {
        var result = new LinkedHashMap<ProductWithVendor, Map<YearMonth, Integer>>();
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var end = YearMonth.now();
        var month = end.minusYears(2);
        while (month.isBefore(end)) {
            var countByPNK = mirrorDB.countReturnByMonth(month);
            for (ProductWithVendor product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                map.put(month, countByPNK.getOrDefault(product.pnk(), 0));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    /**
     * Retrieves the storno percentage by month for all products, within the past 2 years.
     * For month M, the value is avg(storno in M-3..M-1) / avg(orders in M-3..M-1).
     *
     * @return map from product to map of month to storno percentage.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, Double>> getStornoRateByProductAndMonth() throws SQLException {
        var result = new LinkedHashMap<ProductWithVendor, Map<YearMonth, Double>>();
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var end = YearMonth.now();
        var start = end.minusYears(2);
        var ratioByPNK = mirrorDB.getStornoRateByProductAndMonth(start, end);
        var month = start;
        while (month.isBefore(end)) {
            for (ProductWithVendor product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                var value = ratioByPNK.getOrDefault(product.pnk(), Map.of()).get(month);
                map.put(month, value);
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    /**
     * Retrieves the return percentage by month for all products, within the past 2 years.
     * For month M, the value is avg(returns in M-3..M-1) / avg(orders in M-3..M-1).
     *
     * @return map from product to map of month to return percentage.
     * @throws SQLException on database error.
     */
    public Map<ProductWithVendor, Map<YearMonth, Double>> getReturnRateByProductAndMonth() throws SQLException {
        var result = new LinkedHashMap<ProductWithVendor, Map<YearMonth, Double>>();
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var end = YearMonth.now();
        var start = end.minusYears(2);
        var ratioByPNK = mirrorDB.getReturnRateByProductAndMonth(start, end);
        var month = start;
        while (month.isBefore(end)) {
            for (ProductWithVendor product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                var value = ratioByPNK.getOrDefault(product.pnk(), Map.of()).get(month);
                map.put(month, value);
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    /**
     * Retrieves a compact table of current-month percentages for all products.
     * The value for the current month M is computed as avg(M-3..M-1) numerator / avg(M-3..M-1) orders.
     *
     * @return list of rows with product name, PNK, return-percentage, and storno-percentage.
     * @throws SQLException on database error.
     */
    public List<CurrentMonthRatesRow> getCurrentMonthRatesTable() throws SQLException {
        var products = mirrorDB.readProductsWithVendor().stream()
                .sorted(ProductWithVendor.nameComparator)
                .toList();
        var month = YearMonth.now();
        var end = month.plusMonths(1);
        var returnRateByPNK = mirrorDB.getReturnRateByProductAndMonth(month, end);
        var stornoRateByPNK = mirrorDB.getStornoRateByProductAndMonth(month, end);
        var result = new ArrayList<CurrentMonthRatesRow>(products.size());
        for (ProductWithVendor product : products) {
            var returnRate = returnRateByPNK.getOrDefault(product.pnk(), Map.of()).get(month);
            var stornoRate = stornoRateByPNK.getOrDefault(product.pnk(), Map.of()).get(month);
            result.add(new CurrentMonthRatesRow(product.name(), product.pnk(), returnRate, stornoRate));
        }
        return result;
    }

    private MonthCountMaps getMonthCountMaps(YearMonth startMonth, YearMonth endMonth) throws SQLException {
        return new MonthCountMaps(
                mirrorDB.countOrdersByMonth(startMonth, endMonth),
                mirrorDB.countReturnByMonth(startMonth, endMonth),
                mirrorDB.countStornoByMonth(startMonth, endMonth)
        );
    }

    private Map<String, Map<YearMonth, Integer>> aggregateCountsByCategory(List<ProductInfo> products,
                                                                           Map<String, Map<YearMonth, Integer>> countsByProduct) {
        var result = new HashMap<String, Map<YearMonth, Integer>>();
        for (ProductInfo product : products) {
            var category = normalizeCategory(product.category());
            var counts = countsByProduct.get(product.pnk());
            if (counts == null) {
                continue;
            }
            var categoryCounts = result.computeIfAbsent(category, _ -> new HashMap<>());
            for (var entry : counts.entrySet()) {
                categoryCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return result;
    }

    private <T> Map<T, Map<YearMonth, MonthStats>> buildMonthStats(List<T> rows,
                                                                   Function<T, String> keyExtractor,
                                                                   MonthCountMaps counts,
                                                                   YearMonth startMonth,
                                                                   YearMonth endMonth,
                                                                   int aggregateMonths,
                                                                   double confidenceLevel) {
        var result = new LinkedHashMap<T, Map<YearMonth, MonthStats>>();
        var month = startMonth;
        while (month.isBefore(endMonth)) {
            var aggregateStart = month.minusMonths(aggregateMonths);
            for (T row : rows) {
                var key = keyExtractor.apply(row);
                var map = result.computeIfAbsent(row, _ -> new HashMap<>());
                var ordersLastNMonths = Statistics.sumOver(aggregateStart, month, counts.ordersByKey().get(key));
                var returnsLastNMonths = Statistics.sumOver(aggregateStart, month, counts.returnsByKey().get(key));
                var stornoLastNMonths = Statistics.sumOver(aggregateStart, month, counts.stornoByKey().get(key));
                var refusedLastNMonths = stornoLastNMonths - returnsLastNMonths;
                var returnsRate = Statistics.estimateRateOrNull(returnsLastNMonths, ordersLastNMonths, confidenceLevel);
                var stornoRate = Statistics.estimateRateOrNull(stornoLastNMonths, ordersLastNMonths, confidenceLevel);
                var refusedRate = Statistics.estimateRateOrNull(refusedLastNMonths, ordersLastNMonths, confidenceLevel);
                map.put(month, new MonthStats(ordersLastNMonths, returnsLastNMonths, stornoLastNMonths, refusedLastNMonths, returnsRate, stornoRate, refusedRate));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    private static String normalizeCategory(String category) {
        return category == null ? "" : category.trim();
    }

    public List<ReturnStornoOrderDetail> returnDetails(String pnk, YearMonth month) throws SQLException {
        return mirrorDB.getReturnDetails(pnk, month);
    }

    public List<Task> getTasks() throws SQLException {
        return mirrorDB.getAllTasks();
    }

    /**
     * Retrieves the count of items by date for a given ID, within the past 12 months.
     * The result is returned as a JSON string containing a list of date-count pairs.
     *
     * @param id        the identifier for which the counts are to be retrieved
     * @param cache     the cache holding previously retrieved counts to minimize database calls
     * @param retriever a retriever function to fetch counts from the database if not in the cache.
     * @return a JSON string representation of counts by date
     */
    private List<CountByDate> getCountById(String id, final Map<String, CachedDailyAmounts> cache, final Retriever<String, Map<LocalDate, Integer>> retriever) {
        var ordersByDate = getOrRefresh(cache, id, retriever);
        var date = YearMonth.now().minusMonths(12).atDay(1);
        var end = LocalDate.now();
        var result = new ArrayList<CountByDate>();
        while (date.isBefore(end)) {
            result.add(
                    new CountByDate(
                            date,
                            ordersByDate.getOrDefault(date, 0)
                    )
            );
            date = date.plusDays(1);
        }
        return result;
    }

    /**
     * Fetches a map of item counts by date from the cache or retrieves the data from the database if
     * the cached data is outdated or missing. If retrieved from the database, the cache is updated.
     *
     * @param cache     the cache holding previously retrieved counts to minimize database calls.
     * @param id        the identifier for which the counts are to be retrieved.
     * @param retriever the retriever function that fetches counts from the database if not present in the cache.
     * @return a map where the keys are dates and the values are item counts for the specified ID.
     * @throws RuntimeException if unable to retrieve data from the external source due to an SQL error.
     */
    private Map<LocalDate, Integer> getOrRefresh(Map<String, CachedDailyAmounts> cache, String id, Retriever<String, Map<LocalDate, Integer>> retriever) {
        var cachedValue = cache.get(id);
        LocalDateTime now = LocalDateTime.now();
        if (cachedValue == null || cachedValue.lastRead.isBefore(now.minusHours(1))) {
            CachedDailyAmounts newCachedValue;
            try {
                newCachedValue = new CachedDailyAmounts(now, retriever.retrieve(id));
            } catch (SQLException e) {
                throw new RuntimeException("Could not read from the database for the product %s".formatted(id), e);
            }
            cache.put(id, newCachedValue);
            return newCachedValue.cachedData;
        } else {
            return cachedValue.cachedData;
        }
    }
}
