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
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
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
        //EmagApi.setAPILogLevel(FINEST);
        EmagApi.setAPILogLevel(INFO);
        boolean allFetched;
        EmagMirrorDB mirrorDB;
        try {
            mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
        } catch (SQLException e) {
            throw new RuntimeException("error initializing database", e);
        } catch (IOException e) {
            throw new RuntimeException("error connecting to the database", e);
        }
        do {
            try {
                var daysToConsider = 5 * 366;
                var oldestDay = today.minusDays(daysToConsider);
                var day = today;
                while (day.isAfter(oldestDay)) {
                    fetchAllForDay(day, mirrorDB);
                    day = day.minusDays(1);
                }
                allFetched = true;
            } catch (Exception e) {
                allFetched = false;
                logger.log(WARNING, "Waiting for a minute because of an exception ",e);
                e.printStackTrace();
                try {
                    Thread.sleep(60_000); // 5 sec * 5 * 53 weeks = 5 sec * 265 weeks
                } catch (InterruptedException ex) {
                    // Ignored
                }
            }
        } while (!allFetched);
    }

    private static boolean fetchAllForDay(LocalDate day, EmagMirrorDB mirrorDB) throws Exception {
        var startTime = day.atStartOfDay();
        var endTime = startTime.plusDays(1);
        var dayWasFullyFetched = true;
        for (String account : emagAccounts) {
            var fetchStatus = mirrorDB.getFetchStatus(account, day).orElse(null);
            dayWasFullyFetched = dayWasFullyFetched && isDone(fetchStatus);
            if (needsFetch(fetchStatus)) {
                logger.log(INFO, "Fetch from %s for %s - %s".formatted(account, startTime, endTime));
                var fetchStartTime = LocalDateTime.now();
                Exception exception = null;
                var ordersTransferred = 0;
                var rmasTransferred = 0;
                try {
                    ordersTransferred = transferOrdersToDatabase(account, mirrorDB, startTime, endTime);
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
                Thread.sleep(1_000);
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
     * @return a probability between 0.0 and 1.0
     */
    private static double computeProbability(long daysPassed, long daysPassedSinceLastFetch) {
        double probability; // Probability to fetch again.
        if (daysPassed <= 7) {
            probability = 1.0;
        } else if (daysPassed <= 30) {
            probability = (daysPassedSinceLastFetch <= 2) ? 0.1 : 0.5;
        } else if (daysPassed <= 180) {
            probability = (daysPassedSinceLastFetch <= 7) ? 0.02 : 0.1;
        } else if (daysPassed <= 366) {
            probability = (daysPassedSinceLastFetch <= 30) ? 0.01 : 0.05;
        } else {
            probability = (daysPassedSinceLastFetch <= 30) ? 0.0 : 0.01;
        }
        return probability;
    }

    private static int transferOrdersToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var orders = readFromEmag(account, startTime, endTime);
        if (orders != null) {
            orders.forEach(orderResult ->
                    {
                        try {
                            mirrorDB.addOrder(orderResult);
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

    private static List<OrderResult> readFromEmag(String alias, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var emagCredentials = UserPassword.findAlias(alias);
        if (emagCredentials == null) {
            logger.log(WARNING, "Missing credentials for alias " + alias);
        } else {
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            return emag.readRequest("order",
                    Map.of(
                            "createdAfter",
                            startTime,
                            "createdBefore",
                            endTime),
                    null,
                    OrderResult.class);
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
