package ro.sellfluence.emagapi;

import java.time.LocalDateTime;

public record Locker(
        String locker_hash,
        String locker_pin,
        LocalDateTime locker_pin_interval_end
) {
}
