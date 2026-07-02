package ro.sellfluence.emagapi;

import java.util.List;

public record AdsAnalyticsResponse(
        List<Object> meta,
        AdsAnalyticsData data
) {
}
