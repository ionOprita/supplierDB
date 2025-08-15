package ro.sellfluence.apphelper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The information needed for the employee sheet.
 *
 * @param orderId
 * @param vendorName
 * @param quantity
 * @param price
 * @param isCompany
 * @param orderDate
 * @param productName
 * @param partNumberKey
 * @param userName
 * @param billingName
 * @param billingPhone
 * @param billingAddress
 * @param shippingSuburb
 * @param clientName
 * @param clientPhone
 * @param deliveryAddress
 * @param deliveryMode
 * @param contacted
 */
public record EmployeeSheetData(
        String orderId,
        String vendorName,
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
        String shippingSuburb,
        String clientName,
        String clientPhone,
        String deliveryAddress,
        String deliveryMode,
        boolean contacted
) {
    public EmployeeSheetData withCalledSet(boolean newContacted) {
        return new EmployeeSheetData(
                orderId,
                vendorName,
                quantity,
                price,
                isCompany,
                orderDate,
                productName,
                partNumberKey,
                userName,
                billingName,
                billingPhone,
                billingAddress,
                shippingSuburb,
                clientName,
                clientPhone,
                deliveryAddress,
                deliveryMode,
                newContacted
        );
    }
}