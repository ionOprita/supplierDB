package ro.sellfluence.emagsiteapi;

import java.time.ZonedDateTime;

public record ReviewComment(
        Long id,
        String content,
        ReviewUser user,
        Boolean isActive,
        String moderationStatus,
        String created,
        String modified,
        String published,
        EmagUrl editUrl,
        EmagUrl viewUrl,
        String type,
        ReviewProduct product,
        String contentNoTags,
        Long parentId,
        Boolean isOfficial,
        ZonedDateTime deleted
) {
}
