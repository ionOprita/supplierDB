package ro.sellfluence.emagapi;

public record RequestHistory(
     long id,
     String user,
     String action,
     String action_type,
     String source
) {
}
