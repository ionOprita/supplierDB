package ro.sellfluence.emagapi;

import java.time.LocalDate;

public record ExtraInfo(
    LocalDate maximum_finalization_date,
    LocalDate first_pickup_date,
    LocalDate estimated_product_pickup,
    LocalDate estimated_product_reception
){}
