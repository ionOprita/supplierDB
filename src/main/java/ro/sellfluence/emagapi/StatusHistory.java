package ro.sellfluence.emagapi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record StatusHistory(
        String code,
        LocalDateTime event_date,
        List<StatusRequest> requests
) {
    public StatusHistory {
        if (requests == null) {
            requests = new ArrayList<>();
        }
    }
}