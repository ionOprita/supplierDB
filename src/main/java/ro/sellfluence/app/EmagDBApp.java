package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class EmagDBApp {

    private static final Logger logger = Logger.getLogger(EmagDBApp.class.getName());
    private static final List<String> emagAccounts = List.of(
            "sellfluence",
            "sellfusion",
            "zoopieconcept",
            "zoopieinvest",
            "zoopiesolutions",
            "judios",
            "koppel",
            "koppelfbe"
    );
    //Vendor, day-requested, day-executed, error encountered (NULL if fully executed successfully)
    //TODO: Store what has been done externally, so we don't need to refetch when error happens.
    public static void main(String[] args) throws Exception {
        //activateEmagJSONLog();
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emagLocal");
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
                    String error = null;
                    try {
                        transferOrdersToDatabase(account, mirrorDB, startTime, endTime);
                    } catch (Exception e) {
                        error = e.getMessage();
                    } finally {
                        var fetchEndTime = LocalDateTime.now();
                        mirrorDB.addEmagLog(account, startTime, endTime, fetchStartTime, fetchEndTime, error);
                    }
                    Thread.sleep(1_000);
                }
            }
            Thread.sleep(10_000); // 5 sec * 5 * 53 weeks = 5 sec * 265 weeks
            // to reads = 1325 sec = less than 1 hours
        }
    }

    private static void transferOrdersToDatabase(String account, EmagMirrorDB mirrorDB, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var orders = readFromEmag(account, startTime, endTime);
        if (orders != null) {
            orders.forEach(orderResult ->
                    {
                        try {
                            mirrorDB.addOrder(orderResult);
                        } catch (SQLException e) {
                            logger.log(SEVERE, "Error inserting order " + orderResult, e);
                        }
                    }
            );
        }
    }

    private static void activateEmagJSONLog() {
        var emagLogger = Logger.getLogger(EmagApi.class.getName());
        emagLogger.setLevel(FINE);
        for (Handler handler : emagLogger.getHandlers()) {
            handler.setLevel(FINE);
        }
        for (Handler handler : emagLogger.getParent().getHandlers()) {
            handler.setLevel(FINE);
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
}
