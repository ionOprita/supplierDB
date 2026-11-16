# Profit calculator — task notes and recovery handoff

Last reviewed: **2026-11-16**. Repository branch: `profit-calculator`.
Base commit for the latest batch: `9c2e070` (`Reorder columns to show active products first.`).
The working tree was clean before this batch. These notes include the subsequent
FBE fee calculations described below, not a confirmed
production deployment.

Start here after losing the chat, then read [ProfitCalculator.md](ProfitCalculator.md)
for detailed formulas, mappings, API behavior, and the maintained **Open questions**.

## Current objective and working method

Incrementally reproduce the spreadsheet profit calculator inside the existing
server, using data stored in PostgreSQL. Work in small, understandable batches
so the original sheet author can verify the business interpretation. Discovering
missing data is part of the objective: later work may extend the database,
eMAG API collection, or eMAG dashboard extraction.

The current work is on **By Category**. The other calculator page is a scaffold
for later work. The author answered the original three questions: gross units
without storno records, selling accounts only in the selected group, and
per-product commission defaults. These answers are now implemented. Remaining
questions cover settings
ownership/timing, authoritative import/operating-cost inputs, and FBE tier
classification. After initially deferring FBE calculations, the user explicitly
chose **exact sheet behavior** for rows 57–60 pending question 7, including the
known questionable tier assignments. That question is still open.

## Requirements and settled decisions

- **Never change the reference spreadsheets.** All spreadsheet access is
  strictly read-only. Runtime calculator requests must use the database and
  must not fetch spreadsheet values.
- There is one calculator workbook per `vendor.vendor_group`. Known groups are
  J, K, S, and Z; selector options come from the database, with S the default
  when available. We study the S workbook because the other workbooks have the
  same intended structure.
- The approved category selector uses **`product.category`**. Do not replace
  category names with abbreviated spreadsheet tab names such as `2. Trim.`.
- Reproduce the **Actual** area of `2. Trim.` only, with one product per column,
  Romanian labels and sections, and stable source-row numbers retained in the
  local layout definitions. Unimplemented metric/summary values stay blank.
- Keep product names, PNKs, and display-only retracted checkboxes visible.
  Columns show active products before retracted products, retaining the existing
  natural name/product-code ordering within each group on loads and previews.
  Missing PNK or a retracted product blanks its reference/calculated outputs,
  but does not remove its column or prevent editing/storing its overrides.
- Distinguish unknown values from genuine zero/false values. Null remains
  blank; zero price is `0.00`; false VAT-payer status is an unchecked checkbox;
  unknown VAT status has no checkbox.
- Ignore **individual calculator cells manually replacing a normal formula**:
  apply the normal formula consistently to all products instead. This also
  applies to later rows such as 54.
- **Dedicated manual inputs in rows 40–45 are intentional overrides.** The user
  explicitly requested implementing them. The preceding rule does not remove
  these inputs or prohibit product master data originally entered manually.
- Overrides are shared, saved per product in the database, and do not change
  product master fields. They are not private browser scenarios or autosaved
  keystrokes: edits preview, then the user reviews and saves or reverts.
- Applying the USD conversion formulas from the sheet's separate Simulation
  area to By Category's Actual area is an **explicitly approved extension**.
  It is not how the original Actual columns used rows 42–44.
- FBE processing tariffs are **one shared current grid across all groups**.
  The user explicitly requested migration SQL with hardcoded source values,
  rather than a one-off transfer program. This is an authorized seed snapshot;
  subsequent runtime reads/writes use only PostgreSQL. Users edit the existing
  12 tiers; adding/removing tiers and historical schedules are deferred.
- Rows 57–60 temporarily reproduce the existing FBE dimension classifier and
  rounded-up excess-kilogram charges from the current database grid. Do not
  silently correct its MAX-ID selection, enforce girth/weight-range limits,
  rotate dimensions, or require the chosen tier to fit. Replace the isolated
  classifier only after the author clarifies question 7 or the user changes
  the agreed behavior. Existing blank-on-missing rules still apply.
- Row 35 counts previous-calendar-month **gross finalized quantities** for the
  selected product PNKs and selling-account group. Keep finalized snapshots
  even when a later storno exists; ignore status-5 records rather than deduct
  them or reconstruct their `initial_qty`. Both FBE/non-FBE accounts in the
  selected group contribute. Commission defaults are per product, with row 41
  taking precedence when supplied.

### Important category and identity distinctions

The original calculator's category imports external column **CS**, stored as
`product.subcategory` (e.g. `Trimmers`). Our approved UI uses external column
**CN**, stored as `product.category` (e.g. `Aparate de tuns`). These are related
taxonomy levels, not interchangeable keys for category-wide assumptions.

`product.product_code` is the stable global primary key; nonblank PNKs are
globally unique. Each product links to one vendor, so joining its vendor group
does not require deduplicating product columns across accounts.

For the selling-company output, use **`vendor.company_name`**, not `vendor_name`
or the account label. The product importer resolves source company names to
FBE vendors using `Vendor.companyName`; account display names may have an FBE
suffix and represent something different.

## Reference sources and tracing discoveries

These are investigation references, never runtime data sources:

| Workbook | URL / relevant tabs |
| --- | --- |
| S. Calculator Profit | [Workbook](https://docs.google.com/spreadsheets/d/16VK84fOII89oJBZur5tfH-HhOvLGzTrb-7UMVlzm5L0/edit); `2. Trim.` gid **1896630135**, `Calc. Expunere & Profit` gid **1545378824** |
| Date produse | [Workbook](https://docs.google.com/spreadsheets/d/1q8lltfW6bMc3nEzoes7lrAn2Kp_W5UdH5VECtLaIejw/edit); `S. Date Prod.` gid **159482373**, `Date Firme` gid **1807172958**, `Comisioane` gid **813157553** |
| Cent. - Date comenzi general | [Workbook](https://docs.google.com/spreadsheets/d/1bX_k1aaMEKwMCxxF0mrMjTA4I7RnA2WJb8DBGWwisUE/edit); `T. Sales/M. (H.)`, `(GLB.) T. Sales/M.` |
| S. Date comenzi - 2026 | [Workbook](https://docs.google.com/spreadsheets/d/1RQTp2TYFYLx21h0dtepB73hBoTSkZN5iFF_ip7GGCfk/edit); `T. Sales/M.`, raw `Date` |
| Cent. - Ret. Sto. Ref. Inl. | [Workbook](https://docs.google.com/spreadsheets/d/1bOPbTkbvQL3g3Q0hzyNhaTAt--BNAFSZ5bV2PtAKh80/edit); `(GLB) Cent. Prod. (%)` and monthly rate helpers |
| S. eMAG ads | [Workbook](https://docs.google.com/spreadsheets/d/1lTej6Or4ozRAZwpwA8h7PJLQSZQjhR6-lxGg8oa_8do/edit); `eMAG ads`, `Introd. Date` |
| Calculator cost FBE | [Workbook](https://docs.google.com/spreadsheets/d/1VkeF6LQMGVXZRBuVee2p2Oby0JYbmSwc8gBbdLrlcXk/edit); tariff tab `Grila costuri procesare FBE` gid **1892248028**, `Calc. cost FBE (Total)` gid **1391468769** |
| EEE/BAT/packaging source | [Workbook](https://docs.google.com/spreadsheets/d/13xZcGdWG4WKP6TwuXwjVjKeuO99-9QHzuMQgg0S9Po4/edit); `Cost EEE & BAT & Ambalaje/prod.` |

The FBE workbook title and tariff tab above were confirmed from metadata. Its
processing, return, withdrawal, and disposal tariff grid is now stored in the
database, alongside existing product dimensions/weight. The observed classifier
uses maximum matching dimension-row IDs, selecting the last of four identically
sized extra-large tiers regardless of weight; girth and weight range are unused.
This is author question 7, not a rule silently corrected in code. The user now
authorized using that exact behavior to populate By Category rows 57–60 while
the question remains pending. The ambiguity also affects medium dimensions
and can choose a tier whose height does not fit; see regression examples below.
The FBE storage and EEE/BAT/packaging pricing chains have not been fully traced.
Workbook contents can evolve; re-read metadata and
current formulas before implementing another batch. Use exact tab names and
bounded ranges. Inspect array/import anchors (often A1), rather than treating
an imported cell with no individual formula as an unexplained constant. Sample
multiple product columns: some formula rows contain manually replaced cells.

### Dependencies already established

- `Calc. Expunere & Profit!B6` directly combines category-tab rows 7–32 and
  selects product name, PNK, retracted flag, margin, and ROI: source rows
  **7, 8, 9, 31, 32**. It excludes withdrawn products. No direct reverse
  dependency from `2. Trim.` was found. This was not a complete audit of all
  indirect workbook dependencies.
- Local `Date Prod.!A1` is a `QUERY(IMPORTRANGE(...))`. Its D/F/K/L/M columns
  project external `S. Date Prod.` **BH/BN/BK/BL/BM** respectively. BH is PNK;
  BN is reference price; BK is company. BL/BM resolve tax regime/VAT status
  through the company's `Date Firme` columns P/Q. Existing product imports
  already store these resolved values in the database.
- Row 71's `[%]` label is misleading: its value is **`Profit`/`Venit` regime
  text**, not a numeric tax rate.
- Effective price is distinct from reference price. Row 75 normally chooses
  override row 40 before reference row 48. This is meaningful: the investigated
  S.55 product had reference price 299.99 but an override of 259.99. Those were
  illustrative sheet observations, not application defaults to hardcode.
- The commission fallback in category cell C3 selects the first matching
  category product. The author clarified this should instead be each product's
  own commission, now implemented from `product.emag_commission` (a fraction).
- Row 35 traces through the central history workbook into the four groups'
  2026 order workbooks. It selects the previous calendar month, matches PNK,
  and sums raw `Date` quantities: A = order date/time, C = status, E = PNK,
  G = quantity. It excludes `Stornata` and renders a zero result as blank.
- The existing order-sheet exporter includes finalized/status-4 quantities
  and negative status-5 storno quantities. The monthly formula excludes the
  latter; it does not explicitly subtract returns or partial-storno quantities
  from finalized lines. Existing `OrderQuantityTable` behavior differs because
  it can fall back to status-5 `initial_qty`; do not reuse it unquestioningly.
- Order-sheet exports are partitioned by product-name prefix, not selling
  account. They may also be stale: changed orders are not refreshed, and export
  deduplication uses order ID plus product name while ignoring vendor. A live
  database calculation can differ from the sheet even with the same intended
  rule. The workbooks use the Europe/Bucharest timezone.
- Author answers 1–3 supersede the unresolved interpretations above: row 35
  sums status-4 `product_in_order.quantity` in the prior Bucharest calendar
  month, filtering the **selling** account's group. Do not deduplicate by
  order ID/PNK alone: account collisions and multiple same-PNK line IDs are
  legitimate contributions. A 4+5 pair contributes the finalized quantity
  once; a status-5-only record contributes nothing. Any qualifying null
  quantity makes that product's total unknown; no matching sales is known zero.
- Advertising cost row 62 also depends on previous-month units. Its imported
  helper divides the latest report month's PNK-matched campaign spending by
  previous-calendar-month sales. Do not substitute advertising-attributed units
  or assume its numerator and denominator necessarily cover the same month.
- Some return/refusal/replacement helper values are fixed snapshots; formulas
  elsewhere use monthly averages and adjustment factors. These rates have not
  been approved or implemented as simple raw returns divided by sales.

## Completed implementation

### Pages and values

- Menu group **PROFIT CALCULATOR**, matching Sales Performance styling.
- `/private/profit-exposure`: vendor-group selector only; no exposure report yet.
- `/private/profit-by-category`: vendor group/category selectors, Actual table,
  sticky product headers/labels, and shared override editing.
- `/private/profit-fbe-tariffs`: shared current FBE tariff editor, immediately
  after By Category; reviewed atomic saves, conflict handling and Revert.

| Row(s) | Current behavior |
| --- | --- |
| 2 | Selected database-backed `product.category` as literal summary text |
| 35 | Previous-calendar-month gross finalized units from selling accounts in the selected group; integer display, zero blank |
| 7–9 | Product name, PNK, display-only retracted checkbox |
| 48 | `product.emag_sale_price_ron`, reference price including VAT |
| 70 | Linked `vendor.company_name` |
| 71 | `product.income_profit_tax` text |
| 72 | Nullable `product.vat_payer` display-only checkbox |
| 75, 26 | Override 40 when present, otherwise reference 48 |
| 76 | Override 41 when supplied, otherwise the product's `emag_commission` |
| 86 | Effective price × effective commission when both are known |
| 49, 77 | USD purchase override 42 × exchange-rate override 44 |
| 50, 78 | USD transport override 43 × exchange-rate override 44 |
| 57–60 | FBE processing, return, withdrawal and disposal: source-classified tier base fee plus rounded-up excess kg × surcharge |
| 63, 64 | Literal zero from the Google/social-media Ads formulas; not measured spending |
| 88 | Zero only for an explicit `Minulescu` fulfillment override |
| 100 | Zero only for the known `Profit` tax regime |
| 101 | Zero only when VAT-payer status is explicitly false |
| 105, 106 | Zero only for the known `Venit` tax regime |

The seven cost outputs above implement only independently determined branches.
Tax-regime comparisons trim whitespace and ignore case, retaining the original
display text. Null/unknown regimes, null VAT status, blank fulfillment, and
branches needing unavailable cost/tax inputs remain blank. The existing
PNK/nonretracted guard applies even to literal-zero results. Cost and profit
totals remain blank. These zero rules do not establish missing rates or tariffs.

The category summary updates immediately with the current valid group/category
selection. It stays visible during product loading, empty results, failed
product requests, and override previews. It clears during selector-option
reloads or when no valid category is selected. This selection-level value is
independent of product eligibility. Financial summary rows 3–6 remain blank.

### Override behavior

Rows 40–45 are selling price RON, commission percentage, purchase USD per unit,
transport USD per unit, USD/RON rate, and fulfillment source. Commission is
entered as **14** for 14%, stored as **0.14**. Monetary inputs are nonnegative,
commission is 0–100%, and a supplied exchange rate must be positive. Fulfillment
choices are blank, `eMAG`, and `Minulescu`.

The migration-50 `profit_calculator_override` table stores six nullable fields
and a revision per product. Null means no override; zero remains a valid value.
All-null records retain their revision. Saves are atomic and reject stale
revisions with HTTP 409. The API preserves decimal inputs as plain strings;
the server evaluates with `BigDecimal`, rounding only for display. Reference
values are never replaced by overrides.

Drafts survive category/group changes, previews update result cells without
rebuilding focused inputs, and outdated responses are ignored. Review precedes
Save; Revert reloads saved values. Save failures/conflicts retain drafts. A save
acknowledgement and subsequent reload are separate: never repeat a committed
save because readback failed. Row 45 now also determines the zero-storage branch
in row 88; other fulfillment charges remain deferred. Changing fulfillment
clears the previous storage result while preview is pending or fails. Price,
purchase, and freight overrides have the formulas above.

The USD calculations require both operands. No purchase/freight baseline or
exchange-rate default is available, so incomplete inputs leave outputs blank.
Rows 51/52/79/80 (import VAT/customs) remain blank, as do unimplemented rates,
fees, profit totals, monthly financial outputs 36–37, and summary rows 3–6.

### Code entry points

- `Server` registers `GET /app/profitCalculatorOptions`,
  `GET /app/profitCalculatorCategory`, `POST /app/profitCalculatorPreview`, and
  `POST /app/profitCalculatorOverrides`; existing application-user auth applies.
- `ProfitCalculatorCategory` owns the shared calculation evaluator;
  `ProfitCalculatorOverrides` validates requests; `ProfitCalculatorTable`
  handles reads, nullable overrides, revisions, and atomic writes.
- `EmagMirrorDBVersion50` and `SetupDB` add override storage. The history
  importer's migration guard accepts version 49 and newer, retaining its
  version-48 upgrade path.
- Frontend modules: `profit-calculator.js`, `profit-calculator-table.js`,
  `profit-calculator-overrides.js`, and `profit-calculator-layout.js`.

### Monthly units and commission defaults

The author answers to questions 1–3 are recorded under **Resolved author
questions (1–3)** in `ProfitCalculator.md`; pending questions retain IDs 4–7.
`CategoryProduct` now carries `emagCommission` and `previousMonthGrossUnits`
internally. `Calculated.previousMonthGrossUnits` is a nullable JSON integer;
known zero stays zero internally but displays blank. Unknown quantities and
ineligible products remain blank. Commission uses override 41 when nonnull,
otherwise the product's fractional database rate. Clearing an override restores
that default; row 86 recalculates without intermediate rounding. Category
commission summary row 3 remains blank because rates can differ by product.

The API calculates the previous month once per GET/preview request, using
`Europe/Bucharest` through an injectable clock. It passes a `YearMonth` into the
mirror read and a single bounded category/orders query in the same transaction.
That query uses stored local order timestamps as-is, not UTC-converted dates.
There is no schema migration, new endpoint, or change to order ingestion.
Previews preserve monthly units and product commission defaults when applying
draft overrides. Financial monthly outputs 36–37 still need missing cost/profit
inputs and remain blank.

### Shared FBE tariff editor

Migration 51 creates `profit_calculator_fbe_tariff` with a literal 12-row
`INSERT` from `Grila costuri procesare FBE!B4:S15` as inspected on 2026-11-16.
Stable IDs 1–12 retain source order and separate repeated extra-large labels.
The last tier's weight range and four surcharge fields remain SQL null. A normal
later startup does not reseed or overwrite user edits. Storage tariffs are not
part of this table.

Each row stores the label, four dimension limits (mm), weight band/range (kg),
four base fees and four extra-weight fees (RON excluding VAT), plus a revision.
Required numeric thresholds are positive and fees nonnegative; only weight
range and surcharges may be blank. Revisions start at 1. All editable decimals
are plain strings in JSON and `BigDecimal`/`NUMERIC` on the server/database.

The normal application-user role applies to `GET`/`POST
`/app/profitCalculatorFbeTariffs`. Reads return `{tariffs: [...]}`; writes accept
`{changes: [{id, expectedRevision, values: {...}}]}` and acknowledge commit with
`{saved: true}`. Saves lock rows in stable ID order and reject stale revisions
with HTTP 409, rolling back the entire batch. The real mirror transaction
callback returns `true`, preserving the shared non-null DB contract. The UI
keeps drafts after failed saves/conflicts; successful commit and readback are
separate, so a failed read never retries a committed POST.

`ProfitCalculatorFbeTariffs`, `ProfitCalculatorFbeTariffTable`, and
`profit-fbe-tariffs.js` own request validation, persistence and editing. By
Category fee calculations now use these current saved tariffs as described
below. Storage tariffs and fee calculations remain a separate future step.

### FBE fee calculations while question 7 is pending

The user selected **Exact sheet behavior**, with the explicit intention to
improve it after answer 7. The server reads the selected products' length,
width, height (mm), weight (g) and shared tariffs together in one read
transaction for both category loads and previews. No migration or new endpoint
is needed. Runtime fees use the saved grid, not migration constants or Sheets.

For each axis, find the smallest tariff dimension threshold at least as large
as the product's measurement; choose the highest ID among ties. An unmatched
axis contributes 0. The highest of the three IDs selects the tier; if none is
selected, fees are blank. Preserve this rule even when that tier does not fit
all dimensions, rather than substituting a first-fitting-tier algorithm.

Source-compatibility regression examples include:

- 330 × 230 × 25 mm selects ID 3, although it fits ID 2.
- Identical extra-large dimensions select ID 12 regardless of weight.
- 1000 × 700 × 700 mm selects ID 12 despite its 630 mm height limit.
- 2000 × 150 × 10 mm can still select ID 1 through the other axes; all axes
  exceeding every threshold leaves fees blank.

Convert grams to kg exactly; extra kg is `max(0, ceil(weightKg - weightBandKg))`.
Each fee is its base plus extra kg times its own surcharge. With zero extra
kg, base fees are known even if surcharges are null. Positive extra kg with a
null surcharge leaves that fee blank, preserving independently known other
fees. All four measurements must be known; known numeric zero stays a value.
The existing PNK/nonretracted guard applies. All four outputs are RON excluding
VAT, independent of fulfillment override 45, with no rounding beyond the
explicit excess-kg ceiling and two-decimal display.

The existing response gains nullable calculated fields `fbeProcessingCostRon`,
`fbeReturnCostRon`, `fbeWithdrawalCostRon` and `fbeDisposalCostRon`. Saved tariff
edits affect later loads/previews. The classifier is isolated for future
replacement; questions 4–7 remain open and missing VAT/storage/other costs do
not become zero or incomplete profit totals.

After a successful preview, only its four FBE reference outputs refresh the
GET baseline. Later unrelated draft edits retain these latest fees even while
preview is pending or fails. Saved override values/revisions and other baseline
calculations remain intact, so clearing price/commission drafts still restores
the original defaults and does not accidentally save a previous preview.

## Save failure, correction, and verification limits

The user reported HTTP 500 while saving three 14% commission overrides:
`NullPointerException: database transaction must never null`.

The save callback in `EmagMirrorDB.saveProfitCalculatorOverrides` incorrectly
returned null after executing the SQL helper. `DB.writeTX` checks its callback
result before committing and rolls back on that exception. Consequently, that
failed request did not commit the three overrides. The callback now returns
**`true`**. Do not relax the shared non-null transaction contract to mask this.

Earlier tests missed this boundary: SQL integration tests called the table
helper directly, and HTTP/browser tests substituted the API/storage. They did
not establish that the production save wrapper committed successfully.

The new `EmagMirrorDBProfitCalculatorOverridesIntegrationTest` exercises the
real API → mirror → pooled `DB.writeTX` path. It checks three 0.14 commissions
and revisions from a fresh independent connection, plus full-batch rollback
when one revision is stale. It reproduced the exact NPE before the callback
fix and passed afterward.

For this test seam, `DB(HikariDataSource)` takes a caller-owned pool, and the
`EmagMirrorDB(DB)` constructor is package-private. Tests avoid alias/secret
lookup, application startup, and automatic full-schema migration. PostgreSQL's
startup `options=-c search_path=...` sets their isolated schema: using Hikari
`setSchema` with auto-commit disabled opened a transaction before `DB.writeTX`
could configure isolation and initially prevented reaching the intended test.

### Verification record

| Stage | Result and qualification |
| --- | --- |
| Literal FBE fee calculations | **366 JavaScript tests and 101 focused Java/JTE tests passed**, zero failures/errors/skips. Disposable PostgreSQL checks exercised actual API → pooled mirror reads, product measurements, saved tariff edits, previews, eligibility, monthly quantities and commissions. Pure tests lock in 19 classifier/surcharge cases, including intentional ties/nonfitting selections, unmatched axes, exact gram-to-kg conversion and ceiling, null/zero surcharges and precision. UI checks cover all four amounts, eligibility, categories, stale responses and preservation of refreshed FBE fees during later pending/failed previews without changing saved override baselines. |
| Active-first column ordering | **18 calculator evaluator tests passed**, zero failures/errors/skips, using `mvn -o -Dtest=ProfitCalculatorCategoryTest test`. The ordering regression covers natural name/code order in both active/retracted groups and nonempty previews. An eligibility test now matches products by identity rather than assuming their previous positions. No database or API-shape changes were needed. |
| Initial override implementation | 347 JavaScript tests and 61 focused Java tests passed; PostgreSQL migration/persistence/concurrency checks used a disposable database, with no skipped tests. Desktop/mobile browser checks covered actual templates, frontend, and evaluator with substituted storage, including previews, save/reload, drafts, revert, and conflicts. This preceded discovery of the real-wrapper save bug. |
| After the save-wrapper fix | **33 calculator Java tests passed, zero failures/errors/skips**, including the two real-wrapper regressions and existing API, validation, SQL persistence, and concurrency checks. Tests ran against a disposable PostgreSQL database. |
| Independent zero-cost batch | **351 JavaScript tests passed**, zero failures/skips. The calculator Java selection reported **38 tests, 30 passed, 8 skipped**, zero failures/errors. The eight existing PostgreSQL integration checks skipped because `ADS_TEST_DB_URL` was not configured; no storage or migration changed. New coverage checks independent predicates, unknown/opposite branches, JSON numeric zero, eligibility, category replacement, and pending/failed/stale fulfillment previews. |
| Category-summary batch | **351 JavaScript tests passed**, zero failures/skips. Existing UI checks now verify row 2 across immediate category/group changes, loading, empty results, product failures/retry, stale responses, absent categories/options, and override previews; financial summary rows 3–6 remain blank. Documentation and whitespace checks passed. No Java, API, or database code changed, so Java/PostgreSQL tests were not rerun for this batch. |
| Shared FBE tariff editor | **361 JavaScript tests and 64 focused Java/JTE tests passed**, zero failures/errors/skips. Java tests used a disposable PostgreSQL database, covering every seed value, exact decimal/null/zero round trips, real API → mirror → pooled transaction commit visibility, whole-batch rollback and concurrent stale writers. All 12 SQL seed rows and their 180 editable values were independently compared to the current read-only source and matched. Chromium checked the actual JTE/static files and real API/database through controlled browser routing: 12 tiers/180 inputs, precise save/reload, validation, Revert, retained conflict drafts, desktop/mobile layout and independent grid scrolling; no page-script errors. |
| Monthly gross units and per-product commission | **364 JavaScript tests and 75 focused Java/JTE tests passed**, zero failures/errors/skips. Tests used disposable PostgreSQL and cover selling-account isolation, FBE/non-FBE accounts, colliding order IDs, multiple same-PNK lines, status-4/status-5 behavior, inclusive/exclusive month bounds, null/zero quantities, distinct fractional commissions, override precedence/clearing, eligibility, previews and six Bucharest calendar boundary cases. Row 35 displays positive counts as integers and zero as blank. Documentation and whitespace checks passed. |

These are historical verification results, not a claim that every future
checkout was tested. The temporary PostgreSQL clusters were stopped and removed.
Do not depend on temporary logs/screenshots surviving. No application database
was altered by these tests. Live production data coverage was not reconciled;
schema/import mappings were verified instead.

Useful commands from the repository root:

```sh
node --test src/test/js/*.test.mjs
mvn -o '-Dtest=*ProfitCalculator*Test' test
```

For real PostgreSQL integration checks, configure `ADS_TEST_DB_URL` and, when
needed, `ADS_TEST_DB_USER`/`ADS_TEST_DB_PASSWORD` for a **disposable test database**.
Otherwise integration tests skip. Tests create isolated schemas; some HTTP
tests bind loopback ports. Do not point tests at an application/production
database. No credentials are recorded here.

The independent zero-cost batch used the commands above. Its focused evaluator
run also passed all 15 tests. Compilation, template/HTTP checks, and an independent
code review passed. The category-summary batch ran the JavaScript command only.
Neither batch involved a live database, spreadsheet edit, deployment, or
application restart.

For the FBE batch, the Java command was
`mvn '-Dtest=*ProfitCalculator*Test,JteTemplateEngineTest' test`, with
`ADS_TEST_DB_URL`/`ADS_TEST_DB_USER` set only for the disposable local cluster.
The initial offline invocation could not resolve the uncached Surefire 3.6.1
plugin; the online run downloaded the required dependencies and passed. Browser
checks used controlled routing, not production application startup or background
jobs; the HTTP tests separately exercised the actual Server handlers. Temporary
browser helpers/screenshots are not required to resume this work. No application
database or spreadsheet was changed, and no deployment/restart was performed.
The disposable FBE PostgreSQL cluster was stopped and its data directory removed
after verification.

The monthly-unit/commission batch used the JavaScript command above and
`mvn -o '-Dtest=*ProfitCalculator*Test,JteTemplateEngineTest' test` against a
separate disposable PostgreSQL cluster. Its cluster was stopped and removed
after verification. No application database, spreadsheet, deployment, or
server restart was involved; no migration is required for this batch.

The literal-FBE-fee batch used the same focused Java/JTE command and the full
JavaScript suite, with a fresh disposable PostgreSQL cluster. Its cluster was
stopped and removed after verification. No application database, spreadsheet,
deployment or server restart was involved; no migration was required.

## Remaining work and recovery checklist

1. Inspect current Git status, branch, and commit before changing anything;
   preserve work added after this snapshot. Read this file and the detailed
   [formula documentation](ProfitCalculator.md).
2. Confirm that the server was restarted/deployed with the save fix and the
   user's three commission overrides can now be saved. **No deployment/restart
   was performed by the agent, and user confirmation of a successful retry is
   still pending.** The application factory applies migrations during normal
   startup; migrations 50 and 51 were verified separately in disposable tests.
3. Questions 1–3 are resolved and implemented: gross finalized units, selected
   selling-account group, and per-product commission. The remaining questions
   4–7 in `ProfitCalculator.md` concern settings scope and
   effective dates, import-cost source records, operating-cost input sources,
   and FBE tier selection. They do not block the independent zero branches or
   editing tariffs. Rows 57–60 now follow the current sheet formula by explicit
   user choice; resolve question 7 before changing that classification rule.
4. Trace the next small field group to original inputs, then compare against
   database schema and actual data coverage. Extend eMAG/API/dashboard storage
   only when an identified input is missing; do not invent a replacement value.
5. Continue updating `ProfitCalculator.md` formulas/**Open questions** and this
   recovery handoff when decisions or verification change.

Known data gaps from the repository audit include monetary purchase/freight
import baselines, FBE storage and environmental/packaging/SMS tariffs, and review
acquisition costs. Stored carton dimensions are not freight charges;
`product.import_tax` is a rate, not per-unit customs cost; published review
`review_price` metadata is a selling price, not the cost of obtaining a review.
Advertising spend is stored and the gross monthly sales denominator is now
defined. Row 62 still needs its own verified campaign allocation, period and
adjustment-factor mapping before implementation; do not substitute ad-attributed
units. Spreadsheet tax, inflation, interest, or
adjustment constants must not silently become hardcoded database defaults.

`Calc. Expunere & Profit` calculations remain future work after the category
building blocks are understood. No profit totals or missing cost rows should
be represented as zero merely to make the table look complete.
