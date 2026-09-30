package ro.sellfluence.emagsiteapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewProduct(
        Long id,
        String name,
        String partNumberKey,
        ReviewImage image,
        ReviewOffer offer,
        EmagUrl url,
        String sefName,
        FamilyCharacteristics familyCharacteristics
) {
    /** Matches the first nested characteristic used for the review export's Optiune column. */
    public String firstFamilyCharacteristicValue() {
        if (familyCharacteristics == null || familyCharacteristics.characteristics() == null
                || familyCharacteristics.characteristics().isEmpty()) return null;
        var first = familyCharacteristics.characteristics().getFirst();
        return first == null || first.value() == null ? null : first.value().value();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FamilyCharacteristics(List<Characteristic> characteristics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Characteristic(CharacteristicValue value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacteristicValue(String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewOffer(Long id, ReviewPrice price) {
    }
}
