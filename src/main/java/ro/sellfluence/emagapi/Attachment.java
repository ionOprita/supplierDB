package ro.sellfluence.emagapi;

public record Attachment (
    String order_id,
    String name,
    String url,
    Integer type,
    Integer force_download,
    //TODO: Added field
    String visibility
){}
