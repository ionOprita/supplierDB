package ro.sellfluence.emagdashboard;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FetchOffersDeserializationTest {
    private static final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new SimpleModule()
                    .addDeserializer(LocalDate.class, new FetchOffers.LocalDateDeserializer())
                    .addDeserializer(LocalDateTime.class, new FetchOffers.LocalDateTimeDeserializer()))
            .build();

    @Test
    void deserializesOfferEnvelopeAndNormalizesMeasurements() {
        var response = mapper.readValue(jsonWithMeasurements(), OffersResponse.class);

        assertEquals(2, response.data().offers().items().size());
        assertEquals(91, response.data().offers().totalNumberOfItems());
        assertEquals(338, response.data().offers().items().getFirst().measurements().getFirst().weight().intValue());
        assertEquals(1, response.data().offers().items().getFirst().measurements().size());
        assertEquals(0, response.data().offers().items().get(1).measurements().size());
        assertEquals(LocalDate.of(2027, 10, 4),
                response.data().offers().items().getFirst().offerStock().placeOrderToSupplierUntil());
        assertEquals(LocalDateTime.of(2025, 11, 7, 22, 0),
                response.data().offers().items().getFirst().offersOutOfStockDay());
    }

    @Test
    void rejectsUnexpectedMeasurementsShape() {
        var malformed = jsonWithMeasurements().replace("\"measurements\": []", "\"measurements\": \"unexpected\"");

        assertThrows(RuntimeException.class, () -> mapper.readValue(malformed, OffersResponse.class));
    }

    private static String jsonWithMeasurements() {
        return """
                {
                  "data": {
                    "offers": {
                      "totalNumberOfItems": 91,
                      "items": [
                        {
                          "id": "offer_1",
                          "measurements": {"weight": 338, "height": 202, "length": 62, "width": 146},
                          "offerStock": {"placeOrderToSupplierUntil": "2027-10-04"},
                          "offersOutOfStockDay": "2025-11-07 22:00:00"
                        },
                        {
                          "id": "offer_2",
                          "measurements": []
                        }
                      ]
                    }
                  }
                }
                """;
    }
}
