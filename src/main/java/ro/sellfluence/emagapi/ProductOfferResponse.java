package ro.sellfluence.emagapi;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** A single response page from {@code product_offer/read}. */
public record ProductOfferResponse(
        Boolean isError,
        List<String> messages,
        List<ProductOffer> results,
        JsonNode errors,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
) {
    public ProductOfferResponse {
        if (additionalProperties == null) {
            additionalProperties = Map.of();
        }
    }
}
