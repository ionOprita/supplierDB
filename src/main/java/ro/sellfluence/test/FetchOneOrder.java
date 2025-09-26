package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

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
/*
    All sellfusion:

    - Anulat de client 430733210.
    - Anulat de seller 424716128.
    - Stornate 429684548.
    - Finalizate locker 430991369.
    - Finalizate curier 430986576.
-
    Inlocuire needs to read the sheet from google because they are handled within sellfusion.
    => RER Exchange Rate

    427037619
 */
    public static void main(String[] args) throws Exception {
        EmagApi.setAPILogLevel(FINE);
    //    emagAccounts.forEach(account -> fetchOrder(account,"407649385"));
        //fetchOrder("sellfusion","427037619");
        fetchOrder("sellfusion", "407404003");
    }

    private static void fetchOrder(String vendor, String orderId) {
        System.out.printf("Fetching order %s with user %s%n", orderId, vendor);
        var emagCredentials = UserPassword.findAlias(vendor/*"koppelfbe"*/ /*"zoopiesolutions"*/);
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        try {
            var response = emag.readRequest("order", Map.of("id", orderId), null, OrderResult.class);
            var responseRet = emag.readRequest("rma", Map.of("order_id", orderId), null, RMAResult.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}