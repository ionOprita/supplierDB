package ro.sellfluence.emagapi;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdsResponseDeserializationTest {
    private static final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

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
}
