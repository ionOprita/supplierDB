package ro.sellfluence.emagapi;

import java.util.List;

public record AdsAnalyticsResponse(
        List<Object> meta,
        AdsAnalyticsData data,
        String error,
        String message,
        Integer status,
        String code
) {
}
