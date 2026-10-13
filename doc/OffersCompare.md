# eMAG offer data comparison

The current API model can cover many basic offer fields, but replacing the dashboard
reader would lose explicitly modeled performance, replenishment, review, and dashboard
status data. The API supplies substantially more product documentation. Whether the
dashboard can be retired therefore depends on which of its additional fields are needed
and on verifying the mappings below against offers from the same account and product.

This comparison covers [ProductOffer and its nested records](../src/main/java/ro/sellfluence/emagapi/ProductOffer.java)
and [Offer](../src/main/java/ro/sellfluence/emagdashboard/Offer.java), including
[OfferStock](../src/main/java/ro/sellfluence/emagdashboard/OfferStock.java),
[OfferPrice](../src/main/java/ro/sellfluence/emagdashboard/OfferPrice.java),
[OfferLinks](../src/main/java/ro/sellfluence/emagdashboard/OfferLinks.java),
[Measurements](../src/main/java/ro/sellfluence/emagdashboard/Measurements.java), and
[SupplyRecommendationData](../src/main/java/ro/sellfluence/emagdashboard/SupplyRecommendationData.java).
Nested component names use dotted paths; `[]` identifies list elements.

“Only” means that the other model has no explicit counterpart for that information,
not that eMAG could never expose it through that endpoint. `ProductOffer` includes both
observed response fields and specification fields; omitted fields remain null, and
unknown fields are retained in `additionalProperties`. The
[API fixture](../src/test/resources/ro/sellfluence/emagapi/productOffer.json) and
[deserialization tests](../src/test/java/ro/sellfluence/emagapi/ProductOfferDeserializationTest.java)
demonstrate supported shapes, rather than coverage for every live offer. Cross-source
mappings below are inferred from names and structure: there is no paired fixture or
conversion implementation proving equal identifiers, values, or status codes.

## 1. Data only retrieved through the eMAG API

| Data | `ProductOffer` component(s) and Java type | Difference from the dashboard model |
|---|---|---|
| Seller category reference | `vendor_category_id`: `Long` | No explicit seller category reference. Dashboard `categoryDocId` and `categoryId` are separately modeled category identifiers; their relationship to this field is unverified. |
| Content language and translation flag | `source_language`, `content_details.source_language`: `String`; `auto_translated`: `Integer` | No language or automatic translation fields. Top-level and nested language values are stored independently. |
| Full description | `description`: `String` | Dashboard retains product names, but no description. |
| Product images | `images`: `List<Image>`; `images[].display_type`: `Integer`; `images[].url`: `String` | No image collection. |
| Image import controls | `force_images_download`, `images_overwrite`: `Integer` | No corresponding controls. |
| Product characteristics | `characteristics`: `List<Characteristic>`; `characteristics[].id`: `Integer`; `characteristics[].value`, `characteristics[].tag`: `String` | No general characteristic collection. Dashboard measurements are dedicated fields; the API model defines no characteristic-to-measurement mapping. |
| Product family | `family`: `Family`; `family.id`: `Long`; `family.name`: `String`; `family.family_type_id`: `Integer` | No family or variant-group data. |
| Attachments | `attachments`: `List<Attachment>`; `attachments[].id`: `Long`; `attachments[].url`: `String` | No attachment collection. |
| Separate currency type | `currency_type`: `String` | Dashboard has one `currency` field. API `currency_type` and `currency` are independent components, and are different in the fixture; do not assume one is a fallback for the other. |
| Warranty type and offer condition | `offer_details.warranty_type`, `offer_details.offer_condition`: `Integer` | Dashboard has warranty duration, but no explicit warranty type or condition. |
| Availability codes by warehouse | `availability`: `List<WarehouseValue>`; `availability[].warehouse_id`, `availability[].value`: `Integer` | No explicit warehouse availability-code collection. Offer status is a separate concept. |
| Stock age bands | `stock_2p_aging_bands`: `Map<String, Integer>` | No age-band breakdown. |
| General and estimated stock | `general_stock`, `estimated_stock`: `Integer` | No fields with these meanings. Their equality to dashboard `extStock`, `stock2p`, or `stock3p` has not been established. |
| Product safety information | `safety_information`: `String`; `manufacturer`, `eu_representative`: `Boolean` | No corresponding safety information or flags. These two components are booleans, not contact-detail objects. |
| Documentation ownership | `ownership`: `Boolean` | No ownership flag. |
| Additional competitive prices | `best_offer_recommended_price`, `main_offer_price`: `BigDecimal` | Dashboard has best price and several RRP values, but no established counterpart for these two distinct API price fields. `main_offer_price` must not silently replace `best_offer_sale_price`. |
| Documentation and translation validation | `validation_status`, `translation_validation_status`: `List<ValidationStatus>`; `doc_errors`: `JsonNode` | No distinct documentation/translation status lists or arbitrary documentation-error JSON. Offer validation overlaps are discussed in section 3. |
| Structured validation diagnostics | Within a `ValidationStatus`: `errors`: `ValidationErrors`; its `errors`, `warnings`: `List<ValidationIssue>`; `info`: `List<JsonNode>`. A `ValidationIssue` has `code`, `section`: `String`; `message`: `Map<String, String>` | Dashboard has status and invalidation labels, but no equivalent error/warning/info structure with localized messages. |
| Campaign association | `campaign`: `Campaign`; `campaign.id`, `campaign_id`: `Long`; `campaign.name`, `campaign_name`: `String` | No campaign association in `Offer`. This concerns the offer models, not the repository's separate Ads readers. Nested and top-level campaign fields are independent. |
| Smart Deals badge | `has_smart_deals_badge`: `Boolean` | No explicit Smart Deals badge. |
| Reverse VAT charging | `reversible_vat_charging`: `Boolean` | No corresponding flag. |
| Unmodeled response data | `additionalProperties`: `Map<String, JsonNode>` on `ProductOffer` and its nested records | Arbitrary extra API fields are retained, including nested fields. Dashboard queries explicit fields and enables `FAIL_ON_UNKNOWN_PROPERTIES`. The API fixture also retains `barcode`, `commission`, and `platform_data`; their contents are not a fixed typed contract. |

`ProductOffer.ContactInformation` declares `name`, `address`, and `email`, but no
`ProductOffer` component references that record. Its existence does not mean those
contact details are retrieved. Likewise, a nested record declaration alone does not
prove that a field is present in a live response.

## 2. Data only retrieved from the dashboard

| Data | `Offer` component(s) and Java type | Difference from the API model |
|---|---|---|
| Seller identity | `sellerId`: `Integer`; `sellerName`: `String` | No explicit seller identity in each `ProductOffer`. The API account identifies the request's seller outside that record. |
| Additional product and platform identifiers | `categoryDocId`, `docProductId`, `docProductBrandMktId`, `platformId`, `type`: `Integer`; `mktId`: `String` | No explicitly matching components. Do not equate `docProductId` or `mktId` with the API seller product ID or nested offer ID. |
| Separate catalog and seller content | `docProductName`, `docProductPartNumber`, `docProductBrandName`, alongside `extPartNumber` and `brandName`: `String` | API has `name`, `part_number`, `brand`, and `brand_name`, but does not explicitly separate canonical catalog content from seller content in the same way. Shared content concepts have the same Java type; the dashboard's separate copies are additional information. |
| Site safety flag | `isUnsafeForSite`: `Boolean` | API `safety_information` is textual product safety information, not an equivalent site-safety flag. |
| Inactivation criticalities | `inactivationCriticalities`: `List<String>` | No explicitly corresponding criticality list. |
| Detailed invalidation history | `invalidationReason`, `invalidationReasonName`, `invalidationSubReason`, `invalidationSubReasonName`: `String`; `invalidationReasonDate`: `LocalDateTime` | API validation can contain errors and descriptions, but does not explicitly expose these reason/subreason fields or their timestamp. |
| Explicit stock split | `stock2p`, `stock3p`: `Integer` | No dedicated API components for these two stock totals. API warehouse values and stock age bands do not establish how to reproduce this split. |
| Unfair-price value | `offerDetailsUnfairPrice`: `BigDecimal` | No corresponding price field. |
| Locker eligibility | `offerDetailsLockerEligibility`: `Boolean` | No explicit locker-eligibility component. Do not infer it from a general Genius/club flag. |
| Suggested selling price | `productPerformanceSuggestedPrice`: `BigDecimal` | No explicit suggested selling price. Recommended/reference prices and `main_offer_price` are not established substitutes. |
| Forecast stock requirements | `productPerformanceNeededStockNext7`, `productPerformanceNeededStockNext30`: `Integer` | No corresponding 7/30-day stock requirements. |
| Stock depletion estimate | `productPerformanceDepletionDays`: `Integer` | No depletion-days metric. |
| Order values | `productPerformanceOrderValue1`, `productPerformanceOrderValue2`: `BigDecimal` | No corresponding order-value metrics. The model alone does not define the periods represented by the suffixes. |
| Lost order value | `productPerformanceLostOrderValue`: `BigDecimal` | No lost-order-value metric. |
| Out-of-stock history | `productPerformanceDaysWithoutStock`: `Integer`; `offersOutOfStockDay`: `LocalDateTime` | API stock values do not include these historical fields. |
| Review summary | `productPerformanceMultiofferNoOfReviews`: `Integer`; `productPerformanceMultiofferReviewScore`: `BigDecimal` | No review count or score in `ProductOffer`. |
| Replenishment flags | `isStockSupplyNeeded`, `hasSupplyRecommendation`: `Boolean` | No corresponding recommendation flags. |
| Recommended replenishment quantity and period | `supplyRecommendationData.recommendedQtyReplenish`, `supplyRecommendationData.replenishmentPeriod`: `Integer` | No replenishment recommendation. |
| Forecast time until out of stock | `supplyRecommendationData.daysUntilOos`, `supplyRecommendationData.estimatedDaysUntilOos`: `Integer` | No corresponding forecasts. |
| Recent stock and sales history | `supplyRecommendationData.daysInStockL35`, `supplyRecommendationData.unitsSoldL35`: `Integer` | No corresponding history metrics. |
| Forecast units | `supplyRecommendationData.unitsForecastN35`: `Integer` | No corresponding unit forecast. |
| Replenishment stock context | `supplyRecommendationData.unitsInTransit`, `supplyRecommendationData.stockInCurrentDay`: `Integer` | No explicitly corresponding in-transit/recommendation-context quantities. API stock values do not prove equivalence to the latter. |
| Physical measurements | `measurements`: `List<Measurements>`; `measurements[].weight`, `measurements[].height`, `measurements[].length`, `measurements[].width`: `BigDecimal` | No dedicated API dimensions/weight fields. They might appear in characteristics or extra JSON, but no conversion is defined. Dashboard object/array input is normalized to a list by its deserializer. |
| Original price and multiple RRP facets | `extOriginalSalePrice`, `rrp`, `rrpEmag`, `rrpSeller`, `offerStock.rrpValue`: `BigDecimal` | API also has reference-price concepts, notably `recommended_price`. What is additional here is the separate original/eMAG/seller/display price representation; no field-by-field mapping is established. This is a semantic difference, not a Java type difference. |
| RRP classification and badge | `rrpEmagType`, `rrpType`, `rrpBadge`: `Integer`; `offerStock.rrpBadgeColor`: `String` | No explicit RRP type, badge code, or badge color. API `rrp_guidelines` overlaps only the guideline text. |
| Dashboard stock/category/status labels | `offerStock.category`, `offerStock.statusType`, `offerStock.statusName`, `offerStock.inactivationReason`, `offerStock.invalidationReasonLabel`: `String` | No dedicated fields for these dashboard labels. API status descriptions may overlap, but do not establish exact label mappings. |
| Hotness classification | `offerStock.hotnessType`, `offerStock.hotnessName`: `String` | No explicit hotness classification. |
| Stock property classifications | `offerStock.propertyTypes`: `List<String>` | No explicit stock-property classification collection; its relationship to generic API `offer_properties` is unverified. |
| Supplier ordering deadline | `offerStock.placeOrderToSupplierUntil`: `LocalDate` | API `supply_lead_time` is a duration, not an ordering deadline. |
| Mandatory EAN flag | `offerPrice.isEanMandatory`: `Boolean` | API retrieves EAN values, but not an explicit mandatory-EAN flag. |
| Dashboard price status details | `offerPrice.statusDetails`: `String` | No dedicated price-status-details field. API validation descriptions do not prove an exact replacement. |
| Dashboard navigation links | `links.edit`, `links.seller`, `links.details`: `String` | API `url` is the product URL, not these dashboard navigation links. |

[FetchOffers](../src/main/java/ro/sellfluence/emagdashboard/FetchOffers.java) explicitly
requests these fields through the dashboard's GraphQL endpoint using the Playwright
session; it does not merely scrape the visible offer table. All modeled offer fields
and nested records are persisted by
[OffersTable](../src/main/java/ro/sellfluence/db/OffersTable.java). Returned optional
values can still be null.

## 3. Data retrieved by both methods, but represented with different types

These are overlapping concepts or candidate mappings, not verified conversions.
Mappings whose meaning remains uncertain are identified explicitly.

| Data | API component and Java type | Dashboard component and Java type | Mapping implications |
|---|---|---|---|
| Seller product/external identifier | `id`: `Long` | `extId`: `String` | Candidate identity mapping. Parse/format only after confirming that the identifiers match. API tests deliberately support values above the signed `Integer` range. |
| Offer identifier | `offer_details.id`: `Long` | `id`: `String` | Candidate identity mapping. The API explicitly distinguishes this nested offer ID from its top-level seller product ID. Dashboard `id` is the persisted offer key; do not substitute API top-level `id` without validation. |
| Stock quantity | `stock`: `List<WarehouseValue>`, with `warehouse_id`, `value`: `Integer` | `extStock`: `Integer` | API retains a list and warehouse identity; dashboard retains a scalar. A selection/aggregation rule must be verified. A warehouse ID may be omitted. The separate dashboard `stock2p`/`stock3p` split remains unresolved (section 2). |
| Handling time | `handling_time`: `List<WarehouseValue>`, with `warehouse_id`, `value`: `Integer` | `extHandlingTime`: `Integer` | API retains warehouse-specific values; dashboard has one value. Choosing the first entry is not a verified mapping. |
| Scheduled start information | `start_date`: `List<LocalDate>` | `offerStock.startDate`: `Boolean` | Related names, but unconfirmed meaning. The API stores actual dates; the dashboard stores a flag. The flag cannot reconstruct the date list. |
| Fulfillment mode | `fulfillment_type`: `Integer` | `fullfilledByEmag`: `Boolean` | Candidate encoded mode versus FBE flag. No integer-code-to-boolean conversion is defined in this repository. The dashboard spelling is exactly `fullfilledByEmag`. |
| Genius/club eligibility | `genius_eligibility`: `Integer` | `offerPrice.isEmagClubEligible`: `Boolean` | Candidate eligibility mapping. Confirm code meanings and whether both fields describe the same eligibility before converting. |
| Offer validation state | `offer_validation_status`: `ValidationStatus`, containing `value`: `Integer`, `description`: `String`, and `errors`: `ValidationErrors` | `valid`: `Integer`, plus dashboard status/reason strings in `Offer`, `OfferStock`, and `OfferPrice` | Partly overlapping validity information. A record cannot be compared directly to a scalar; even `offer_validation_status.value` is not proven to use the same codes as `valid`. API diagnostics and dashboard invalidation history retain different detail (sections 1 and 2). |
| Offer properties | `additionalProperties["offer_properties"]`: `JsonNode` (when returned) | `offerProperties`: `List<String>` | Candidate property collection retained dynamically by the API. The fixture has an empty array; API tests also retain an array of objects. Verify actual shape and meaning before converting to strings. This is not safely classified as dashboard-only data. |

Many renamed fields already have the same declared Java type and are consequently
excluded from the table above. The principal same-type mappings to verify are:

- `List<String>`: `ean` ↔ `eans`.
- `String`: `part_number_key` ↔ `docProductPartNumberKey`; `part_number` ↔
  `extPartNumber`; `url` ↔ `extUrl`; `currency` ↔ `currency`;
  `rrp_guidelines` ↔ `offerStock.rrpGuideline`. Product name and brand concepts
  (`name`, `brand`, `brand_name` versus `docProductName`, `brandName`,
  `docProductBrandName`) also use strings, with the seller/catalog distinction
  described in section 2.
- `Integer`: `category_id` ↔ `categoryId`; `status` ↔ `extStatus`;
  `warranty` ↔ `extWarranty`; `vat_id` ↔ `offerStock.vatId`;
  `number_of_offers` ↔ `productPerformanceMultiofferOffersCount`;
  `buy_button_rank` ↔ `productPerformanceBuyButtonRank`;
  `recycleWarranties` ↔ `recycleWarrantiesQuantity`.
- `Integer`: `supply_lead_time` / `offer_details.supply_lead_time` ↔
  `offerDetailsSupplyLeadTime`; `emag_club` / `offer_details.emag_club` ↔
  `offerDetailsEmagClub`. Top-level and nested API values are independent;
  missing top-level fields are not automatically filled from nested fields.
- `BigDecimal`: `sale_price` ↔ `extSalePrice`; `min_sale_price` ↔ `minPrice`;
  `max_sale_price` ↔ `maxPrice`; `green_tax` ↔ `greenTax`;
  `best_offer_sale_price` ↔ `productPerformanceMultiofferBestPrice`.
  `recommended_price` also overlaps the RRP/reference-price concept, but an exact
  mapping to the dashboard's multiple prices remains unresolved (section 2).
- Weak same-type candidates, all `Integer`: `genius_eligibility_type` ↔
  `offerDetailsEmagClubType`; `genius_computed` ↔ `offerDetailsEmagClubMerged`.
  These are related club/Genius code fields, with no verified equivalence.

Same types do not guarantee the same meaning or price basis. The
[README's API inspection notes](../Readme.md#inspect-offers-through-the-direct-emag-api)
state that API `sale_price` excludes VAT and that product content is seller-submitted.
The dashboard price VAT basis and canonical-content equivalence need checking before
substitution.

For the current [Product Performance report](ProductPerformance.md), the offer-derived
inputs are PNK, `extStock`, and `extSalePrice`, scoped by vendor and snapshot date.
API `part_number_key`, `stock`, and `sale_price` are plausible replacements once stock
selection and price equivalence are verified. That narrower use case does not require
all dashboard-only analytics. However,
[FetchProductOffer](../src/main/java/ro/sellfluence/test/FetchProductOffer.java) currently
archives API responses and prints records without updating the database, while the
dashboard reader writes daily snapshots. Retiring the dashboard reader also requires
an API snapshot-storage path that preserves the report's vendor/date scope and handling
of missing or multiple matching offers.
