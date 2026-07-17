package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.util.List;

import static java.util.logging.Level.FINE;

public class FetchOrderCount {
    private static final List<String> emagAccounts = List.of(
            "judios",
            "koppel",
            "koppelfbe",
            "sellfusion",
            "zoopiesolutions"
    );

    public static void main(String[] args) throws IOException, InterruptedException {
        EmagApi.setAPILogLevel(FINE);
        for (String emagAccount : emagAccounts) {
            var emagCredentials = UserPassword.findAlias(emagAccount);
            var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
            System.out.println(emagAccount+": "+emag.countOrderRequest());
        }
    }
}
