# Product performance calculations

`/app/productPerformance?vendorId=…&productCode=…&period=14days` returns database-backed
product and advertising performance for one vendor/product. `period` accepts `14days` (the default)
or `30days`; legacy `week` and `month` values are aliases for these new modes, not calendar reports.
The UI normalizes old bookmarks and displays the actual dates. The response contains column
definitions, rows and an `errors` list; `mock` is always false. Vendor/product membership is
checked before querying report sources.

Every source in this report uses the exact selected vendor UUID. Orders, net Total Sales,
and the compatibility gross GMV field are filtered by the selling account before selecting
order snapshots or pairing finalized/storno states. Main and FBE accounts are not implicitly
combined by company name. Other pages retain their existing global Orders/GMV calculations.

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
only keywords whose status is `active` contribute. Exactly one remaining type must exist and must be
`broad` or `exact`. No remaining type, both types, or any unsupported/null type is an
error, except for the all-zero case below. Classification produces one result per
adset snapshot, so joining multiple keywords cannot multiply the adset's values.

When no non-negative match type remains, classification is unnecessary if all six
adset primitives (impressions, clicks, spend, sales, sold units, and sales count) are
present and numerically zero. Such a snapshot produces no keyword-classification
error and preserves the interval's observed advertising data without assigning a category or
invalidating totals and shares. Any nonzero or missing primitive prevents this
exception. Conflicting or unsupported match types still produce errors, and other
validations still run.

All numeric advertising inputs come from `ads_adset`. No numeric keyword or targeted
product values are read, and `ads_search_phrase` is not used at all.

## Periods and formulas

Orders, sales and advertising metrics are calculated directly for each row's explicit inclusive
start/end dates. Clicks uses the closing-date rolling counter, and Perform. uses the
classification observed on that date.
`ProductPerformancePeriods` uses the Romanian business date (`Europe/Bucharest`):

- **14 days:** consecutive windows anchored to 2026-11-02: Nov 2–15, Nov 16–29,
  Nov 30–Dec 13. Floor division applies the same alignment to older history.
- **30 days:** one closed window per calendar month-end, from `end.minusDays(29)` to end.
  For example, Dec 2–31; in leap-year February, Jan 31–Feb 29. These windows can overlap
  or leave gaps and must not be summed as calendar-month totals.
- A window becomes closed only after its closing date has elapsed in Romania.
- A separate **provisional** rolling row ends on the latest live vendor snapshot date strictly
  before today, with exactly 14/30 dates. It is omitted if its end is at or before the latest
  closed boundary, if it predates the product's first activity, or if it duplicates a closed row.
  It can overlap history; it is not a partially elapsed fixed window.

Rows begin at the earliest known eligible Ads, matching offer (including zero stock), or
status 4/5 order activity in the selected account. Orders from another account cannot extend
the report history. Rows exist without Ads and without visits captures, preserving
historical Orders/GMV. No activity means no rows. Rows are sorted oldest to newest.
“Closed” refers to elapsed dates: later corrections/imports can still change the underlying data.

Each row exposes `period: {startDate, endDate, windowDays, provisional}`, `values`,
`visitsSnapshotDate`, `visitsCapturedAt`, `visitsStatus` (`available`, `missing`, `ambiguous`),
and `visitsReason`. Performance observation metadata is independent: `performanceSnapshotDate`,
`performanceStatus` (`available`, `missing`, `ambiguous`), `performanceReason`, and the raw
`performanceType`. Dates/timestamps are ISO strings. The first column key is `period`;
the UI renders explicit bounds from the row metadata, not a reconstructed week/month label.
The UI initially shows the latest 20 rows; “Show everything” reveals all returned periods.
Switching modes requests new aggregates rather than relabelling rows.

“Download CSV” exports the displayed rows and values, including their numeric formatting.
Check “Show everything” to include the full history for the selected product and period mode.
The CSV has one flat header row, with advertising labels qualified by their group (for
example, “Total - Clicks”). The filename identifies the vendor, product code, report
mode (`14days`/`30days`), and export date. It also includes period start/end, window length,
provisional state, visits snapshot date/time, status and reason, plus performance snapshot date,
status, reason and type. Download is disabled while loading, after a failed request, or when
the report has no rows; partial reports with data remain downloadable.

Advertising metrics sum the available daily reports within each interval; missing dates are not
filled with invented observations. An interval with no eligible advertising snapshots has null
Ads values, not invented zero activity. Within intervals containing Ads data, the category
accumulators are combined to produce Total, and rates are derived after aggregation.

Stock and **Sales Price (cu TVA)** come from daily offers matched on vendor and the
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

In **both 14-day and 30-day modes**, Sales Price is the arithmetic mean of available daily
gross offer prices across the row's interval, rounded to two decimals with HALF_EVEN.
It is not an average of prices paid in orders. Each observed day has one weight,
independently of advertising activity and order quantities. Missing prices or VAT rates
are omitted rather than counted as zero; valid zero prices are included. No available
prices display “—”. If any eligible day in the interval has multiple matching offers, the
price displays “???” instead of selecting or averaging an ambiguous value.
Stock still uses the latest eligible day even when an earlier day makes the mean price
ambiguous. Prices are calculated from daily observations, never from other rows' averages.
There is no separate Average price column. Offer-only periods can create rows without
supplying advertising totals.

Orders appears immediately after Stock, before Total Sales, in the table and CSV. It is the
number of **ordered product units**, using the same selection as Order Table, rather
than the number of distinct orders or advertising-attributed sales. For example, two
orders containing three and two units contribute five. Returning two units does not
reduce that Orders value; returned units are reported separately on the dashboard.

For each explicit 14-day or 30-day interval, order snapshots are first
filtered by the exact selected account and their order date to that period. Within each order ID and product PNK,
status 4 contributes `product_in_order.quantity`; if no status 4 snapshot exists in
that period, status 5 contributes `initial_qty`. Other statuses do not contribute.
The latest surrogate ID breaks ties within one status. This preserves Order Table's
quantity rules while restricting the candidate snapshots to the selected selling account.
Another account's matching order ID cannot replace or contribute a snapshot.
No catalog or retracted-product filter is added to the quantity query.
Zero and signed source quantities are retained. Interval totals are calculated directly,
not by summing other report rows, because a snapshot can be selected differently for each
period. A displayed period with no contributing order quantities has zero Orders.
Orders-only periods can create rows, and advertising errors do not hide Orders.

The overview dashboard uses the same ordered-unit selection once for the selected
month (through today in the current month), then groups the selected snapshots by
order date and selling-account company. Returns sums product quantities in finalized
return requests by request date, counting each returned item once. Its existing
company-resolution rules remain in effect; unresolved quantities are shown in the
unassigned-units notice. Both charts therefore show units, rather than transaction or
return-request counts. Their company attribution follows the selling account, while
Order Table's visible product labels follow the product catalog.

**Total Sales (fara TVA)** replaces the displayed GMV column. It uses the existing product
GMV allocation with the selected internal product code, but supplies net inputs:
`product_in_order.sale_price` without adding VAT, and the sum of seller-funded
`voucher_split.value` without `vat_value`. Each line retains its own source amount;
we never divide aggregate GMV by a single VAT rate. Net unit prices retain their source
precision until the period total is rounded to two decimals with HALF_EVEN.

Each row uses its explicit 14-day or 30-day interval, with both dates
inclusive, even when advertising snapshots cover only part of that period. The product's
order history for the selected account is loaded once per amount basis, retaining finalized and storno snapshots outside
the requested periods so matching orders are calculated correctly. Both bases preserve
the same storno deductions and seller voucher allocation, with period
membership determined by order dates rather than modification timestamps. Each period is
computed directly; totals are not assembled from other displayed rows. Net Total Sales,
gross GMV and Ads all refer to the selected account. The existing global product-period
methods remain available to other consumers with their original scope.

The API adds numeric `totalSales` and retains the legacy numeric `gmv` with its original
VAT-inclusive meaning for compatibility; both now use the selected account. Only `totalSales` is included in the displayed
columns and CSV. Both values retain two decimal places. Total Sales displays whole
numbers using the existing integer formatter, including zero and negative values. No
contributing orders produce zero. Qualifying order history can create rows without Ads, and advertising
classification errors do not hide a row's Total Sales or legacy GMV.

### VAT labels and advertising source limits

All five Ads groups label **Spend (fara TVA)** and **CPC (fara TVA)** using the agreed
convention that Spend consumes the VAT-exclusive Ads credit. The seller's credit display
confirms the credit basis; the Ads API itself does not provide an explicit VAT flag.
Raw Spend is not converted or rewritten; CPC remains Spend divided by Ads clicks.

**Sales (fara TVA)** retains eMAG's original attributed sales amount. On 2026-11-06,
the user confirmed directly in the eMAG platform that Ads Sales excludes VAT. No
additional VAT conversion is applied. ACOS (Spend / Sales), ROAS (Sales / Spend), and
category Sales shares retain their existing formulas and use VAT-exclusive Sales,
with Spend using the net-credit convention above. API keys and numeric values are unchanged.
Counts and percentage labels have no VAT suffix.

Column metadata has an optional `description`, displayed as header/cell hover text and
accessible labels. CSV uses the same column labels, including **Sales (fara TVA)**, and includes
the eight period/visits metadata fields plus four performance observation fields. Dedicated GMV pages and their detail
exports use **GMV (cu TVA)** and retain their existing calculations.

| Displayed metric | Primitive or calculation within the period |
|---|---|
| Impressions | Sum of `summary_impressions` |
| Clicks | Sum of `summary_clicks` |
| Spend | Sum of `summary_spent` |
| Sales | Sum of `summary_sales` (revenue, not a count) |
| Total Units Sold | Sum of `summary_sold_units` |
| CTR | Clicks / impressions |
| Ads Conversion | Sum of `summary_sales_count` / Ads clicks |
| CPC | Spend / clicks |
| ROAS | Sales / spend |
| ACOS | Spend / sales |
| Category impression/click/spend/sales share | Category primitive / corresponding Total primitive |
| TACOS (Total, Auto, Broad, Exact, Product) | Group Spend / Product metrics Total Sales (both excluding VAT) |
| Total: % Clicks Ads of Total | Total Ads Clicks / Product metrics Clicks |
| Total: % Sales Ads of Total | Total Ads Sales / Product metrics Total Sales (both excluding VAT) |

TACOS uses one shared denominator: the selected account/product's total sales from organic
and advertising activity, including the existing net voucher/storno adjustments. Total's
Sales share is a revenue share, not a units or order-count share. The category shares of
Total Ads retain their original Ads-only denominators.

Ads conversion uses sales count, **not sold units**. Daily ratios are neither summed nor
averaged. Percent values are fractions (0.025 means 2.5%), unlike the percentage-point
values displayed by the other ads report APIs. Decimal inputs retain their precision;
division uses `BigDecimal` with `DECIMAL128`, and rounding for presentation happens in
the browser.

An empty category within an existing period has zero primitives. If any contributing
snapshot is missing a primitive, that category's affected primitive and its dependent
calculations remain null, and the corresponding Total primitive also remains null.
For TACOS and Total's product Clicks/Sales shares, missing inputs or a denominator at or
below zero produce null (`—`). A known zero numerator and positive denominator produce 0%.
No Ads observations remain unknown; an empty category within an observed, valid Ads period
can produce zero TACOS. Missing Spend, Sales or visits affects only dependent ratios.
Valid category TACOS remains available when classification errors invalidate another category
or Total. Existing Ads-only ratios retain their prior zero-denominator convention (zero
when both inputs are known); missing inputs always remain null.

Ratios are never capped at 100%, and signed source numerators are preserved. Ads attribution
and the net-sales voucher/storno rules can differ. Product Clicks uses a rolling eMAG counter
whose exact cutoff is unknown, so its share is approximate even when nominal row dates match.
The column descriptions explain the account scope, formulas and these source limitations.

## Rolling product visits

**Product metrics → Clicks** shows the raw Last14 or Last30 counter for the selected
vendor/product and report mode. There is no separate top panel or independent visits request.
The old `/app/productVisits` endpoint remains available for compatibility.

The source is the authenticated eMAG offers dashboard's `productPerformanceVisitsLast14`
and `productPerformanceVisits` fields. See [eMAG dashboard API](EmagDashboardAPI.md).
Subtracting consecutive rolling totals cannot give daily visits: both new visits and
visits leaving the window change the total. We use one raw counter, not sums or differences
between captures. **The counter's exact source cutoff is unknown**; the table tooltip describes
the displayed visits window as approximate. No claim of exact calendar alignment is made.

For historical rows, the exact closing date's last successful live vendor snapshot supplies
Clicks. All required dates are read together. Missing/import-only captures remain unavailable;
we never borrow another day's counter under the original interval. For the provisional row,
the interval is explicitly shifted to the selected completed snapshot date. PNKs are
trimmed before matching. Exactly one matching offer with `ext_stock <> 0` is preferred;
if none have nonzero stock, exactly one matching offer of any stock is accepted.
Multiple candidates display an ambiguity message instead of being summed or selected
arbitrarily. Missing values display “—”; a measured zero displays “0”. Missing values
in the latest snapshot do not silently fall back to older observations.

The Clicks tooltip and CSV include snapshot date, capture time and availability/reason.
The timestamp is when we observed the counters, not an eMAG-provided cutoff for their windows.
Historical captures are not considered stale merely because their dates precede today.
Snapshots from before visits collection have no visits timestamp and do not qualify.

Hourly collection keeps the latest successful complete snapshot per vendor per day;
earlier days remain stored. A failed fetch or database transaction preserves the last
successful snapshot. The report reads stored data; administrators can request a fresh collection
using Run in System → Tasks. Collection failures and
missed days are documented in [Task history](TaskHistory.md).

## Product conversion and performance classification

**Product metrics → Conv.** is calculated as **Orders / Clicks**, displayed as a percentage.
Orders means the ordered product units defined above, not distinct orders and not Ads sales
counts. Both inputs belong to the selected account/product and the same displayed 14-day or
30-day row. For example, 25 units divided by 1,000 Clicks is stored in the response as `0.025`
and displayed as `2.50%`. The calculation uses `BigDecimal` with `DECIMAL128`; percentages
are formatted only for display/export, not rounded or multiplied by 100 in the API value.

The numerator preserves the existing Order Table quantity rules, including signed quantities
and no deduction for returns. Conversion remains available without any Ads observations or
when advertising classification errors affect Ads columns. A known zero Orders value divided
by positive Clicks produces 0%; missing Orders, missing/ambiguous Clicks, or Clicks at or below
zero produce null (`—`). Values above 100% are not capped. The formula uses the existing raw
rolling Clicks counter, so its calendar alignment shares that counter's unverified eMAG cutoff.
It does not claim to reproduce eMAG's native conversion or change any Ads conversion formula.

**Product metrics → Perform.** uses the already-collected `offerStock.hotnessName` and
`hotnessType`, stored in `offers_stock`. These are eMAG classifications such as Super Hot,
Hot, Regular, Cold and Super Cold; no local thresholds are calculated. Classification is
based on eMAG's 30-day performance window, including when displayed on a 14-day report row.

The reader selects the exact closing-date snapshot for the selected vendor and trimmed PNK.
It uses the same offer selection rule as Clicks: prefer one matching offer with `ext_stock <> 0`;
otherwise accept one matching offer regardless of stock, including zero or unknown stock.
Multiple effective candidates are ambiguous even if their labels agree. The selected offer
is joined to its own `offers_stock` row on vendor, snapshot date and offer ID. Labels from
different offers are never combined, and classifications from adjacent dates are never borrowed
or averaged. A unique out-of-stock product can therefore retain its historical classification.

Hotness is read independently of visits provenance: an older observation can be valid even
without `visits_fetched_at` or any Clicks value. Missing classifications display `—`; ambiguous
offers display `???`. A nonblank `hotnessName` is displayed exactly as supplied. When only
the type is present, the known types `super_hot`, `hot`, `regular`, `cold`, and `super_cold`
display Super Hot, Hot, Regular, Cold, and Super Cold respectively; an unknown type is
displayed unchanged. The tooltip and accessible label explain the 30-day classification and
actual observation date. The response and CSV preserve the separate performance date, status,
reason and raw type. Unknown labels remain text rather than being rejected by a fixed enum.
Page loads use stored observations; no schema migration or new conversion collection is required.

## Unsupported columns and errors

The current sources support all 73 advertising columns, plus Orders, Total Sales, Stock, Sales Price
and raw rolling Clicks, calculated product conversion and observed performance classification
where the required inputs exist. Review count and rating remain null. These unsupported overview columns retain
their positions without fabricated values.

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
14/30-day boundaries and previews, daily mean gross prices, Orders/net sales/gross GMV alignment and independence from advertising errors,
product conversion without Ads, missing/zero Clicks, zero units and unclamped percentages,
unavailable columns, and empty inputs. PostgreSQL integration tests
cover joins, active filters, product attribution, vendor isolation, classification,
Order Table quantity-rule parity, exact selected-account isolation, VAT conversion,
same-order-ID cross-account separation, exact-day visits/ambiguity/live provenance and snapshot isolation,
exact-day hotness with zero/unknown stock and legacy observations without visits provenance,
and dashboard quantities without join multiplication. API orchestration tests check identical bounds for
all sources and rows without Ads. Leap-year, overlapping-window and Romanian midnight cases are covered.
Browser-script tests cover real period requests, stale responses, error details,
selectors, Orders/Total Sales formatting and refreshes, VAT descriptions, conversion percentages,
performance observation tooltips and safe text rendering, CSV alignment, and row visibility.

Run `mvn test` and `node --test src/test/js/product-performance.test.mjs`. PostgreSQL
tests require `ADS_TEST_DB_URL` pointing to a disposable database, optionally with
`ADS_TEST_DB_USER` and `ADS_TEST_DB_PASSWORD`; each test uses an isolated schema.
