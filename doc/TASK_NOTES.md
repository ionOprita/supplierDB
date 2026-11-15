# Profit calculator — task notes and recovery handoff

Last reviewed: **2026-11-15**. Repository branch: `profit-calculator`.
Base commit for the latest batch: `12cf14f`
(`Summarized current work to keep context after compatcion.`).
The working tree was clean before this batch. These notes include the subsequent
independent-cost changes described below, not a confirmed production deployment.

Start here after losing the chat, then read [ProfitCalculator.md](ProfitCalculator.md)
for detailed formulas, mappings, API behavior, and the maintained **Open questions**.

## Current objective and working method

Incrementally reproduce the spreadsheet profit calculator inside the existing
server, using data stored in PostgreSQL. Work in small, understandable batches
so the original sheet author can verify the business interpretation. Discovering
missing data is part of the objective: later work may extend the database,
eMAG API collection, or eMAG dashboard extraction.

The current work is on **By Category**. The other calculator page is a scaffold
for later work. The user has forwarded the original three open questions to the
sheet author; answers remain pending. Three further questions now cover settings
ownership/timing and authoritative import/operating-cost inputs.

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
| External FBE calculator | [Workbook](https://docs.google.com/spreadsheets/d/1VkeF6LQMGVXZRBuVee2p2Oby0JYbmSwc8gBbdLrlcXk/edit); `Calc. cost FBE (Total)` |
| EEE/BAT/packaging source | [Workbook](https://docs.google.com/spreadsheets/d/13xZcGdWG4WKP6TwuXwjVjKeuO99-9QHzuMQgg0S9Po4/edit); `Cost EEE & BAT & Ambalaje/prod.` |

The last two import sources were identified, but their internal pricing chains
have not been fully traced. Workbook contents can evolve; re-read metadata and
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
  category product. It is not automatically each product's own commission.
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

| Row(s) | Current behavior |
| --- | --- |
| 7–9 | Product name, PNK, display-only retracted checkbox |
| 48 | `product.emag_sale_price_ron`, reference price including VAT |
| 70 | Linked `vendor.company_name` |
| 71 | `product.income_profit_tax` text |
| 72 | Nullable `product.vat_payer` display-only checkbox |
| 75, 26 | Override 40 when present, otherwise reference 48 |
| 76 | Override 41 only; default commission deliberately remains blank |
| 86 | Effective price × effective commission when both are known |
| 49, 77 | USD purchase override 42 × exchange-rate override 44 |
| 50, 78 | USD transport override 43 × exchange-rate override 44 |
| 63, 64 | Literal zero from the Google/social-media Ads formulas; not measured spending |
| 88 | Zero only for an explicit `Minulescu` fulfillment override |
| 100 | Zero only for the known `Profit` tax regime |
| 101 | Zero only when VAT-payer status is explicitly false |
| 105, 106 | Zero only for the known `Venit` tax regime |

The latest seven outputs implement only independently determined branches.
Tax-regime comparisons trim whitespace and ignore case, retaining the original
display text. Null/unknown regimes, null VAT status, blank fulfillment, and
branches needing unavailable cost/tax inputs remain blank. The existing
PNK/nonretracted guard applies even to literal-zero results. Cost and profit
totals remain blank. These zero rules do not establish missing rates or tariffs.

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
fees, profit totals, monthly quantities, and summary outputs.

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
| Initial override implementation | 347 JavaScript tests and 61 focused Java tests passed; PostgreSQL migration/persistence/concurrency checks used a disposable database, with no skipped tests. Desktop/mobile browser checks covered actual templates, frontend, and evaluator with substituted storage, including previews, save/reload, drafts, revert, and conflicts. This preceded discovery of the real-wrapper save bug. |
| After the save-wrapper fix | **33 calculator Java tests passed, zero failures/errors/skips**, including the two real-wrapper regressions and existing API, validation, SQL persistence, and concurrency checks. Tests ran against a disposable PostgreSQL database. |
| Independent zero-cost batch | **351 JavaScript tests passed**, zero failures/skips. The calculator Java selection reported **38 tests, 30 passed, 8 skipped**, zero failures/errors. The eight existing PostgreSQL integration checks skipped because `ADS_TEST_DB_URL` was not configured; no storage or migration changed. New coverage checks independent predicates, unknown/opposite branches, JSON numeric zero, eligibility, category replacement, and pending/failed/stale fulfillment previews. |

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

The latest batch used the commands above. Its focused evaluator run also
passed all 15 tests. Compilation, template/HTTP checks, and an independent
code review passed. No live database, spreadsheet, deployment, or application
restart was involved in this batch.

## Remaining work and recovery checklist

1. Inspect current Git status, branch, and commit before changing anything;
   preserve work added after this snapshot. Read this file and the detailed
   [formula documentation](ProfitCalculator.md).
2. Confirm that the server was restarted/deployed with the save fix and the
   user's three commission overrides can now be saved. **No deployment/restart
   was performed by the agent, and user confirmation of a successful retry is
   still pending.** The application factory applies migrations during normal
   startup; migration 50 was verified separately in disposable tests.
3. Await the author answers already requested: gross versus net monthly units,
   selling-account scope, and intentional category-first versus per-product
   commission defaults. Keep the pending commission fallback blank meanwhile.
   The additional questions in `ProfitCalculator.md` concern settings scope and
   effective dates, import-cost source records, and operating-cost input sources;
   they do not block the independent zero branches already implemented.
4. Trace the next small field group to original inputs, then compare against
   database schema and actual data coverage. Extend eMAG/API/dashboard storage
   only when an identified input is missing; do not invent a replacement value.
5. Continue updating `ProfitCalculator.md` formulas/**Open questions** and this
   recovery handoff when decisions or verification change.

Known data gaps from the repository audit include monetary purchase/freight
import baselines, FBE and environmental/packaging/SMS tariffs, and review
acquisition costs. Stored carton dimensions are not freight charges;
`product.import_tax` is a rate, not per-unit customs cost; published review
`review_price` metadata is a selling price, not the cost of obtaining a review.
Advertising spend is stored, but its calculator allocation still depends on
the unresolved sales denominator. Spreadsheet tax, inflation, interest, or
adjustment constants must not silently become hardcoded database defaults.

`Calc. Expunere & Profit` calculations remain future work after the category
building blocks are understood. No profit totals or missing cost rows should
be represented as zero merely to make the table look complete.
