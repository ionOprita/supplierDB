package ro.sellfluence.app;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static ro.sellfluence.emagapi.EmagApi.statusFinalized;

public class EmagDBApp {

    private static final Logger logger = Logger.getLogger(EmagDBApp.class.getName());

    public static void main(String[] args) throws Exception {
        var emagLogger = Logger.getLogger(EmagApi.class.getName());
        emagLogger.setLevel(FINE);
        for (Handler handler : emagLogger.getHandlers()) {
            handler.setLevel(FINE);
        }
        for (Handler handler : emagLogger.getParent().getHandlers()) {
            handler.setLevel(FINE);
        }
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB("emag");
        var orders = readFromEmag("emag", LocalDateTime.now().minusDays(51), LocalDateTime.now());
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


    private static List<OrderResult> readFromEmag(String alias, LocalDateTime startTime, LocalDateTime endTime) throws IOException, InterruptedException {
        var emagCredentials = UserPassword.findAlias(alias);
        if (emagCredentials == null) {
            logger.log(WARNING, "Missing credentials for alias " + alias);
        } else {
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            return emag.readRequest("order",
                    Map.of("status",
                            statusFinalized,
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
