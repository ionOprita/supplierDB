package ro.sellfluence.app;

import ch.claudio.db.DB;
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

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

public class EmagDBApp {

    private static final Logger logger = Logger.getLogger(EmagDBApp.class.getName());
    private static final List<String> emagAccounts = List.of(
            //"sellfluence",
//            "zoopieconcept",
//            "zoopieinvest",
//            "zoopiesolutions",
//            "judios",
//            "koppel",
//            "koppelfbe"
            "sellfusion"
    );

    //Vendor, day-requested, day-executed, error encountered (NULL if fully executed successfully)
    public static void main(String[] args) {
        //EmagApi.activateEmagJSONLog();
        boolean allFetched;
        do {
            try {
                var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagRemote");
                var startOfToday = LocalDate.now().atStartOfDay();
                int weeksToRead = 5*53;
                for (int pastWeek = 0; pastWeek < weeksToRead; pastWeek++) {
                    var endTime = startOfToday.minusWeeks(pastWeek);
                    var startTime = endTime.minusWeeks(1);
                    for (String account : emagAccounts) {
                        var wasFetched = mirrorDB.wasFetched(account, startTime, endTime);
                        if (wasFetched != EmagMirrorDB.FetchStatus.yes) {
                            logger.log(INFO, "Fetch from %s for %s - %s".formatted(account, startTime, endTime));
                            var fetchStartTime = LocalDateTime.now();
                            Exception exception = null;
                            try {
                                transferOrdersToDatabase(account, mirrorDB, startTime, endTime);
                                DB.printTimes();
                                transferRMAsToDatabase(account, mirrorDB, startTime, endTime);
                                DB.printTimes();
                            } catch (Exception e) {
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
                    // to reads = 1325 sec = less than 1 hours
                }
                allFetched = true;
            } catch (Exception e) {
                allFetched = false;
                // After an error wait a minute before retrying
                try {
                    Thread.sleep(60_000); // 5 sec * 5 * 53 weeks = 5 sec * 265 weeks
                } catch (InterruptedException ex) {
                    // Ignored
                }
            }
        } while (!allFetched);
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
                            "createdAfter",
                            startTime,
                            "createdBefore",
                            endTime),
                    null,
                    RMAResult.class);
        }
        return null;
    }
}
