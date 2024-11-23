package ro.sellfluence.emagapi;

import java.time.LocalDateTime;

public record StatusHistory(
        String code,
        LocalDateTime event_date,
        StatusRequest[] requests
) { }