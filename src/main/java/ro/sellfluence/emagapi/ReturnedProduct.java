package ro.sellfluence.emagapi;

public record ReturnedProduct(
        int id,
        Integer product_emag_id,
        int product_id,
        int quantity,
        String product_name,
        int return_reason,
        String observations,
        String diagnostic,
        Integer reject_reason,
        int retained_amount
) {}
