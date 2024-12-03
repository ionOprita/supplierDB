package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

import java.util.Map;

import static java.util.logging.Level.FINE;

public class FetchOneOrder {
    public static void main(String[] args) throws Exception {
        EmagApi.setAPILogLevel(FINE);
        var emagCredentials = UserPassword.findAlias("sellfusion");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        //var response = emag.readRequest("order", Map.of("id","381040577"), null, OrderResult.class);
        var responseRet = emag.readRequest("rma", Map.of("order_id", "381040577"), null, RMAResult.class);
    }
}
