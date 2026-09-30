package ro.sellfluence.emagsiteapi;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ReviewsData(
        @JsonProperty(value = "count", required = true) int count,
        Review firstItem,
        List<Review> items,
        String summary,
        List<String> suggestedQuestions,
        Map<Integer, Integer> ratingDistribution,
        List<ReviewCharacteristicRating> reviewCharacteristicsAverageRating,
        EmagUrl addUrl,
        EmagUrl viewUrl,
        List<ReviewSortOption> sortOptions,
        Integer positiveRatingPercentage,
        Integer boughtCount,
        String boughtCountFilter,
        String boughtCountMessage,
        String positiveRatingPercentageMessage,
        ReviewMessages messages,
        String family_id
) {
    public ReviewsData {
        items = items == null ? List.of() : List.copyOf(items);
        suggestedQuestions = suggestedQuestions == null ? List.of() : List.copyOf(suggestedQuestions);
        ratingDistribution = ratingDistribution == null ? Map.of() : Map.copyOf(ratingDistribution);
        reviewCharacteristicsAverageRating = reviewCharacteristicsAverageRating == null
                ? List.of()
                : List.copyOf(reviewCharacteristicsAverageRating);
        sortOptions = sortOptions == null ? List.of() : List.copyOf(sortOptions);
    }

    ReviewsData withItems(List<Review> allItems) {
        return new ReviewsData(
                count, firstItem, allItems, summary, suggestedQuestions, ratingDistribution,
                reviewCharacteristicsAverageRating, addUrl, viewUrl, sortOptions,
                positiveRatingPercentage, boughtCount, boughtCountFilter, boughtCountMessage,
                positiveRatingPercentageMessage, messages, family_id
        );
    }

    public record ReviewCharacteristicRating(Long reviewCharacteristicsId, String name, Double averageRating) {
    }

    public record ReviewSortOption(
            String name,
            String direction,
            String placeholder,
            @JsonProperty("isSelected") Boolean selected,
            @JsonProperty("isDefault") Boolean defaultOption
    ) {
    }

    public record ReviewMessages(String tagInfoText, String tagInfoModalText, String tagFilterText) {
    }
}
