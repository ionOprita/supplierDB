package ro.sellfluence.test;

import org.hibernate.type.descriptor.jdbc.NVarcharJdbcType;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

public class FetchOrderByModifiedDate {

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
        EmagApi.setAPILogLevel(WARNING);
    //    emagAccounts.forEach(account -> fetchOrder(account,"407649385"));
        fetchOrder("sellfusion");
    }

    private static void fetchOrder(String vendor) throws SQLException, IOException {
        var emagCredentials = UserPassword.findAlias(vendor);
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var startTime = LocalDate.of(2023,3,9).atStartOfDay();
        var endTime = LocalDate.of(2025,3,9).atStartOfDay();
        List<Integer> statusList = List.of(5);
        var fromDB = getFromDB(startTime, endTime, statusList, "SELLFUSION FBE");
        System.out.printf("DB: %d orders%n", fromDB.size());
        try {
            var response = emag.readRequest("order",
                    Map.of(
                            "modifiedAfter",
                            startTime,
                            "modifiedBefore",
                            endTime,
                            "status",
                            statusList
                            ),
                    null, OrderResult.class);
            System.out.printf("Fetched %d Orders%n",response.size());
            var ordersInResponse = response.stream().map(it->it.id()).collect(Collectors.toSet());
            var onlyInDB = fromDB.stream().filter(it  -> !ordersInResponse.contains(it.id()));
            onlyInDB.forEach(it-> System.out.println(it));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
//        var responseRet = emag.readRequest("rma", Map.of("order_id", orderId), null, RMAResult.class);
    }
    private static final String databaseName = "emagLocal";

    private static List<OrderResult> getFromDB(LocalDateTime startTime, LocalDateTime endTime, List<Integer> statusList, String vendorName) throws SQLException, IOException {
        var mirrorDB = EmagMirrorDB.getEmagMirrorDB(databaseName);
        var vendorId = mirrorDB.getVendorByName(vendorName);
        return mirrorDB.readOrderByDateAndStatus(startTime, endTime, statusList, vendorId);
    }
}