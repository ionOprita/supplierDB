package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;

import static java.util.logging.Level.FINE;

public class FetchOrderCount {
    public static void main(String[] args) throws IOException, InterruptedException {
        EmagApi.setAPILogLevel(FINE);
        var emagCredentials = UserPassword.findAlias("sellfusion");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        System.out.println(emag.countOrderRequest());
    }
}
