package ro.sellfluence.emagsiteapi;

public record ReviewUser(
        Long id,
        String hash,
        String name,
        ReviewAvatar userAvatar,
        String nickname,
        EmagUrl url,
        String email,
        Boolean isOfficial
) {
    public record ReviewAvatar(String initials, String path, String backgroundColor, ReviewImage image) {
    }
}
