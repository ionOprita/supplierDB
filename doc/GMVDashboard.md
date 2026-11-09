# GMV Dashboard

`/private/gmv-dashboard` shows one daily company chart and the selected month's GMV.
The month defaults to the current month in the Europe/Bucharest calendar. Past months
cover the whole month; the current month covers its first day through today. Future
days have gaps rather than zero observations.

All companies are selected initially. Selecting a company filters the loaded report;
changing month loads a new report. **Calculate GMV** reads current order data again and
keeps the selection. It does not update the stored monthly GMV table. The existing
`/private/gmv` page, product/date-range calculator, contributing-order details and CSV
remain available under **Orders, Returns & GMV**. `/private/overview` is now labelled
**Sales Dashboard**, and remains the login and header-logo destination.

## Calculation

Amounts are RON and include VAT at each product line's stored VAT rate. The
pages, chart axes, totals and detail export labels explicitly use **GMV (cu TVA)**.
Product Performance instead displays **Total Sales (fara TVA)**, calculated from net
order-line prices and net seller vouchers; this does not change the GMV figures here.
The existing GMV calculator rounds each VAT-inclusive unit price to two decimal places using
HALF_EVEN before multiplying quantity. Seller-funded voucher values including their
VAT are added once per product line; eMAG-funded vouchers and shipping are excluded.
Finalized/storno snapshots and remaining quantities follow the existing GMV rules.
Finalized RMA product quantities are not separately deducted again.

The report calculates the selected period once and then groups the calculator's
contributions by their source order date and selling-account company. This ensures
the sum of daily values equals the displayed total. Independently calculating each
day would not guarantee the same monthly result when finalized and storno prices differ.

For example, four finalized units at 100 RON followed in the same month by three
remaining units at 110 RON contribute 330 RON on the storno snapshot's date. When both
states enter the current reporting period, a new calculation can revise earlier daily
allocations. Matching states outside the requested period are retained for correct
storno calculations. Date membership uses order dates, rather than modification dates.

Company grouping uses the same trimmed company names, stable keys and vendor-name
fallbacks as Sales Dashboard. Attribution follows the order's selling account; the
legacy GMV table's vendor filter follows product-catalog ownership instead. The new
reader retains catalog product-code scope on every snapshot, keeps identities separate
by vendor, and does not introduce retracted-product or catalog-vendor filters.

## API and precision

`GET /app/gmvDashboard?month=YYYY-MM` is authenticated and uses `Cache-Control: no-store`.
An omitted month selects the current month. Its response contains `month`, `throughDate`,
`days`, `currency`, `total`, and `companies`. Each company contains `key`, `name`, `total`
and daily `gmv` values aligned with `days`. All amounts are exact decimal strings with
two fractional digits, including zero and negative values. The browser preserves them
for totals and tooltips and converts to floating point only for chart coordinates.

Malformed or future months return 400; unsupported or missing source currencies return
422 rather than combining unlike amounts; database failures return a generic 500.
Failed refreshes preserve the prior chart and its information date, with a visible
message identifying the displayed month. The information button supports hover, keyboard
focus, click/tap, Escape and outside dismissal.

## Validation

SQL and calculator tests cover monthly reconciliation, partial-month restatement,
outside-period matches, differing prices and VAT, vouchers, zero/negative amounts,
catalog scope and vendor identity, and unsupported currency. HTTP and browser-script
tests cover request validation, exact monetary serialization, loading/recalculation,
company selection, stale reports and accessible help. Real browser checks cover both
themes, responsive layouts, navigation, and the retained legacy calculator.

PostgreSQL integration tests use an isolated schema in the disposable database configured
through `ADS_TEST_DB_URL`, with optional `ADS_TEST_DB_USER` and `ADS_TEST_DB_PASSWORD`.
