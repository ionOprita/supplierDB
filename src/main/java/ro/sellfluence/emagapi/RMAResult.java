package ro.sellfluence.emagapi;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record RMAResult(
        int is_full_fbe,
        int emag_id,
        Integer return_parent_id,
        String order_id,
        int type,
        int is_club,
        int is_fast,
        String customer_name,
        String customer_company,
        String customer_phone,
        String pickup_country,
        String pickup_suburb,
        String pickup_city,
        String pickup_address,
        String pickup_zipcode,
        int pickup_locality_id,
        int pickup_method,
        String customer_account_iban,
        String customer_account_bank,
        String customer_account_beneficiary,
        Integer replacement_product_emag_id,
        Integer replacement_product_id,
        String replacement_product_name,
        Integer replacement_product_quantity,
        String observations,
        int request_status,
        int return_type,
        int return_reason,
        LocalDateTime date,
        List<ReturnedProduct> products,
        ExtraInfo extra_info,
        String return_tax_value,
        String swap,
        String return_address_snapshot,
        List<AWB> awbs,
        List<StatusHistory> status_history,
        List<RequestHistory> request_history,
        @JsonDeserialize(using = LockerDeserializer.class)
        Locker locker,
        Integer return_address_id,
        String country,
        String address_type,
        Integer request_status_reason
) {
    public RMAResult {
        // Normalize Lists
        if (products == null) {
            products = new ArrayList<>();
        }
        if (awbs == null) {
            awbs = new ArrayList<>();
        }
        if (status_history == null) {
            status_history = new ArrayList<>();
        }
        if (request_history == null) {
            request_history = new ArrayList<>();
        }
    }
}