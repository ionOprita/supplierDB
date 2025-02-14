package ro.sellfluence.emagapi;

import java.time.LocalDateTime;

public record RequestHistory(
     long id,
     String user,
     String action,
     String action_type,
     String source,
     LocalDateTime date
) {
}
