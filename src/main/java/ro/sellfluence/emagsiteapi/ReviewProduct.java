package ro.sellfluence.emagsiteapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewProduct(
        Long id,
        String name,
        String partNumberKey,
        ReviewImage image,
        ReviewOffer offer,
        EmagUrl url,
        String sefName
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewOffer(Long id, ReviewPrice price) {
    }
}
