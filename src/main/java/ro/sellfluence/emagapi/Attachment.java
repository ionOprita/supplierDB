package ro.sellfluence.emagapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties({"order_id"})
public record Attachment (
    String name,
    String url,
    Integer type,
    Integer force_download,
    String visibility
){ }
