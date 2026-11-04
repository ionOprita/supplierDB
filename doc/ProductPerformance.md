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

Stock and **Sales Price (incl. VAT)** come from daily offers matched on vendor and the
selected product's PNK (`doc_product_part_number_key`), with surrounding whitespace
removed. The existing `ext_stock <> 0` eligibility filter is retained: zero-stock offers
and empty snapshot days do not supply observations. The latest eligible day in the period
supplies Stock from `offers_offer.ext_stock`. Multiple matching offers on that day display
“???”, and a missing stock field displays as “—”.

The stored `ext_sale_price` is converted to a gross price using the same offer's
`offers_stock.vat_id`, joined on vendor, snapshot date and offer ID. The eMAG Romania
rate mapping, verified against `vat/read` on 2026-11-04, is ID 5 → 0%, ID 9 → 21%, and
ID 10 → 11%. An unknown or missing ID leaves the price unavailable; no default rate is
assumed. Conversion uses the full stored decimal price and `BigDecimal`, rounding each
daily gross price to two decimals with HALF_EVEN. For example, 165.28 at 21% becomes
199.99. Stored offers are not rewritten and page loads do not call the external API.

In week mode, Sales Price uses the latest eligible day's gross price. In month mode,
it is the arithmetic mean of available daily gross prices across the complete calendar
month, rounded to two decimals with HALF_EVEN. Each observed day has one weight,
independently of advertising activity and order quantities. Missing prices or VAT rates
are omitted rather than counted as zero; valid zero prices are included. No available
prices display “—”. If any eligible day in the month has multiple matching offers, the
monthly price displays “???” instead of selecting or averaging an ambiguous value.
Stock still uses the latest eligible day even when an earlier day makes monthly price
ambiguous. Monthly prices are calculated from daily observations, never from weekly
averages. The separate Average price column is removed from the API metadata, table
and CSV. Offer-only periods do not create rows or affect advertising totals and errors.

Orders appears immediately after Stock, before GMV, in the table and CSV. It is the
number of **ordered product units**, using the same selection as Order Table, rather
than the number of distinct orders or advertising-attributed sales. For example, two
orders containing three and two units contribute five. Returning two units does not
reduce that Orders value; returned units are reported separately on the dashboard.

For each complete Monday–Sunday week or calendar month, order snapshots are first
filtered by their order date to that period. Within each order ID and product PNK,
status 4 contributes `product_in_order.quantity`; if no status 4 snapshot exists in
that period, status 5 contributes `initial_qty`. Other statuses do not contribute.
The latest surrogate ID breaks ties within one status. This preserves Order Table's
existing selection key, including its omission of vendor ID, and includes all selling
accounts for the selected product PNK. No catalog or retracted-product filter is added.
Zero and signed source quantities are retained. Monthly totals are calculated directly,
not by summing weekly totals, because a snapshot can be selected differently for each
period. A displayed period with no contributing order quantities has zero Orders.
Orders-only periods do not create rows, and advertising errors do not hide Orders.

The overview dashboard uses the same ordered-unit selection once for the selected
month (through today in the current month), then groups the selected snapshots by
order date and selling-account company. Returns sums product quantities in finalized
return requests by request date, counting each returned item once. Its existing
company-resolution rules remain in effect; unresolved quantities are shown in the
unassigned-units notice. Both charts therefore show units, rather than transaction or
return-request counts. Their company attribution follows the selling account, while
Order Table's visible product labels follow the product catalog.

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

The current sources support 66 advertising columns, plus Orders, GMV, Stock, and Sales Price.
Overall product clicks/conversion, performance classification, review count,
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
period boundaries, daily/monthly gross-price aggregation, Orders/GMV period alignment and independence from advertising errors,
unavailable columns, and empty inputs. PostgreSQL integration tests
cover joins, active filters, product attribution, vendor isolation, classification,
Order Table quantity parity, VAT conversion and snapshot isolation, and dashboard quantities without join multiplication.
Browser-script tests cover real period requests, stale responses, error details,
selectors, Orders/GMV formatting and refreshes, CSV alignment, and row visibility.

Run `mvn test` and `node --test src/test/js/product-performance.test.mjs`. PostgreSQL
tests require `ADS_TEST_DB_URL` pointing to a disposable database, optionally with
`ADS_TEST_DB_USER` and `ADS_TEST_DB_PASSWORD`; each test uses an isolated schema.
