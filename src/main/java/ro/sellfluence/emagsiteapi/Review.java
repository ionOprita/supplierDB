package ro.sellfluence.emagsiteapi;

import java.util.List;

public record Review(
        Long id,
        String content,
        ReviewUser user,
        Boolean isActive,
        String moderationStatus,
        String created,
        String modified,
        String published,
        String deleted,
        String reportReason,
        EmagUrl editUrl,
        EmagUrl viewUrl,
        String type,
        String title,
        ReviewProduct product,
        String contentNoTags,
        Integer rating,
        Boolean isBought,
        Integer votes,
        Boolean currentCustomerHasVoted,
        List<ReviewComment> comments,
        Long brandId,
        Long categoryId,
        Long offerId,
        String clientType,
        String clientTypeInfo,
        Long productDocId,
        Long productFamilyId,
        Boolean allowCommentsLikes,
        Boolean hasMedia,
        List<ReviewImage> pictures
) {
    public Review {
        comments = comments == null ? List.of() : List.copyOf(comments);
        pictures = pictures == null ? List.of() : List.copyOf(pictures);
    }
}
