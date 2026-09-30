package ro.sellfluence.emagsiteapi;

import java.util.List;

public record ReviewImage(String original, List<ResizedImage> resizedImages) {
    public ReviewImage {
        resizedImages = resizedImages == null ? List.of() : List.copyOf(resizedImages);
    }

    public record ResizedImage(String size, String url) {
    }
}
