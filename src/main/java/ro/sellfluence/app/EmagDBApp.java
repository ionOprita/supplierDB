package ro.sellfluence.app;

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
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

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
    private static final LocalDateTime today = LocalDate.now().atStartOfDay();

    public static void main(String[] args) {
        //EmagApi.setAPILogLevel(FINEST);
        EmagApi.setAPILogLevel(WARNING);
        boolean allFetched;
        EmagMirrorDB mirrorDB;
        try {
            mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagOprita");
        } catch (SQLException e) {
            throw new RuntimeException("error initializing database", e);
        } catch (IOException e) {
            throw new RuntimeException("error connecting to the database", e);
        }
        do {
            try {
                var yesterday = today.minusDays(1);
                fetchAllForDay(yesterday, mirrorDB);
                var daysToConsider = 5 * 366;
                var startOfFullPeriod = yesterday.minusDays(daysToConsider);
                var sequenceOfDaysNotNeeded = 0;
                do {
                    var randomDay = startOfFullPeriod.plusDays(random.nextInt(daysToConsider));
                    var dayWasFullyFetched = fetchAllForDay(randomDay, mirrorDB);
                    if (dayWasFullyFetched) {
                        sequenceOfDaysNotNeeded++;
                        if (sequenceOfDaysNotNeeded % 1000 == 0) {
                            logger.log(INFO, "Skipped %d already done days…".formatted(sequenceOfDaysNotNeeded));
                        }
                    } else {
                        logger.log(INFO, "Skipped %d already done days!".formatted(sequenceOfDaysNotNeeded));
                        sequenceOfDaysNotNeeded = 0;
                    }
                } while (sequenceOfDaysNotNeeded < 10_000);
                // Assume all days done, when 10'000 random tries all fell on a date, which was already handled.
                allFetched = true;
            } catch (Exception e) {
                allFetched = false;
                // After an error, wait a minute before retrying
                try {
                    Thread.sleep(60_000); // 5 sec * 5 * 53 weeks = 5 sec * 265 weeks
                } catch (InterruptedException ex) {
                    // Ignored
                }
            }
        } while (!allFetched);
    }

    private static boolean fetchAllForDay(LocalDateTime startTime, EmagMirrorDB mirrorDB) throws Exception {
        var endTime = startTime.plusDays(1);
        var dayWasFullyFetched = true;
        for (String account : emagAccounts) {
            var needsFetching = mirrorDB.getFetchStatus(account, startTime, endTime) != EmagMirrorDB.FetchStatus.yes;
            needsFetching = randomRefetch(needsFetching, startTime);
            if (needsFetching) {
                dayWasFullyFetched = false;
                logger.log(INFO, "Fetch from %s for %s - %s".formatted(account, startTime, endTime));
                var fetchStartTime = LocalDateTime.now();
                Exception exception = null;
                try {
                    transferOrdersToDatabase(account, mirrorDB, startTime, endTime);
                    transferRMAsToDatabase(account, mirrorDB, startTime, endTime);
                } catch (Exception e) {
                    logger.log(WARNING, "Some error occurred", e);
                    exception = e;
                } finally {
                    var fetchEndTime = LocalDateTime.now();
                    var error = (exception != null) ? exception.getMessage() : null;
                    mirrorDB.addEmagLog(account, startTime, endTime, fetchStartTime, fetchEndTime, error);
                    if (exception != null) throw exception;
                }
                Thread.sleep(1_000);
            }
        }
        return dayWasFullyFetched;
    }

    /**
     * If a day was already fetched, then randomly refetch it again, just to see if anything changed.
     * Days in the near past are given higher probability to be fetched again.
     *
     * @param needsFetching original information whether the day was already fetched.
     * @param startTime day considered.
     * @return modified value.
     */
    private static boolean randomRefetch(boolean needsFetching, LocalDateTime startTime) {
        // If it needs fetching thn return unchanged.
        if (needsFetching) {
            return true;
        }
        var daysPassed = startTime.until(today, DAYS);
        double probability; // Probability to fetch again.
        if (daysPassed <= 7) {
            probability = 1.0;
        } else if (daysPassed <= 30) {
            probability = 0.8;
        } else if (daysPassed <= 180) {
            probability = 0.5;
        } else if (daysPassed <= 366) {
            probability = 0.2;
        } else {
            probability = 0.05;
        }
        // Return true and thus refetch only if the random value is smaller than the probability.
        boolean newNeedsFetching = random.nextDouble() < probability;
        if (newNeedsFetching) {
            System.out.printf("Refetching %s (probability was %d%%)%n", startTime.toLocalDate(), Math.round(probability * 100));
        }
        return newNeedsFetching;
    }

    private static void transferOrdersToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
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
        }
    }

    private static void transferRMAsToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
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
        }
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
