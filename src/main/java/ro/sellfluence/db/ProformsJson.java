package ro.sellfluence.db;

import ro.sellfluence.emagapi.Proform;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;

// TODO: Does this need to be stored in the database?
/** Encodes complete proform lists in the existing order TEXT column. */
final class ProformsJson {
    private static final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final JavaType listType = mapper.getTypeFactory()
            .constructCollectionType(List.class, Proform.class);

    private ProformsJson() {
    }

    static String encode(List<Proform> proforms) throws SQLException {
        try {
            if (proforms == null) {
                throw new IllegalArgumentException("The proforms list must not be null");
            }
            return mapper.writeValueAsString(proforms);
        } catch (RuntimeException exception) {
            throw new SQLException("Could not encode order proforms for database storage", exception);
        }
    }

    /** SQL null and blank legacy columns mean no proforms; nonblank values must be JSON arrays. */
    static List<Proform> decode(String json, String orderId) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Proform> proforms = mapper.readValue(json, listType);
            if (proforms == null) {
                throw new IllegalArgumentException("Expected a proforms JSON array, received JSON null");
            }
            return proforms;
        } catch (RuntimeException exception) {
            throw new SQLException("Could not decode stored proforms for order %s: "
                    .formatted(orderId)
                    + "expected a compatible JSON array; legacy text or corrupt JSON was not discarded", exception);
        }
    }
}
