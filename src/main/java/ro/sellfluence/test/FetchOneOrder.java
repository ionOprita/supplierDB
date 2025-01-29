package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.util.Map;

import static java.util.logging.Level.FINE;

public class FetchOneOrder {
    public static void main(String[] args) throws Exception {
        EmagApi.setAPILogLevel(FINE);
        fetchOrder("zoopieinvest","242717034");
    }

    private static void fetchOrder(String vendor, String orderId) throws IOException, InterruptedException {
        System.out.printf("Fetching order %s with user %s%n", orderId, vendor);
        var emagCredentials = UserPassword.findAlias(vendor/*"koppelfbe"*/ /*"zoopiesolutions"*/);
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var response = emag.readRequest("order", Map.of("id", orderId), null, OrderResult.class);
        var responseRet = emag.readRequest("rma", Map.of("order_id", orderId), null, RMAResult.class);
    }
}
