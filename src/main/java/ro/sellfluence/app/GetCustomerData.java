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
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static ro.sellfluence.emagapi.EmagApi.statusFinalized;

public class GetCustomerData {

    private static final Logger logger = Logger.getLogger(GetCustomerData.class.getName());

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

    public static Map<String, List<SheetData>> getByProduct(LocalDateTime startTime, LocalDateTime endTime, String... emagAccounts) {
        Map<String, List<SheetData>> orderedProductsByPNK = new HashMap<>();
        for (var alias : emagAccounts) {
            var emagCredentials = UserPassword.findAlias(alias);
            if (emagCredentials==null) {
                logger.log(WARNING, "Missing credentials for alias "+alias);
                return Map.of();
            }
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
                                            product.name,
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
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return orderedProductsByPNK;
    }
}
