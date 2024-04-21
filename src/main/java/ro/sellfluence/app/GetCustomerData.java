package ro.sellfluence.app;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.OrderResult;
import ro.sellfluence.emagapi.Product;
import ro.sellfluence.support.UserPassword;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ro.sellfluence.emagapi.EmagApi.statusFinalized;

public class GetCustomerData {

    public record SheetData(
            String orderId,
            int quantity,
            BigDecimal price,
            boolean isCompany,
            LocalDateTime orderDate,
            String productName,
            String partNumberKey,
            String userName,
            String billingName,
            String billingPhone,
            String billingAddress,
            String clientName,
            String clientPhone,
            String deliveryAddress,
            String deliveryMode,
            boolean contacted
    ) {
    }

    public static Map<String, List<SheetData>> getByProduct(LocalDateTime startTime, LocalDateTime endTime) {
        //TODO: Need to get from more than one emag account.
        //TODO: Bail out immediatly if the error message indicates IP-Adress which is not configured.
        var emagCredentials = UserPassword.findAlias("emag");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        try {
            var responses = emag.readRequest("order",
                    Map.of("status",
                            statusFinalized,
                            "createdAfter",
                            startTime,
                            "createdBefore",
                            endTime),
                    null,
                    OrderResult.class);
            Map<String, List<SheetData>> orderedProductsByPNK = new HashMap<>();
            for (OrderResult order : responses) {
                if (order.customer != null) {
                    for (Product product : order.products) {
                        List<SheetData> list = orderedProductsByPNK.getOrDefault(product.part_number_key, new ArrayList<>());
                        list.add(new SheetData(
                                        order.id,
                                        product.quantity,
                                        product.sale_price,
                                        order.isCompany(),
                                        order.date,
                                        product.name, // TODO: This might not be correct.
                                        product.part_number_key,
                                        order.customer.name,
                                        order.customer.billing_name,
                                        order.customer.billing_phone,
                                        order.customer.getBillingAddress(),
                                        order.customer.name,
                                        order.customer.phone_1,
                                        order.customer.getShippingAddress(),
                                        order.getDeliveryMode(),
                                        false
                                )
                        );
                        orderedProductsByPNK.put(product.part_number_key, list);
                    }
                } else {
                    System.out.printf("WARNING: order %s ignored because customer is null%n", order.id);
                }
            }
            return orderedProductsByPNK;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
