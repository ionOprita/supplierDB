package ro.sellfluence.emagapi;

import org.junit.jupiter.api.Test;
import ro.sellfluence.support.UserPassword;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductOfferDeserializationTest {

    @Test
    void readsOfferResponseWithBooleanOwnership() {
        var response = parse("""
                {
                  "isError": false,
                  "messages": [],
                  "results": [{
                    "id": 243409,
                    "part_number_key": "ES0NKBBBM",
                    "number_of_offers": 3,
                    "buy_button_rank": 1,
                    "best_offer_sale_price": 51.6477,
                    "ownership": true,
                    "category_id": 506,
                    "brand": "Brand test",
                    "name": "Test product",
                    "part_number": "md788hc/a",
                    "sale_price": 51.6477,
                    "status": 1,
                    "campaign": {"id": 243409, "name": "Test campaign"}
                  }]
                }
                """);

        assertFalse(response.isError());
        assertEquals(List.of(), response.messages());
        assertEquals(1, response.results().size());
        var offer = response.results().getFirst();
        assertEquals(243409L, offer.id());
        assertEquals("ES0NKBBBM", offer.part_number_key());
        assertEquals(3, offer.number_of_offers());
        assertEquals(1, offer.buy_button_rank());
        assertEquals(new BigDecimal("51.6477"), offer.best_offer_sale_price());
        assertTrue(offer.ownership());
        assertEquals(506, offer.category_id());
        assertEquals("Brand test", offer.brand());
        assertEquals("Test product", offer.name());
        assertEquals("md788hc/a", offer.part_number());
        assertEquals(new BigDecimal("51.6477"), offer.sale_price());
        assertEquals(1, offer.status());
        assertEquals(243409L, offer.campaign().id());
        assertEquals("Test campaign", offer.campaign().name());
        assertEquals(Map.of(), offer.additionalProperties());
        assertEquals(Map.of(), response.additionalProperties());
    }

    @Test
    void readsProductDocumentationAndAllOfferFieldsWithoutRounding() throws IOException {
        var offer = parse(readFixture()).results().getFirst();

        assertEquals(4294967295L, offer.id());
        assertEquals(506, offer.category_id());
        assertEquals(6001L, offer.vendor_category_id());
        assertEquals("ES0NKBBBM", offer.part_number_key());
        assertEquals("ro_RO", offer.source_language());
        assertEquals("Test product", offer.name());
        assertEquals("md788hc/a", offer.part_number());
        assertEquals("<p>Seller documentation</p>", offer.description());
        assertEquals("Brand test", offer.brand());
        assertEquals(1, offer.force_images_download());
        assertEquals(0, offer.images_overwrite());
        assertEquals("https://example.test/product", offer.url());
        assertEquals(24, offer.warranty());
        assertEquals(List.of("0001234567890"), offer.ean());
        assertEquals(2, offer.status());
        assertEquals(new BigDecimal("51.6477"), offer.sale_price());
        assertEquals(new BigDecimal("79.9999"), offer.recommended_price());
        assertEquals(new BigDecimal("40.0000"), offer.min_sale_price());
        assertEquals(new BigDecimal("90.1234"), offer.max_sale_price());
        assertEquals("EUR", offer.currency_type());
        assertEquals(14, offer.supply_lead_time());
        assertEquals(List.of(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 1)), offer.start_date());
        assertEquals(1, offer.vat_id());
        assertEquals(1, offer.emag_club());
        assertEquals("Keep out of reach of children.", offer.safety_information());
        assertEquals(new BigDecimal("0.1234"), offer.green_tax());
        assertEquals(3, offer.number_of_offers());
        assertEquals(1, offer.buy_button_rank());
        assertEquals(new BigDecimal("49.1234"), offer.best_offer_sale_price());
        assertTrue(offer.ownership());
        assertEquals(12, offer.general_stock());
        assertEquals(10, offer.estimated_stock());

        assertEquals(1, offer.images().getFirst().display_type());
        assertEquals("https://example.test/product.jpg", offer.images().getFirst().url());
        assertEquals(22, offer.characteristics().getFirst().id());
        assertEquals("Gold", offer.characteristics().getFirst().value());
        assertEquals("color", offer.characteristics().getFirst().tag());
        assertEquals(7001L, offer.family().id());
        assertEquals("Product family", offer.family().name());
        assertEquals(3, offer.family().family_type_id());
        assertEquals(4294967295L, offer.attachments().getFirst().id());
        assertEquals("https://example.test/manual.pdf", offer.attachments().getFirst().url());
        assertEquals(1, offer.stock().getFirst().warehouse_id());
        assertEquals(12, offer.stock().getFirst().value());
        assertEquals(1, offer.handling_time().getFirst().warehouse_id());
        assertEquals(3, offer.handling_time().getFirst().value());
        assertFalse(offer.manufacturer());
        assertFalse(offer.eu_representative());
        assertEquals(243409L, offer.campaign().id());
        assertEquals("Test campaign", offer.campaign().name());
    }

    @Test
    void readsAllAdditionalFieldsObservedInTheReadResponse() throws IOException {
        var offer = parse(readFixture()).results().getFirst();

        assertEquals("Brand label", offer.brand_name());
        assertEquals("ro_RO", offer.content_details().source_language());
        assertEquals("RON", offer.currency());
        assertEquals(3, offer.availability().getFirst().value());
        assertNull(offer.availability().getFirst().warehouse_id());
        assertEquals(Map.of("0-30", 12, "365+", 2), offer.stock_2p_aging_bands());
        assertEquals(4294967294L, offer.offer_details().id());
        assertEquals(1, offer.offer_details().warranty_type());
        assertEquals(7, offer.offer_details().supply_lead_time());
        assertEquals(0, offer.offer_details().emag_club());
        assertEquals(1, offer.offer_details().offer_condition());
        assertEquals(new BigDecimal("74.0000"), offer.best_offer_recommended_price());
        assertEquals(new BigDecimal("50.0100"), offer.main_offer_price());
        assertEquals(4294967295L, offer.campaign_id());
        assertEquals("Read campaign", offer.campaign_name());
        assertEquals(0, offer.auto_translated());
        assertEquals(2, offer.fulfillment_type());
        assertEquals(1, offer.genius_computed());
        assertEquals(1, offer.genius_eligibility());
        assertEquals(1, offer.genius_eligibility_type());
        assertEquals(0, offer.recycleWarranties());
        assertTrue(offer.has_smart_deals_badge());
        assertFalse(offer.reversible_vat_charging());
        assertEquals("RRP is eligible to be displayed", offer.rrp_guidelines());
    }

    @Test
    void keepsReadMetadataAtItsActualLocationWithoutFillingSpecificationOnlyFields() {
        var offer = parse("""
                {"isError":false,"results":[{
                  "id":123,"currency":"RON","campaign_id":456,"campaign_name":"Campaign",
                  "content_details":{"source_language":"ro_RO"},
                  "offer_details":{"id":789,"supply_lead_time":14,"emag_club":1},
                  "stock":[{"value":5}],"handling_time":[{"value":3}]
                }]}
                """).results().getFirst();

        assertEquals(123L, offer.id());
        assertEquals(789L, offer.offer_details().id());
        assertEquals(5, offer.stock().getFirst().value());
        assertNull(offer.stock().getFirst().warehouse_id());
        assertEquals(3, offer.handling_time().getFirst().value());
        assertNull(offer.handling_time().getFirst().warehouse_id());
        assertNull(offer.source_language());
        assertNull(offer.supply_lead_time());
        assertNull(offer.emag_club());
        assertNull(offer.currency_type());
        assertNull(offer.campaign());
    }

    @Test
    void readsLocalizedValidationErrorsAndWarningsWithoutDroppingStatuses() throws IOException {
        var offer = parse(readFixture()).results().getFirst();

        assertEquals(1, offer.offer_validation_status().value());
        assertEquals("Saleable", offer.offer_validation_status().description());
        assertNull(offer.offer_validation_status().errors());
        assertEquals(List.of(8, 9), offer.validation_status().stream()
                .map(ProductOffer.ValidationStatus::value).toList());
        assertEquals(List.of(9, 8), offer.translation_validation_status().stream()
                .map(ProductOffer.ValidationStatus::value).toList());
        assertEquals("Approved documentation", offer.validation_status().get(1).description());
        assertNull(offer.validation_status().get(1).errors());
        var details = offer.validation_status().getFirst().errors();
        assertEquals(List.of(), details.info());
        assertEquals(1, details.errors().size());
        var error = details.errors().getFirst();
        assertEquals("UPDATE_NOT_ALLOWED_SAME_PLATFORM", error.code());
        assertEquals("update_vendor_id", error.section());
        assertEquals(Map.of("en_GB", "Update already in progress.", "ro_RO", "Actualizare în curs."), error.message());
        assertEquals(1, details.warnings().size());
        var warning = details.warnings().getFirst();
        assertNull(warning.code());
        assertEquals("disclaimer", warning.section());
        assertEquals(Map.of("en_GB", "Review pending.", "ro_RO", "Revizuire în curs."), warning.message());
        var translationDetails = offer.translation_validation_status().get(1).errors();
        assertEquals(List.of(), translationDetails.errors());
        assertEquals(List.of(), translationDetails.warnings());
        assertEquals(List.of(), translationDetails.info());
    }

    @Test
    void retainsUndocumentedFieldsInTheEnvelopeOfferAndNestedObjects() throws IOException {
        var response = parse(readFixture());
        var offer = response.results().getFirst();

        assertEquals("offer-read-1", response.additionalProperties().get("request_id").stringValue());
        assertTrue(offer.additionalProperties().get("platform_data").get("available").booleanValue());
        assertEquals(2, offer.additionalProperties().get("platform_data").get("tags").size());
        assertEquals("image/jpeg", offer.images().getFirst().additionalProperties().get("media_type").stringValue());
        assertEquals("Auriu", offer.characteristics().getFirst().additionalProperties().get("translated_value").stringValue());
        assertEquals("phones", offer.family().additionalProperties().get("category").stringValue());
        assertEquals("manual", offer.attachments().getFirst().additionalProperties().get("kind").stringValue());
        assertEquals(2, offer.stock().getFirst().additionalProperties().get("reserved").intValue());
        assertFalse(offer.handling_time().getFirst().additionalProperties().get("calendar").get("weekend").booleanValue());
        assertEquals(5, offer.campaign().additionalProperties().get("offer_count").intValue());
        assertEquals("retained", offer.content_details().additionalProperties().get("future_content_detail").stringValue());
        assertEquals("retained", offer.offer_details().additionalProperties().get("future_offer_detail").stringValue());
        assertEquals("retained", offer.availability().getFirst().additionalProperties().get("future_availability_detail").stringValue());
        assertEquals("retained", offer.offer_validation_status().additionalProperties().get("future_status").stringValue());
        var details = offer.validation_status().getFirst().errors();
        assertEquals("retained", details.additionalProperties().get("future_validation_detail").stringValue());
        assertTrue(details.errors().getFirst().additionalProperties().containsKey("identifier"));
        assertTrue(details.errors().getFirst().additionalProperties().get("identifier").isNull());
        assertTrue(details.warnings().getFirst().additionalProperties().get("extraInfo").isNull());
        assertEquals("bg_BG", offer.translation_validation_status().getFirst().additionalProperties().get("languages").get(0).stringValue());
        assertTrue(offer.additionalProperties().get("offer_properties").isEmpty());
        assertTrue(offer.additionalProperties().get("barcode").isEmpty());
        assertTrue(offer.additionalProperties().get("commission").isNull());
        assertTrue(offer.doc_errors().isArray());
        assertEquals("description", offer.doc_errors().get(0).get("field").stringValue());
        assertTrue(response.errors().get("warnings").isArray());
    }

    @Test
    void acceptsNumericStringsAndPreservesUnsignedIdentifiersAndEanZeroes() {
        var offer = parse("""
                {"isError":false,"results":[{
                  "id":"4294967295",
                  "category_id":"506",
                  "status":"2",
                  "sale_price":"1234.5678",
                  "green_tax":"0.0010",
                  "main_offer_price":"0.1234567890123456789",
                  "best_offer_recommended_price":1234.0010,
                  "ean":["0001234567890"],
                  "stock":[{"warehouse_id":"1","value":"65535"}],
                  "attachments":[{"id":"4294967295","url":"https://example.test/manual.pdf"}],
                  "campaign":{"id":"4294967295","name":"Campaign"}
                }]}
                """).results().getFirst();

        assertEquals(4294967295L, offer.id());
        assertEquals(506, offer.category_id());
        assertEquals(2, offer.status());
        assertEquals(new BigDecimal("1234.5678"), offer.sale_price());
        assertEquals(new BigDecimal("0.0010"), offer.green_tax());
        assertEquals(new BigDecimal("0.1234567890123456789"), offer.main_offer_price());
        assertEquals(new BigDecimal("1234.0010"), offer.best_offer_recommended_price());
        assertEquals(List.of("0001234567890"), offer.ean());
        assertEquals(1, offer.stock().getFirst().warehouse_id());
        assertEquals(65535, offer.stock().getFirst().value());
        assertEquals(4294967295L, offer.attachments().getFirst().id());
        assertEquals(4294967295L, offer.campaign().id());
    }

    @Test
    void keepsMissingAndNullOptionalValuesDistinctFromEmptyCollections() {
        var response = parse("""
                {"isError":false,"results":[
                  {},
                  {"id":null,"name":null,"sale_price":null,"ean":null,"stock":null,
                   "family":null,"campaign":null,"doc_errors":null,"start_date":null,
                   "availability":null,"stock_2p_aging_bands":null,"offer_validation_status":null,
                   "validation_status":null,"translation_validation_status":null,
                   "content_details":null,"offer_details":null,"manufacturer":null,"eu_representative":null,
                   "main_offer_price":null,"best_offer_recommended_price":null},
                  {"ean":[],"stock":[],"start_date":[],"availability":[],"stock_2p_aging_bands":{},
                   "validation_status":[],"translation_validation_status":[],
                   "offer_validation_status":{},"content_details":{},"offer_details":{},
                   "images":[{}],"characteristics":[{}],
                   "family":{},"attachments":[{}],"handling_time":[{}],
                   "manufacturer":false,"eu_representative":false,"campaign":{}}
                ]}
                """);

        assertNull(response.messages());
        assertNull(response.errors());
        assertEquals(Map.of(), response.additionalProperties());
        for (var offer : response.results().subList(0, 2)) {
            assertNull(offer.id());
            assertNull(offer.name());
            assertNull(offer.sale_price());
            assertNull(offer.ean());
            assertNull(offer.stock());
            assertNull(offer.start_date());
            assertNull(offer.availability());
            assertNull(offer.stock_2p_aging_bands());
            assertNull(offer.offer_validation_status());
            assertNull(offer.validation_status());
            assertNull(offer.translation_validation_status());
            assertNull(offer.content_details());
            assertNull(offer.offer_details());
            assertNull(offer.manufacturer());
            assertNull(offer.eu_representative());
            assertNull(offer.main_offer_price());
            assertNull(offer.best_offer_recommended_price());
            assertNull(offer.family());
            assertNull(offer.campaign());
            assertTrue(offer.doc_errors() == null || offer.doc_errors().isNull());
            assertEquals(Map.of(), offer.additionalProperties());
        }
        var offerWithCollections = response.results().get(2);
        assertEquals(List.of(), offerWithCollections.ean());
        assertEquals(List.of(), offerWithCollections.stock());
        assertEquals(List.of(), offerWithCollections.start_date());
        assertEquals(List.of(), offerWithCollections.availability());
        assertEquals(Map.of(), offerWithCollections.stock_2p_aging_bands());
        assertEquals(List.of(), offerWithCollections.validation_status());
        assertEquals(List.of(), offerWithCollections.translation_validation_status());
        assertEquals(Map.of(), offerWithCollections.images().getFirst().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.characteristics().getFirst().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.family().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.attachments().getFirst().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.handling_time().getFirst().additionalProperties());
        assertFalse(offerWithCollections.manufacturer());
        assertFalse(offerWithCollections.eu_representative());
        assertNull(offerWithCollections.offer_validation_status().value());
        assertNull(offerWithCollections.offer_validation_status().errors());
        assertEquals(Map.of(), offerWithCollections.offer_validation_status().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.content_details().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.offer_details().additionalProperties());
        assertEquals(Map.of(), offerWithCollections.campaign().additionalProperties());
    }

    @Test
    void keepsMissingAndNullValidationDetailsDistinctFromEmptyLists() {
        var statuses = parse("""
                {"isError":false,"results":[{"validation_status":[
                  {"errors":{}},
                  {"errors":{"errors":null,"warnings":null,"info":null}},
                  {"errors":{"errors":[],"warnings":[],"info":[]}},
                  {"errors":{"errors":[{}],"warnings":[{"code":null,"section":null,"message":null}]}}
                ]}]}
                """).results().getFirst().validation_status();

        for (var status : statuses.subList(0, 2)) {
            assertNull(status.errors().errors());
            assertNull(status.errors().warnings());
            assertNull(status.errors().info());
            assertEquals(Map.of(), status.errors().additionalProperties());
        }
        assertEquals(List.of(), statuses.get(2).errors().errors());
        assertEquals(List.of(), statuses.get(2).errors().warnings());
        assertEquals(List.of(), statuses.get(2).errors().info());
        for (var issue : List.of(statuses.get(3).errors().errors().getFirst(),
                statuses.get(3).errors().warnings().getFirst())) {
            assertNull(issue.code());
            assertNull(issue.section());
            assertNull(issue.message());
            assertEquals(Map.of(), issue.additionalProperties());
        }
    }

    @Test
    void preservesUnspecifiedPropertiesAndErrorStructuresWithoutAssumingTheirElementShape() {
        var response = parse("""
                {"isError":false,"errors":["warning"],"results":[{
                  "offer_properties":[{"future":"value"}],
                  "barcode":["0001234567890"],
                  "commission":{"rate":0.0010},
                  "validation_status":[],
                  "translation_validation_status":null,
                  "doc_errors":{"description":["Missing documentation"]}
                }]}
                """);
        var offer = response.results().getFirst();

        assertEquals("warning", response.errors().get(0).stringValue());
        assertEquals("value", offer.additionalProperties().get("offer_properties").get(0).get("future").stringValue());
        assertEquals("0001234567890", offer.additionalProperties().get("barcode").get(0).stringValue());
        assertEquals(new BigDecimal("0.0010"), offer.additionalProperties().get("commission").get("rate").decimalValue());
        assertEquals(List.of(), offer.validation_status());
        assertNull(offer.translation_validation_status());
        assertEquals("Missing documentation", offer.doc_errors().get("description").get(0).stringValue());
    }

    @Test
    void preservesPrecisionAndScaleOfDecimalsInUndocumentedFieldsAndUnspecifiedStructures() {
        var response = parse("""
                {
                  "isError":false,
                  "future_decimal":0.1234567890123456789,
                  "future_scaled_decimal":0.0010,
                  "errors":{"amount":0.1234567890123456789,"tax":0.0010},
                  "results":[{
                    "future_decimal":0.1234567890123456789,
                    "future_scaled_decimal":0.0010,
                    "images":[{
                      "url":"https://example.test/product.jpg",
                      "future_decimal":0.1234567890123456789,
                      "future_scaled_decimal":0.0010
                    }],
                    "offer_details":{"future_decimal":0.1234567890123456789,"future_scaled_decimal":0.0010},
                    "content_details":{"future_decimal":0.1234567890123456789,"future_scaled_decimal":0.0010},
                    "offer_validation_status":{"threshold":0.1234567890123456789,"minimum":0.0010},
                    "validation_status":[{"threshold":0.1234567890123456789,"minimum":0.0010,"errors":{
                      "future_decimal":0.1234567890123456789,"future_scaled_decimal":0.0010,
                      "errors":[{"future_decimal":0.1234567890123456789,"future_scaled_decimal":0.0010}],
                      "warnings":[{"future_decimal":0.1234567890123456789,"future_scaled_decimal":0.0010}],
                      "info":[{"amount":0.1234567890123456789,"tax":0.0010}]
                    }}],
                    "translation_validation_status":[{"threshold":0.1234567890123456789,"minimum":0.0010}],
                    "doc_errors":{"amount":0.1234567890123456789,"tax":0.0010}
                  }]
                }
                """);
        var offer = response.results().getFirst();
        var exactDecimal = new BigDecimal("0.1234567890123456789");
        var scaledDecimal = new BigDecimal("0.0010");

        var validationDetails = offer.validation_status().getFirst().errors();
        for (var additionalProperties : List.of(response.additionalProperties(), offer.additionalProperties(),
                offer.images().getFirst().additionalProperties(), offer.offer_details().additionalProperties(),
                offer.content_details().additionalProperties(), validationDetails.additionalProperties(),
                validationDetails.errors().getFirst().additionalProperties(),
                validationDetails.warnings().getFirst().additionalProperties())) {
            assertEquals(exactDecimal, additionalProperties.get("future_decimal").decimalValue());
            assertEquals(scaledDecimal, additionalProperties.get("future_scaled_decimal").decimalValue());
        }
        for (var validationStatus : List.of(offer.offer_validation_status(), offer.validation_status().getFirst(),
                offer.translation_validation_status().getFirst())) {
            assertEquals(exactDecimal, validationStatus.additionalProperties().get("threshold").decimalValue());
            assertEquals(scaledDecimal, validationStatus.additionalProperties().get("minimum").decimalValue());
        }
        for (var errors : List.of(response.errors(), offer.doc_errors(), validationDetails.info().getFirst())) {
            assertEquals(exactDecimal, errors.get("amount").decimalValue());
            assertEquals(scaledDecimal, errors.get("tax").decimalValue());
        }
    }

    private static ProductOfferResponse parse(String json) {
        return new EmagApi(new UserPassword("test", "user", "password", null)).parseProductOffers(json);
    }

    private static String readFixture() throws IOException {
        try (var resource = ProductOfferDeserializationTest.class.getResourceAsStream("productOffer.json")) {
            if (resource == null) {
                throw new FileNotFoundException("Test resource not found: productOffer.json");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
