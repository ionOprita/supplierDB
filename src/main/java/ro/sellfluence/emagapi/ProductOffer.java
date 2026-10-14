package ro.sellfluence.emagapi;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Seller product documentation and offer data returned by {@code product_offer/read}.
 * Fields omitted by eMAG remain null. Models both observed response fields and
 * fields described by the API specification, retaining unknown fields as JSON.
 */
public record ProductOffer(
        Long id,
        Integer category_id,
        Long vendor_category_id,
        String part_number_key,
        String source_language,
        String name,
        String part_number,
        String description,
        String brand,
        String brand_name,
        ContentDetails content_details,
        Integer force_images_download,
        List<Image> images,
        Integer images_overwrite,
        List<Characteristic> characteristics,
        Family family,
        String url,
        Integer warranty,
        List<String> ean,
        List<Attachment> attachments,
        Integer status,
        BigDecimal sale_price,
        BigDecimal recommended_price,
        BigDecimal min_sale_price,
        BigDecimal max_sale_price,
        String currency_type,
        String currency,
        List<WarehouseValue> stock,
        List<WarehouseValue> handling_time,
        List<WarehouseValue> availability,
        Map<String, Integer> stock_2p_aging_bands,
        Integer supply_lead_time,
        List<LocalDate> start_date,
        Integer vat_id,
        Integer emag_club,
        OfferDetails offer_details,
        String safety_information,
        Boolean manufacturer,
        Boolean eu_representative,
        BigDecimal green_tax,
        Integer number_of_offers,
        Integer buy_button_rank,
        BigDecimal best_offer_sale_price,
        BigDecimal best_offer_recommended_price,
        BigDecimal main_offer_price,
        Boolean ownership,
        Integer general_stock,
        Integer estimated_stock,
        ValidationStatus offer_validation_status,
        List<ValidationStatus> validation_status,
        List<ValidationStatus> translation_validation_status,
        JsonNode doc_errors,
        Campaign campaign,
        Long campaign_id,
        String campaign_name,
        Integer auto_translated,
        Integer fulfillment_type,
        Integer genius_computed,
        Integer genius_eligibility,
        Integer genius_eligibility_type,
        Integer recycleWarranties,
        Boolean has_smart_deals_badge,
        Boolean reversible_vat_charging,
        String rrp_guidelines,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
) {
    public ProductOffer {
        if (additionalProperties == null) {
            additionalProperties = Map.of();
        }
    }

    public record Image(
            Integer display_type,
            String url,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public Image {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record Characteristic(
            Integer id,
            String value,
            String tag,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public Characteristic {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record Family(
            Long id,
            String name,
            Integer family_type_id,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public Family {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record Attachment(
            Long id,
            String url,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public Attachment {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    /** Stock quantities, handling time in days, or availability codes; warehouse_id may be omitted. */
    public record WarehouseValue(
            Integer warehouse_id,
            Integer value,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public WarehouseValue {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record ContactInformation(
            String name,
            String address,
            String email,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public ContactInformation {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record Campaign(
            Long id,
            String name,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public Campaign {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record ContentDetails(
            String source_language,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public ContentDetails {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    /** Offer metadata; this id is distinct from the top-level seller product id. */
    public record OfferDetails(
            Long id,
            Integer warranty_type,
            Integer supply_lead_time,
            Integer emag_club,
            Integer offer_condition,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public OfferDetails {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record ValidationStatus(
            Integer value,
            String description,
            ValidationErrors errors,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public ValidationStatus {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record ValidationErrors(
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            List<JsonNode> info,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public ValidationErrors {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }

    public record ValidationIssue(
            String code,
            String section,
            Map<String, String> message,
            @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additionalProperties
    ) {
        public ValidationIssue {
            if (additionalProperties == null) {
                additionalProperties = Map.of();
            }
        }
    }
}
