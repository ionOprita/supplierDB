package ro.sellfluence.emagapi;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ro.sellfluence.sheetSupport.Conversions.toLocalDateTime;

class AdsResponseDeserializationTest {
    private static final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(
                    new SimpleModule()
                            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer())
            )
            .build();

    @Test
    void deserializesCampaignAdSetsResponse() throws Exception {
        var json = Files.readString(Path.of("AdsJSON", "adsCampaignAdSets.json"));

        var response = objectMapper.readValue(json, AdsCampaignAdSetsResponse.class);

        assertEquals(1, response.meta().totalCount());
        assertEquals(505390, response.data().id());
        assertEquals(LocalDateTime.of(2026, 2, 15, 0, 0), response.data().dateStart());
        assertNull(response.data().dateEnd());
        assertEquals(1, response.data().adsets().size());
        assertEquals(557688, response.data().adsets().getFirst().id());
        assertEquals(new BigDecimal("3.62"), response.data().adsets().getFirst().recommendedBid().bid());
        assertEquals(2, response.data().adsets().getFirst().summary().keywordCount());
        assertNull(response.data().adsets().getFirst().summary().productTargetCount());
    }

    @Test
    void deserializesCampaignPhrasesResponse() throws Exception {
        var json = Files.readString(Path.of("AdsJSON", "adsCampaignPhrases.json"));

        var response = objectMapper.readValue(json, AdsCampaignPhrasesResponse.class);

        assertEquals(3779, response.meta().totalCount());
        assertEquals(100, response.data().searchPhrases().size());
        assertEquals(180461, response.data().adsets().getFirst().id());
        assertEquals("philips oneblade", response.data().searchPhrases().getFirst().searchPhrase());
    }

    @Test
    void deserializesCampaignTargetedProductsResponse() throws Exception {
        var json = Files.readString(Path.of("AdsJSON", "adsCampaignTargetedProducts.json"));

        var response = objectMapper.readValue(json, AdsCampaignTargetedProductsResponse.class);

        assertEquals(4192, response.meta().totalCount());
        assertEquals(100, response.data().docs().size());
        assertEquals(174138, response.data().campaign().id());
        assertEquals(102562071, response.data().docs().getFirst().docId());
    }

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }
}
