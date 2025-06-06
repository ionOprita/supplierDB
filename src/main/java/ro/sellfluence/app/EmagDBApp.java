package ro.sellfluence.app;

import ro.sellfluence.db.EmagFetchLog;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.support.Time.time;
import static ro.sellfluence.support.UsefulMethods.isBlank;

public class EmagDBApp {

    private static final Logger logger = Logger.getLogger(EmagDBApp.class.getName());
    private static final List<String> emagAccounts = List.of(
            "sellfluence",
            "zoopieconcept",
            "zoopieinvest",
            "zoopiesolutions",
            "judios",
            "koppel",
            "koppelfbe",
            "sellfusion"
    );

    private static final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private static final LocalDate today = LocalDate.now();

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s (%2$s)%n");
        EmagApi.setAPILogLevel(INFO);
        try {
            EmagMirrorDB mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
            if (args.length > 0 && Objects.equals(args[0], "refetch_some")) {
                fetchAndStoreToDBProbabilistic(mirrorDB);
            } else if (args.length > 0 && Objects.equals(args[0], "nofetch")) {
// Don't fetch anything.
            } else {
                fetchAndStoreToDB(mirrorDB);
            }
            mirrorDB.updateGMVTable();
        } catch (SQLException e) {
            throw new RuntimeException("error initializing database", e);
        } catch (IOException e) {
            throw new RuntimeException("error connecting to the database", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Unexpected interrupted exception", e);
        }
    }

    /**
     * Logic for fetching data that follows this specification:
     * <ul>
     *     <li>Get orders with states 1-4 since the last time we fetched.</li>
     *     <li>Get orders with states 5 for the last two years.</li>
     *     <li>Reread all orders having status 0-3 in the database by order id to see if their value has changed.</li>
     * </ul>
     * @param mirrorDB
     */
    private static void fetchAndStoreToDB(EmagMirrorDB mirrorDB) throws IOException, InterruptedException, SQLException {
        time(
                "Fetch new orders",
                () -> repeatUntilDone(() -> fetchNewOrders(mirrorDB))
        );
        time(
                "Fetch orders not finalized in database",
                () -> repeatUntilDone(() -> fetchOrdersNotFinalizedInDB(mirrorDB))
        );
        time(
                "Fetch storno orders",
                () -> repeatUntilDone(() -> fetchStornoOrders(mirrorDB))
        );
    }

    private static boolean fetchStornoOrders(EmagMirrorDB mirrorDB) {
        for (String emagAccount : emagAccounts) {
            System.out.println(emagAccount);
            try {
                transferOrdersToDatabase(emagAccount, mirrorDB, null, null, LocalDate.now().minusYears(2).atStartOfDay(), null, List.of(5), null);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    /**
     * Look for new orders.
     *
     * @param mirrorDB
     */
    private static boolean fetchNewOrders(EmagMirrorDB mirrorDB) {
        for (String emagAccount : emagAccounts) {
            try {
                var startOfFetch = LocalDateTime.now();
                LocalDateTime lastFetchTime;
                try {
                    lastFetchTime = mirrorDB.getLastFetchTimeByAccount(emagAccount);
                } catch (NullPointerException e) {
                    lastFetchTime = null;
                }
                if (lastFetchTime == null) {
                    lastFetchTime = startOfFetch.minusMonths(2);
                }
                System.out.printf("%s since %s.%n", emagAccount, lastFetchTime);
                transferOrdersToDatabase(emagAccount, mirrorDB, lastFetchTime, null, null, null, List.of(1, 2, 3, 4), null);
                mirrorDB.saveLastFetchTime(emagAccount, startOfFetch);
            } catch (IOException | InterruptedException | SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    private static boolean fetchOrdersNotFinalizedInDB(EmagMirrorDB mirrorDB) {
        try {
            var ordersInProgress = mirrorDB.readOrderIdForOpenOrdersByVendor();
            for (String emagAccount : emagAccounts) {
                System.out.println(emagAccount);
                List<String> orderIds = ordersInProgress.get(emagAccount);
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
     * @param mirrorDB
     */
    private static void fetchAndStoreToDBProbabilistic(EmagMirrorDB mirrorDB) {
        var daysToConsider = 3 * 366;   // 3 Years
        var oldestDay = today.minusDays(daysToConsider);
        cleanupFetchLogs(mirrorDB, oldestDay);
        repeatUntilDone(
                () -> {
                    boolean result = false;
                    var day = today;
                    while (day.isAfter(oldestDay)) {
                        result = fetchAllForDay(day, mirrorDB);
                        day = day.minusDays(1);
                    }
                    return result;
                }
        );
    }

    /**
     * Drop from the emag_fetch_log all entries for days older than the given one.
     * @param mirrorDB the database to use.
     * @param oldestDay cutoff date.
     */
    private static void cleanupFetchLogs(EmagMirrorDB mirrorDB, LocalDate oldestDay) {
        try {
            var count = mirrorDB.deleteFetchLogsOlderThan(oldestDay);
            logger.log(INFO, "Deleted %d fetch logs older than %s".formatted(count, oldestDay));
        } catch (SQLException e) {
            logger.log(WARNING, "Error deleting fetch logs", e);
        }
    }

    private static boolean repeatUntilDone(Callable<Boolean> callable) {
        boolean allFetched;
        var result = false;
        do {
            try {
                result = callable.call();
                allFetched = true;
            } catch (Exception e) {
                allFetched = false;
                logger.log(WARNING, "Waiting for a minute because of an exception ", e);
                e.printStackTrace();
                try {
                    Thread.sleep(60_000); // 5 sec * 5 * 53 weeks = 5 sec * 265 weeks
                } catch (InterruptedException ex) {
                    // Ignored
                }
            }
        } while (!allFetched);
        return result;
    }

    private static boolean fetchAllForDay(LocalDate day, EmagMirrorDB mirrorDB) throws Exception {
        var startTime = day.atStartOfDay();
        var endTime = startTime.plusDays(1);
        var dayWasFullyFetched = true;
        for (String account : emagAccounts) {
            // Check in the database when it was last fetched.
            var fetchStatus = mirrorDB.getFetchStatus(account, day).orElse(null);
            dayWasFullyFetched = dayWasFullyFetched && isDone(fetchStatus);
            if (needsFetch(fetchStatus)) {
                logger.log(INFO, "Fetch from %s for %s - %s".formatted(account, startTime, endTime));
                var fetchStartTime = LocalDateTime.now();
                Exception exception = null;
                var ordersTransferred = 0;
                var rmasTransferred = 0;
                try {
                    ordersTransferred = transferOrdersToDatabase(account, mirrorDB, startTime, endTime, null, null, null, null);
                    rmasTransferred = transferRMAsToDatabase(account, mirrorDB, startTime, endTime);
                } catch (Exception e) {
                    logger.log(WARNING, "Some error occurred", e);
                    exception = e;
                } finally {
                    var fetchEndTime = LocalDateTime.now();
                    var error = (exception != null) ? exception.getMessage() : null;
                    mirrorDB.addEmagLog(account, day, fetchEndTime, error);
                    logger.log(FINE, "Transferred %d orders and %d RMAs in %.2f seconds".formatted(ordersTransferred, rmasTransferred, fetchStartTime.until(fetchEndTime, MILLIS) / 1000.0));
                    if (exception != null) throw exception;
                }
                // If emag connection issue get high, maybe add in again Thread.sleep(1_000);
            }
        }
        return dayWasFullyFetched;
    }

    /**
     * Report if the log indicates that this day is done.
     *
     * @param fetchStatus for a particular account and day or null if non was found.
     * @return true if the entry existed and had a blank error message.
     */
    private static boolean isDone(EmagFetchLog fetchStatus) {
        return fetchStatus != null && isBlank(fetchStatus.error());
    }

    /**
     * Determine from the status found in the fetch log, whether the day needs to be fetched.
     * A null value in fetchLog means, that no record was found, thus this will return true.
     * The same happens if there is an error message.
     *
     * <p>If the day was already processed successfully, then it is still proposed to be
     * fetched again with a certain probability based on when it was last fetched
     * and how old the day is.</p>
     *
     * @param fetchLog as retrieved from the database or null.
     * @return true if this day and account needs to be fetched.
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
     * last time that day was fetched.
     *
     * @param daysPassed Number of days between order creation and today.
     * @param daysPassedSinceLastFetch Number of days since last fetch and today.
     * @return a probability between 0.0 and 1.0, 0.0 meaning never, 1.0 meaning 100% aka always will happen.
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

    private static int transferOrdersToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime createdAfter, LocalDateTime createdBefore, LocalDateTime modifiedAfter, LocalDateTime modifiedBefore, List<Integer> statusList, String orderId) throws IOException, InterruptedException {
        var orders = readFromEmag(account, createdAfter, createdBefore, modifiedAfter, modifiedBefore, statusList, orderId);
        if (orders != null) {
            orders.forEach(orderResult ->
                    {
                        try {
                            mirrorDB.addOrder(orderResult, account);
                        } catch (SQLException e) {
                            throw new RuntimeException("Error inserting order " + orderResult, e);
                        }
                    }
            );
            return orders.size();
        }
        return 0;
    }

    private static int transferRMAsToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
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

    private static List<OrderResult> readFromEmag(String alias, LocalDateTime createdAfter, LocalDateTime createdBefore, LocalDateTime modifiedAfter, LocalDateTime modifiedBefore, List<Integer> statusList, String id) throws IOException, InterruptedException {
        var emagCredentials = UserPassword.findAlias(alias);
        if (emagCredentials == null) {
            logger.log(WARNING, "Missing credentials for alias " + alias);
        } else {
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            var filter = new HashMap<String, Object>();
            filter.put("itemsPerPage", 1000);
            if (createdAfter != null) filter.put("createdAfter", createdAfter);
            if (createdBefore != null) filter.put("createdBefore", createdBefore);
            if (modifiedAfter != null) filter.put("modifiedAfter", modifiedAfter);
            if (modifiedBefore != null) filter.put("modifiedBefore", modifiedBefore);
            if (statusList != null) filter.put("status", statusList);
            if (id != null) filter.put("id", id);
            return emag.readRequest("order", filter, null, OrderResult.class);
        }
        return null;
    }

    private static List<RMAResult> readRMAFromEmag(String alias, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var emagCredentials = UserPassword.findAlias(alias);
        if (emagCredentials == null) {
            logger.log(WARNING, "Missing credentials for alias " + alias);
        } else {
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            return emag.readRequest("rma",
                    Map.of(
                            "date_start",
                            startTime,
                            "date_end",
                            endTime),
                    null,
                    RMAResult.class);
        }
        return null;
    }
}