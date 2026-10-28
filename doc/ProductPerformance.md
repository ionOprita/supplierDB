# Product performance calculations

`/app/productPerformance?vendorId=…&productCode=…&period=week` returns database-backed
product and advertising performance for one vendor/product. `period` accepts `week` (the default)
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

“Download CSV” exports the displayed rows and values, including their numeric formatting.
Check “Show everything” to include the full history for the selected product and period mode.
The CSV has one flat header row, with advertising labels qualified by their group (for
example, “Total - Clicks”). The filename identifies the vendor, product code, week/month
mode, and export date. Download is disabled while loading, after a failed request, or when
the report has no rows; partial reports with data remain downloadable.

Only periods containing eligible advertising snapshots are returned. Partial periods sum
the available daily reports, including the first/last stored period; missing dates are not
filled with invented observations. The category accumulators are combined to produce
Total, and the same calculator derives all rates after aggregation.

Stock and Sales Price come from `offers_offer.ext_stock` and `ext_sale_price`. Offers are
matched on vendor and the selected product's PNK (`doc_product_part_number_key`), with
surrounding whitespace removed. For each displayed period, the latest `offers_snapshot`
day for that vendor supplies the values. A day with exactly one matching offer supplies
both fields; a missing snapshot, no matching offer, or a null field displays as “—”.
A day with multiple matching offers displays “???” in both cells until a rule for
combining them is established. An empty latest snapshot does not reuse older values.
Offer snapshots do not create extra periods or affect advertising totals and errors.

GMV uses the existing product GMV calculator with the selected internal product code.
Each row includes the complete Monday–Sunday week or calendar month, with both dates
inclusive, even when advertising snapshots cover only part of that period. The product's
order history is loaded once per request, retaining finalized and storno snapshots outside
the requested periods so matching orders are calculated correctly. Calculations use
VAT-inclusive prices, storno deductions, and seller voucher adjustments, with period
membership determined by order dates rather than modification timestamps. Each period is
computed directly; monthly GMV is not assembled from displayed weeks.

The API's numeric `gmv` value retains two decimal places. The GMV column displays whole
numbers using the existing integer formatter, including zero and negative values. No
contributing orders produce zero. GMV-only periods do not create extra rows, and advertising
classification errors do not hide a row's GMV.

`# rev.` and `rating` use the stored reviews also exported to `Date Recenzii`, matched
on the selected product's trimmed PNK. Reviews are public product data: vendors sharing
a PNK receive the same review metrics after the vendor/product membership check.
The review history is read once per report request, without joining comments or other
review child records. Retained reviews that no longer appear in later fetches still count,
using their latest stored ratings, just as they remain in the sheet export.

A review belongs to the complete week or month containing its **publication date**,
preserving the source timestamp's local calendar date and matching the sheet export's
date semantics. Creation, modification, and first-import dates do not determine membership.
Reviews without a publication date are excluded; malformed nonempty publication timestamps
surface as data errors. `# rev.` counts each dated review once, including reviews with no
rating. `rating` is the arithmetic mean of the nonnull ratings in that period, displayed
with two decimal places. Monthly averages are calculated from individual reviews rather
than by averaging weekly averages.

After a successful review fetch, a period with no published reviews shows `0` reviews and
an unavailable rating (`—`). If all reviews in the period lack ratings, the count remains
available and the rating is `—`. Both cells are unavailable when the product has never
been successfully fetched. Review-only periods do not create extra rows, and advertising
classification errors do not hide review metrics. The existing CSV export includes the
displayed counts and ratings.

| Displayed metric | Primitive or calculation within the period |
|---|---|
| # rev. | Count of stored reviews published in the period |
| rating | Sum of nonnull ratings / count of rated reviews published in the period |
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

The current sources support 66 advertising columns, plus GMV, Stock, Sales Price, review
count, and rating. Overall product clicks/conversion, performance classification, and
average price remain null. TACOS and Total's advertising shares of overall product clicks
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
period boundaries, GMV period alignment and independence from advertising errors,
review publication dates, raw-review monthly averages, unavailable review data,
unavailable columns, and empty inputs. PostgreSQL integration tests
cover joins, active filters, product attribution, vendor isolation, and classification
without multiplying metrics, plus PNK-scoped review reads, successful empty fetches,
and retained reviews without child-record duplication. Browser-script tests cover real
period requests, stale responses, error details, selectors, GMV and review formatting,
review CSV values, selection refreshes, and row visibility.

Run `mvn test` and `node --test src/test/js/product-performance.test.mjs`. PostgreSQL
tests require `ADS_TEST_DB_URL` pointing to a disposable database, optionally with
`ADS_TEST_DB_USER` and `ADS_TEST_DB_PASSWORD`; each test uses an isolated schema.
