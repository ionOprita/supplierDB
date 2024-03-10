package ro.koppel.app;

import ro.koppel.emag.EmagApi;
import ro.koppel.emag.OrderResult;
import ro.koppel.emag.Product;
import ro.koppel.support.UserPassword;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.LocalDate.now;

public class GetCustomerData {

    record SheetData(
            String orderId,
            int quantity,
            BigDecimal price,
            boolean isCompany,
            LocalDateTime orderDate,
            String productName,
            String partNumberKey,
            String billingName,
            String billingPhone,
            String clientName,
            String clientPhone,
            String deliveryAddress,
            String deliveryMode,
            boolean contacted
    ) {
    }

    static Map<String, List<SheetData>> getByProduct() {
        var emagCredentials = UserPassword.findAlias("emag");
        var emag = new EmagApi(emagCredentials.getUsername(), emagCredentials.getPassword());
        var startTime = LocalDate.of(2024, 2, 20).atStartOfDay();
        var endTime = now().minusDays(13).atStartOfDay();
        try {
            var responses = emag.readRequest("order",
                    Map.of("status",
                            4,
                            "createdAfter",
                            startTime,
                            "createdBefore",
                            endTime),
                    null,
                    OrderResult.class);
            Map<String, List<SheetData>> orderedProductsByPNK = new HashMap<>();
            for (OrderResult order : responses) {
                for (Product product : order.products) {
                    List<SheetData> list = orderedProductsByPNK.getOrDefault(product.part_number_key, new ArrayList<SheetData>());
                    list.add(new SheetData(
                                    order.id,
                                    product.quantity,
                                    product.sale_price,
                                    order.isCompany(),
                                    order.date,
                                    product.name, // TODO: This might not be correct.
                                    product.part_number_key,
                                    order.customer.billing_name,
                                    order.customer.billing_phone,
                                    order.customer.name,
                                    order.customer.phone_1,
                                    order.customer.getShippingAddress(),
                                    order.getDeliveryMode(),
                                    false
                            )
                    );
                    orderedProductsByPNK.put(product.part_number_key, list);
                }
            }
            return orderedProductsByPNK;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        System.out.println(getByProduct());
    }
}
