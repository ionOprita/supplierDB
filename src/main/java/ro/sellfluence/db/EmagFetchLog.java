package ro.sellfluence.db;

import java.time.LocalDateTime;

public record EmagFetchLog(
        String account,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime fetchStartTime,
        LocalDateTime fetchEndTime,
        String error
) {
}
