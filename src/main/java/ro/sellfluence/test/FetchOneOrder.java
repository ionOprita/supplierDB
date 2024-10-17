package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.RMAResult;
import ro.sellfluence.support.UserPassword;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

public class FetchOneOrder {
    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getLogger(EmagApi.class.getName());
        logger.setLevel(FINE);
        Arrays.stream(logger.getHandlers()).forEach(h -> {
                    h.setLevel(FINE);
                }
        );
        Arrays.stream(logger.getParent().getHandlers()).forEach(h -> {
                    h.setLevel(FINE);
                }
        );
        var emagCredentials = UserPassword.findAlias("sellfusion");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var response = emag.readRequest("order", Map.of("id","381040577"), null, OrderResult.class);
        var responseRet = emag.readRequest("rma", Map.of("order_id","381040577"), null, RMAResult.class);
    }
}
