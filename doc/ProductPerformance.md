# Product performance calculations

`/app/productPerformance?vendorId=…&productCode=…&period=week` returns database-backed
advertising performance for one vendor/product. `period` accepts `week` (the default)
or `month`. The response keeps the dashboard's column definitions and adds an `errors`
list; `mock` is always false. Vendor/product membership is checked before reading ads.

## Sources and product attribution

`ProductPerformanceTable` reads daily `ads_adset` summaries and joins `ads_campaign`
on **vendor_id, report_date, campaign_id**. Both snapshots must have `status = 'active'`
on that report date. Neither `inherited_status` nor a later snapshot replaces this rule.

Adset names and known, nonblank product PNKs are trimmed. A recognized adset PNK has
priority over the campaign name, including when it names a different product. Otherwise,
the campaign name is searched for complete PNK tokens belonging to that vendor; a PNK
must be delimited by the start/end of the name or non-alphanumeric characters. Matching
is case-sensitive. Multiple distinct recognized PNKs make fallback attribution ambiguous,
and are reported rather than assigning the entire adset to each product. Repeated product
rows with the same PNK do not multiply metrics. A product without a PNK has no matched ads.

`product.emag_ads_auto_id` and `product.emag_ads_manual_id` are not used to restrict
campaigns: a product can have many campaigns beyond these two stored identifiers.

Classification uses the adset's `targeting`:

| Targeting | Dashboard section |
|---|---|
| `auto` | Auto |
| `products` | Product |
| `keywords` | Broad or Exact, determined below |

For a keyword adset, distinct `ads_keyword.match_type` values are collected for the
same **vendor_id, report_date, campaign_id, adset_id**. `negative` entries are ignored;
keyword status is not a filter. Exactly one remaining type must exist and must be
`broad` or `exact`. No remaining type, both types, or any unsupported/null type is an
error, except for the all-zero case below. Classification produces one result per
adset snapshot, so joining multiple keywords cannot multiply the adset's values.

When no non-negative match type remains, classification is unnecessary if all six
adset primitives (impressions, clicks, spend, sales, sold units, and sales count) are
present and numerically zero. Such a snapshot produces no keyword-classification
error and preserves its observed week/month without assigning a category or
invalidating totals and shares. Any nonzero or missing primitive prevents this
exception. Conflicting or unsupported match types still produce errors, and other
validations still run.

All numeric advertising inputs come from `ads_adset`. No numeric keyword or targeted
product values are read, and `ads_search_phrase` is not used at all.

## Periods and formulas

`ProductPerformanceData` accumulates daily primitives into Monday–Sunday weeks or
calendar months. The response's historical `week` key contains the period's start date,
including in month mode. Rows are sorted oldest to newest. The UI initially shows the
latest 20 rows; “Show everything” reveals all returned periods. Switching period mode
requests new aggregates rather than relabelling existing rows.

Only periods containing eligible snapshots are returned. Partial periods sum the
available daily reports, including the first/last stored period; missing dates are not
filled with invented observations. The category accumulators are combined to produce
Total, and the same calculator derives all rates after aggregation.

| Displayed metric | Primitive or calculation within the period |
|---|---|
| Impressions | Sum of `summary_impressions` |
| Clicks | Sum of `summary_clicks` |
| Spend | Sum of `summary_spent` |
| Sales | Sum of `summary_sales` (revenue, not a count) |
| Total Units Sold | Sum of `summary_sold_units` |
| CTR | Clicks / impressions |
| Conversion | Sum of `summary_sales_count` / clicks |
| CPC | Spend / clicks |
| ROAS | Sales / spend |
| ACOS | Spend / sales |
| Category impression/click/spend/sales share | Category primitive / corresponding Total primitive |

Conversion uses sales count, **not sold units**. Daily ratios are neither summed nor
averaged. Percent values are fractions (0.025 means 2.5%), unlike the percentage-point
values displayed by the other ads report APIs. Decimal inputs retain their precision;
division uses `BigDecimal` with `DECIMAL128`, and rounding for presentation happens in
the browser.

An empty category within an existing period has zero primitives. If any contributing
snapshot is missing a primitive, that category's affected primitive and its dependent
calculations remain null, and the corresponding Total primitive also remains null.
A ratio with a missing input is null; with known inputs and a zero denominator it is
zero, following the existing ads report convention. The UI displays null as “—”,
distinct from a measured zero.

## Unsupported columns and errors

The current sources support 66 advertising columns. Stock, GMV 30, overall product
clicks/conversion, sales price, performance classification, average price, review count,
and rating remain null. TACOS and Total's advertising shares of overall product clicks
and sales also remain null: advertising summaries do not supply the required overall
product denominators. These columns retain their positions without fabricated values.

Keyword classification errors make Broad, Exact, and Total unavailable for the affected
period, along with all category shares that depend on Total. Valid Auto and Product
metrics remain visible. Ambiguous product attribution invalidates the affected category
and Total; unknown targeting invalidates all categories. Unaffected periods remain usable.

Partial reports return HTTP 200 with structured errors containing the vendor, campaign,
adset, report date, match types, and an explanation. The UI displays a summary and
expandable details using text content. Invalid request parameters return HTTP 400,
an unknown product/vendor pairing returns HTTP 404, and database failures return HTTP 500.

## Validation

Unit tests cover weighted formulas, numeric/null serialization, classification errors,
period boundaries, unavailable columns, and empty inputs. PostgreSQL integration tests
cover joins, active filters, product attribution, vendor isolation, and classification
without multiplying metrics. Browser-script tests cover real period requests, stale
responses, error details, selectors, and row visibility.

Run `mvn test` and `node --test src/test/js/product-performance.test.mjs`. PostgreSQL
tests require `ADS_TEST_DB_URL` pointing to a disposable database, optionally with
`ADS_TEST_DB_USER` and `ADS_TEST_DB_PASSWORD`; each test uses an isolated schema.
