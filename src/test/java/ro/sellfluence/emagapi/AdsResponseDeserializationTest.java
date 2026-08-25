package ro.sellfluence.emagapi;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
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
        var response = readResponse("adsCampaignAdSets.json", AdsCampaignAdSetsResponse.class);

        assertEquals(1, response.meta.totalCount());
        assertEquals(505390, response.data.id());
        assertEquals(LocalDateTime.of(2026, 2, 15, 0, 0), response.data.dateStart());
        assertNull(response.data.dateEnd());
        assertEquals(1, response.data.adsets().size());
        assertEquals(557688, response.data.adsets().getFirst().id());
        assertEquals(new BigDecimal("3.62"), response.data.adsets().getFirst().recommendedBid().bid());
        assertEquals(2, response.data.adsets().getFirst().summary().keywordCount());
        assertNull(response.data.adsets().getFirst().summary().productTargetCount());
    }

    @Test
    void deserializesCampaignPhrasesResponse() throws Exception {
        var response = readResponse("adsCampaignPhrases.json", AdsCampaignPhrasesResponse.class);

        assertEquals(3779, response.meta.totalCount());
        assertEquals(100, response.data.searchPhrases().size());
        assertEquals(180461, response.data.adsets().getFirst().id());
        assertEquals("philips oneblade", response.data.searchPhrases().getFirst().searchPhrase());
    }

    @Test
    void deserializesCampaignTargetedProductsResponse() throws Exception {
        var response = readResponse("adsCampaignTargetedProducts.json", AdsCampaignTargetedProductsResponse.class);

        assertEquals(4192, response.meta.totalCount());
        assertEquals(100, response.data.docs().size());
        assertEquals(174138, response.data.campaign().id());
        assertEquals(102562071, response.data.docs().getFirst().docId());
    }

    @Test
    void deserializesCampaignKeywordsResponse() throws Exception {
        var response = readResponse("adsCampaignKeywords.json", AdsCampaignKeywordsResponse.class);

        assertEquals(2, response.meta.totalCount());
        assertEquals(new BigDecimal("12.5"), response.data.summary().averageCostOfSale());
        assertEquals(2, response.data.keywords().size());
        var keyword = response.data.keywords().getFirst();
        assertEquals(1001, keyword.id());
        assertEquals(new BigDecimal("1.25"), keyword.bid());
        assertEquals("active", keyword.status());
        assertEquals("example exact keyword", keyword.keyword());
        assertEquals("exact", keyword.matchType());
        assertEquals("active", keyword.inheritedStatus());
        assertEquals(new BigDecimal("1.2"), keyword.inheritedBid());
        assertEquals(2001, keyword.adset().id());
        assertEquals("Example keyword adset", keyword.adset().name());
        assertEquals(3, keyword.summary().clicks());
    }

    private static <T> T readResponse(String resourceName, Class<T> responseType) throws IOException {
        try (var resource = AdsResponseDeserializationTest.class.getResourceAsStream(resourceName)) {
            if (resource == null) {
                throw new FileNotFoundException("Test resource not found: " + resourceName);
            }
            return objectMapper.readValue(resource, responseType);
        }
    }

    private static class LocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return toLocalDateTime(p.getString());
        }
    }
}
