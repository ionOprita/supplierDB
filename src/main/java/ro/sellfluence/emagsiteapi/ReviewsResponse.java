package ro.sellfluence.emagsiteapi;

import java.util.List;

public record ReviewsResponse(int code, ReviewsData data, List<String> metadata, String notifications ) {
    ReviewsResponse withItems(List<Review> items) {
        return new ReviewsResponse(code, data.withItems(items), metadata, notifications);
    }
}
