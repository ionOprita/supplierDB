package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.util.logging.Level.FINE;

public class FetchOneOrder {

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

    public static void main(String[] args) throws Exception {
        EmagApi.setAPILogLevel(FINE);
    //    emagAccounts.forEach(account -> fetchOrder(account,"407649385"));
        fetchOrder("sellfusion","414251813");
    }

    private static void fetchOrder(String vendor, String orderId) {
        System.out.printf("Fetching order %s with user %s%n", orderId, vendor);
        var emagCredentials = UserPassword.findAlias(vendor/*"koppelfbe"*/ /*"zoopiesolutions"*/);
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        try {
            var response = emag.readRequest("order", Map.of("id", orderId), null, OrderResult.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
//        var responseRet = emag.readRequest("rma", Map.of("order_id", orderId), null, RMAResult.class);
    }
}