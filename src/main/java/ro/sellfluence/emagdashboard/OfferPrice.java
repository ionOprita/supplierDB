package ro.sellfluence.emagdashboard;

public record OfferPrice(
        Boolean isEanMandatory,
        Boolean isEmagClubEligible,
        String statusDetails
) {
}
