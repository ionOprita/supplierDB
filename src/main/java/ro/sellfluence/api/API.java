package ro.sellfluence.api;

import com.google.gson.Gson;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagMirrorDB.ReturnStronoDetail;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.support.DoubleWindow;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The API for the frontend.
 */
public class API {

    private final EmagMirrorDB mirrorDB;

    private static final Gson gson = new Gson();

    public API(EmagMirrorDB db) {
        mirrorDB = db;
    }

    record ProductForFrontend(String name, String id) {
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
    public List<CountByDate> getStornos(String id) {
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
     * Retrieves the count of the stornos by month for all products, within the past 2 years.
     *
     * @return map from product to map of month to the storno count.
     * @throws SQLException on database error.
     */
    public Map<ProductInfo, Map<YearMonth, Integer>> getStornosByProductAndMonth() throws SQLException {
        var result = new TreeMap<ProductInfo, Map<YearMonth, Integer>>(ProductInfo.nameComparator);
        var products = mirrorDB.readProducts().stream().sorted(ProductInfo.nameComparator).toList();
        var end = YearMonth.now();
        var month = end.minusYears(2);
        while (month.isBefore(end)) {
            var storno = mirrorDB.countStornoByMonth(month);
            for (ProductInfo product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                map.put(month, storno.getOrDefault(product.pnk(), 0));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    public List<ReturnStronoDetail> stornoDetails(String pnk, YearMonth month) throws SQLException {
        return mirrorDB.getStornoDetails(pnk, month);
    }

    /**
     * Retrieves the count of the stornos by month for all products, within the past 2 years.
     *
     * @return map from product to map of month to the storno count.
     * @throws SQLException on database error.
     */
    public Map<ProductInfo, Map<YearMonth, Integer>> getReturnsByProductAndMonth() throws SQLException {
        var result = new TreeMap<ProductInfo, Map<YearMonth, Integer>>(ProductInfo.nameComparator);
        var products = mirrorDB.readProducts().stream().sorted(ProductInfo.nameComparator).toList();
        var end = YearMonth.now();
        var month = end.minusYears(2);
        while (month.isBefore(end)) {
            var countByPNK = mirrorDB.countReturnByMonth(month);
            for (ProductInfo product : products) {
                var map = result.computeIfAbsent(product, _ -> new HashMap<>());
                map.put(month, countByPNK.getOrDefault(product.pnk(), 0));
            }
            month = month.plusMonths(1);
        }
        return result;
    }

    public List<ReturnStronoDetail> returnDetails(String pnk, YearMonth month) throws SQLException {
        return mirrorDB.getReturnDetails(pnk, month);
    }

    /**
     * Retrieves the count of items by date for a given ID, within the past 12 months.
     * The result is returned as a JSON string containing a list of date-count pairs.
     *
     * @param id        the identifier for which the counts are to be retrieved
     * @param cache     the cache holding previously retrieved counts to minimise database calls
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
     * @param cache     the cache holding previously retrieved counts to minimise database calls.
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