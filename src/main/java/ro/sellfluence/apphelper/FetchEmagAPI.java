package ro.sellfluence.apphelper;

import ro.sellfluence.db.EmagFetchLog;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.EmagAccounts;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.Logs;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.db.EmagFetchLog.isDone;
import static ro.sellfluence.support.Time.timeE;

public class FetchEmagAPI {

    private static final Logger warnLogger = Logs.getConsoleAndFileLogger("EmagDBAppWarnings", WARNING, 5, 10_000_000);
    private static final Logger consoleLogger = Logs.getConsoleLogger("EmagDBApp", INFO);

    private static final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private static final LocalDate today = LocalDate.now();

    public static Boolean fetchRMAs(EmagMirrorDB mirrorDB) {
        for (UserPassword emagAccount : EmagAccounts.getAccounts(mirrorDB)) {
            System.out.println(emagAccount);
            try {
                transferRMAsToDatabase(emagAccount, mirrorDB, LocalDate.now().minusMonths(6).atStartOfDay(), null);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    public static boolean fetchStornoOrders(EmagMirrorDB mirrorDB) {
        for (UserPassword emagAccount : EmagAccounts.getAccounts(mirrorDB)) {
            System.out.println(emagAccount);
            try {
                transferOrdersToDatabase(emagAccount, mirrorDB, null, null, LocalDate.now().minusMonths(6).atStartOfDay(), null, List.of(5), null);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    /**
     * Look for new orders.
     *
     * @param mirrorDB to which to store the orders.
     */
    public static boolean fetchNewOrders(EmagMirrorDB mirrorDB) {
        for (UserPassword emagAccount : EmagAccounts.getAccounts(mirrorDB)) {
            try {
                var startOfFetch = LocalDateTime.now();
                LocalDateTime lastFetchTime;
                String alias = emagAccount.getUsername();
                try {
                    lastFetchTime = mirrorDB.getLastFetchTimeByAccount(alias);
                } catch (NullPointerException e) {
                    lastFetchTime = null;
                }
                if (lastFetchTime == null) {
                    lastFetchTime = startOfFetch.minusMonths(2);
                }
                System.out.printf("%s since %s.%n", alias, lastFetchTime);
                transferOrdersToDatabase(emagAccount, mirrorDB, lastFetchTime, null, null, null, List.of(1, 2, 3, 4), null);
                mirrorDB.saveLastFetchTime(alias, startOfFetch);
            } catch (IOException | InterruptedException | SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    public static boolean fetchOrdersNotFinalizedInDB(EmagMirrorDB mirrorDB, boolean newOnly) {
        try {
            var ordersInProgress = mirrorDB.readOrderIdForOpenOrdersByVendor(newOnly);
            for (UserPassword emagAccount : EmagAccounts.getAccounts(mirrorDB)) {
                consoleLogger.log(INFO, "Fetch not finalized orders for %s.".formatted(emagAccount));
                List<String> orderIds = ordersInProgress.get(emagAccount.getUsername());
                if (orderIds != null) {
                    for (String orderId : orderIds) {
                        transferOrdersToDatabase(emagAccount, mirrorDB, null, null, null, null, null, orderId);
                    }
                }
            }
        } catch (SQLException | IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    /**
     * Logic for fetching data that reads backwards from today 3 years. Newer dates are always processed,
     * older dates are processed randomly with a probability depending on age.
     *
     * @param mirrorDB to which to store the orders.
     */
    public static void fetchAndStoreToDBProbabilistic(EmagMirrorDB mirrorDB) throws SQLException {
        var oldestDay = today.minusYears(2);
        cleanupFetchLogs(mirrorDB, oldestDay);

                    var day = today;
                    while (day.isAfter(oldestDay)) {
                        fetchAllForDay(day, mirrorDB);
                        day = day.minusDays(1);
                    }


        timeE(
                "Refresh return-rate materialized views",
                mirrorDB::refreshReturnRateMaterializedViews
        );
    }

    /**
     * Drop from the emag_fetch_log all entries for days older than the given one.
     *
     * @param mirrorDB  the database to use.
     * @param oldestDay cutoff date.
     */
    private static void cleanupFetchLogs(EmagMirrorDB mirrorDB, LocalDate oldestDay) {
        try {
            var count = mirrorDB.deleteFetchLogsOlderThan(oldestDay);
            consoleLogger.log(INFO, "Deleted %d fetch logs older than %s".formatted(count, oldestDay));
        } catch (SQLException e) {
            warnLogger.log(WARNING, "Error deleting fetch logs", e);
        }
    }

    private static boolean fetchAllForDay(LocalDate day, EmagMirrorDB mirrorDB) throws SQLException {
        var startTime = day.atStartOfDay();
        var endTime = startTime.plusDays(1);
        var dayWasFullyFetched = true;
        for (UserPassword account : EmagAccounts.getAccounts(mirrorDB)) {
            // Check in the database when it was last fetched.
            var fetchStatus = mirrorDB.getFetchStatus(account.getUsername(), day).orElse(null);
            dayWasFullyFetched = dayWasFullyFetched && isDone(fetchStatus);
            if (needsFetch(fetchStatus)) {
                consoleLogger.log(INFO, "Fetch from %s for %s–%s".formatted(account, startTime, endTime));
                var fetchStartTime = LocalDateTime.now();
                Exception exception = null;
                var ordersTransferred = 0;
                var rmasTransferred = 0;
                try {
                    ordersTransferred = transferOrdersToDatabase(account, mirrorDB, startTime, endTime, null, null, null, null);
                    rmasTransferred = transferRMAsToDatabase(account, mirrorDB, startTime, endTime);
                } catch (Exception e) {
                    warnLogger.log(WARNING, "Some error occurred", e);
                    exception = e;
                } finally {
                    var fetchEndTime = LocalDateTime.now();
                    var error = (exception != null) ? exception.getMessage() : null;
                    mirrorDB.addEmagLog(account.getUsername(), day, fetchEndTime, error);
                    consoleLogger.log(FINE, "Transferred %d orders and %d RMAs in %.2f seconds".formatted(ordersTransferred, rmasTransferred, fetchStartTime.until(fetchEndTime, MILLIS) / 1000.0));
                    if (exception != null) throw new RuntimeException(exception);
                }
                // If emag connection issues get high, maybe add in again Thread.sleep(1_000);
            }
        }
        return dayWasFullyFetched;
    }

    /**
     * Determine from the status found in the fetch log, whether the day needs to be fetched.
     * A null value in fetchLog means that no record was found; thus this will return true.
     * The same happens if there is an error message.
     *
     * <p>If the day was already processed successfully, then it might still be
     * fetched again with a certain probability based on when it was last fetched
     * and how old the day is.</p>
     *
     * @param fetchLog as retrieved from the database or null.
     * @return true if this day and this account needs to be fetched.
     */
    private static boolean needsFetch(EmagFetchLog fetchLog) {
        // If this day was never retrieved, then we must do it now.
        if (!isDone(fetchLog)) {
            return true;
        }
        var daysPassed = fetchLog.date().until(today, DAYS);
        var daysPassedSinceLastFetch = fetchLog.fetchTime().toLocalDate().until(today, DAYS);
        double probability = computeProbability(daysPassed, daysPassedSinceLastFetch);
        // Return true and fetch only if the random value is smaller than the probability.
        return random.nextDouble() < probability;
    }

    /**
     * Determine the probability depending on the number of days passed and the number of days since the
     * last time the data for this day was fetched.
     *
     * @param daysPassed               Number of days between order creation and today.
     * @param daysPassedSinceLastFetch Number of days since last fetch and today.
     * @return a probability between 0.0 and 1.0, 0.0 meaning `never`, 1.0 meaning 100% aka `will always happen`.
     */
    private static double computeProbability(long daysPassed, long daysPassedSinceLastFetch) {
        double probability; // Probability to fetch again.
        if (daysPassedSinceLastFetch > 60) {
            // Data not refetched for two months are always refetched.
            probability = 1.0;
        } else if (daysPassedSinceLastFetch > 30 && daysPassed <= 366) {
            // Data older than 30 days is refetched if it is not older than a year.
            probability = 1.0;
        } else if (daysPassed <= 7) {
            // For orders having a date within the last week.
            probability = 1.0;
        } else if (daysPassed <= 30) {
            // For orders having a date within the last month
            probability = (daysPassedSinceLastFetch <= 3) ? 0.1 : 1.0;
        } else if (daysPassed <= 180) {
            // For orders having a date within the last half-year.
            probability = (daysPassedSinceLastFetch <= 7) ? 0.02 : 0.3;
        } else if (daysPassed <= 366) {
            // For orders having a date within the last year.
            probability = (daysPassedSinceLastFetch <= 14) ? 0.01 : 0.1;
        } else {
            // For orders having a date older than a year.
            probability = (daysPassedSinceLastFetch <= 30) ? 0.0 : 0.05;
        }
        return probability;
    }

    private static int transferOrdersToDatabase(UserPassword account, EmagMirrorDB mirrorDB, LocalDateTime createdAfter, LocalDateTime createdBefore, LocalDateTime modifiedAfter, LocalDateTime modifiedBefore, List<Integer> statusList, String orderId) throws IOException, InterruptedException {
        var orders = readFromEmag(account, createdAfter, createdBefore, modifiedAfter, modifiedBefore, statusList, orderId);
        if (orders != null) {
            orders.forEach(orderResult ->
                    {
                        try {
                            mirrorDB.addOrder(orderResult, account.getUsername());
                        } catch (SQLException e) {
                            throw new RuntimeException("Error inserting order " + orderResult, e);
                        }
                    }
            );
            return orders.size();
        }
        return 0;
    }

    private static int transferRMAsToDatabase(UserPassword account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var rmas = readRMAFromEmag(account, startTime, endTime);
        if (rmas != null) {
            rmas.forEach(rma ->
                    {
                        try {
                            mirrorDB.addRMA(rma);
                        } catch (SQLException e) {
                            throw new RuntimeException("Error inserting RMA request " + rma, e);
                        }
                    }
            );
            return rmas.size();
        }
        return 0;
    }

    private static List<OrderResult> readFromEmag(UserPassword emagCredentials, LocalDateTime createdAfter, LocalDateTime createdBefore, LocalDateTime modifiedAfter, LocalDateTime modifiedBefore, List<Integer> statusList, String id) throws IOException, InterruptedException {
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var filter = new HashMap<String, Object>();
        filter.put("itemsPerPage", 300);
        if (createdAfter != null) filter.put("createdAfter", createdAfter);
        if (createdBefore != null) filter.put("createdBefore", createdBefore);
        if (modifiedAfter != null) filter.put("modifiedAfter", modifiedAfter);
        if (modifiedBefore != null) filter.put("modifiedBefore", modifiedBefore);
        if (statusList != null) filter.put("status", statusList);
        if (id != null) filter.put("id", id);
        return emag.readRequest("order", filter, null, OrderResult.class);
    }

    private static List<RMAResult> readRMAFromEmag(UserPassword emagCredentials, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var filter = new HashMap<String, Object>();
        if (startTime != null) {
            filter.put("date_start", startTime);
        }
        if (endTime != null) {
            filter.put("date_end", endTime);
        }
        return emag.readRequest("rma",
                filter,
                null,
                RMAResult.class);

    }
}
