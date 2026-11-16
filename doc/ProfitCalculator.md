# Profit calculator

## Data sources and implementation policy

The calculator uses PostgreSQL data at runtime. Google Sheets is a read-only
reference for understanding formulas, labels, and business rules; calculator
requests never fetch spreadsheet values.

Use the normal formula for a row consistently across all products. Ignore
individual calculator cells that have been replaced with manually entered
values. This policy also applies to future rows such as 54. Product master data
already stored in the database remains a valid input, including fields that
were originally maintained manually in the product source spreadsheet.

The dedicated manual-input section (rows 40–45) is different: those cells are
intentional overrides and are implemented as editable, shared database values.
Ignoring a value pasted over a formula does not mean ignoring these inputs.

The By Category selectors use `vendor.vendor_group` and `product.category`.
Product columns show active products first, followed by retracted products.
Within each group, the existing natural product-name order and product-code
tie-breaker apply, consistently on category loads and override previews.
Products remain visible when retracted or when their PNK is missing, but the
calculated/reference output rows are blank for those products. Their overrides
remain editable and stored. Missing source values remain blank: no default
price, tax regime, VAT status, cost, or exchange rate is invented.

## Implemented rows

These row numbers refer to the **Actual** area of the reference tab
[2. Trim.](https://docs.google.com/spreadsheets/d/16VK84fOII89oJBZur5tfH-HhOvLGzTrb-7UMVlzm5L0/edit#gid=1896630135).
The formulas below use column D as an example; other product columns follow
the same rule. `D8` is the product PNK and `D9` is its retracted flag.

| Row | Original label | Database input | Display |
| --- | --- | --- | --- |
| 2 | Category | Selected `product.category` from the database-backed selector options | Literal category text |
| 35 | Nr. unitati vandute luna anterioara (ant.) | Gross finalized order-line quantities, restricted to selling accounts in the selected vendor group | Integer count; zero remains blank |
| 48 | Pret vanzare prod. cu TVA (RON) A. | `product.emag_sale_price_ron` | RON amount, two decimal places |
| 71 | Impozit profit / venit [%] | `product.income_profit_tax` | Tax-regime text, e.g. `Profit` or `Venit` |
| 72 | Platitor de TVA | `product.vat_payer` | Display-only checkbox |

A genuine zero price is displayed as `0.00`. A nonnull `false` VAT status is an
unchecked checkbox; an unknown/null VAT status has no checkbox. Row 71's
existing label is preserved, but its value is **not a percentage**.

### Row 2 — category summary

The summary displays the selected database category, using the same
`product.category` value as the category selector. It is not the abbreviated
spreadsheet tab name or `product.subcategory`.

The text updates immediately when the group/category selection changes and
remains visible while products load, when there are no products, and when a
product request fails. Override previews retain it. Reloading selector options
or having no valid group/category selection clears it. This is a selection-level
summary, so it does not depend on any individual product's PNK or retracted
status. Financial summary rows 3–6 remain blank.

### Row 48 — reference selling price including VAT

```excel
=IF(D$8<>"",IF(D$9<>TRUE,IFERROR(INDEX('Date Prod.'!$F:$F,MATCH(D$8,'Date Prod.'!$D:$D,0)),),),)
```

For an eligible product, look up the reference selling price by PNK. The server
reads `product.emag_sale_price_ron` from that product's database record.

This is the reference price, not the effective price in rows 75 and 26. The
row-40 override changes rows 75 and 26, while row 48 continues to show the
database reference price.

### Row 71 — tax regime

```excel
=IF(D$8<>"",IF(D$9<>TRUE,IFERROR(INDEX('Date Prod.'!$L:$L,MATCH(D$8,'Date Prod.'!$D:$D,0)),),),)
```

For an eligible product, look up the tax regime by PNK. The source resolves it
from the product's selling company. The database already holds the resulting
text in `product.income_profit_tax`; no tax amount or rate is computed here.

### Row 72 — VAT-payer status

```excel
=IF(D$8<>"",IF(D$9<>TRUE,IFERROR(INDEX('Date Prod.'!$M:$M,MATCH(D$8,'Date Prod.'!$D:$D,0)),),),)
```

For an eligible product, look up its company's VAT-payer boolean by PNK. The
database holds the resolved boolean in `product.vat_payer`. The checkbox cannot
modify that field. This row is not the order line's VAT rate or the import VAT
amount.

### Import lineage and database mapping

The calculator's local `Date Prod.!A1` uses `QUERY(IMPORTRANGE(...))` to import and
reorder columns from `S. Date Prod.` in the separate
[Date produse workbook](https://docs.google.com/spreadsheets/d/1q8lltfW6bMc3nEzoes7lrAn2Kp_W5UdH5VECtLaIejw/edit#gid=159482373).
The important projected columns are:

| Calculator's local column | External source column | Meaning |
| --- | --- | --- |
| `Date Prod.!D` | `S. Date Prod.!BH` (column 60) | PNK used for lookup |
| `Date Prod.!F` | `S. Date Prod.!BN` (column 66) | Reference price including VAT |
| `Date Prod.!L` | `S. Date Prod.!BL` (column 64) | Tax regime |
| `Date Prod.!M` | `S. Date Prod.!BM` (column 65) | VAT-payer status |

`BN` is product master price data. `BL` looks up the selling company from `BK`
in `'Date Firme'!B:B`, returning its tax regime from `P:P`. `BM` performs the same
company lookup and returns its VAT-payer status from `Q:Q`. Both `Date Firme`
and `S. Date Prod.` belong to the same external workbook.

`PopulateProductsTableFromSheets.ProductColumn` maps `BH`, `BL`, `BM`, and `BN`
to the product importer fields. `ProductTable` persists/reads them as
`emag_pnk`, `income_profit_tax`, `vat_payer`, and `emag_sale_price_ron`.
The calculator therefore uses the resolved database fields directly instead of
recreating spreadsheet imports or reaching back to the company sheet.

### Shared FBE tariffs

The external FBE workbook is titled
[Calculator cost FBE](https://docs.google.com/spreadsheets/d/1VkeF6LQMGVXZRBuVee2p2Oby0JYbmSwc8gBbdLrlcXk/edit).
Its tariff tab is
[Grila costuri procesare FBE](https://docs.google.com/spreadsheets/d/1VkeF6LQMGVXZRBuVee2p2Oby0JYbmSwc8gBbdLrlcXk/edit#gid=1892248028).
This is the reference for the processing, return, withdrawal, and disposal
fees needed by rows 57–60. Migration 51 creates `profit_calculator_fbe_tariff`
and includes a literal SQL `INSERT` of the 12 processing-tariff rows from
`B4:S15`, as inspected on **2026-11-16**. There is no transfer program or
runtime spreadsheet import. Later startups do not reseed or overwrite edits.

The **FBE Tariffs** page (`/private/profit-fbe-tariffs`), immediately after
By Category, edits one current grid shared across all vendor groups. This
first step edits the 12 existing tiers; it does not add/delete tiers or
implement historical tariff schedules. Stable IDs 1–12 preserve source order.
The four extra-large tiers remain separate despite identical category names
and dimension limits.

Each row stores the source category label, four maximum dimensions in mm,
`weightBandKg`, four base fees in RON excluding VAT, `weightRangeKg`, and
four extra-weight fees in RON. The weight-field names preserve the source
labels rather than assume a corrected classification algorithm. The final
tier's blank weight range and four blank surcharge fields are null, not zero.
Dimensions and weight thresholds must be positive; fees are nonnegative.
Category labels and base fields are required. Weight range and surcharge
fields may remain blank. The girth and weight-range inputs are retained even
though the observed processing classifier does not use them.

Users review edits before Save; Revert loads the latest saved rates. Saves
are atomic across changed rows and require each row's expected revision.
A conflict leaves the entire batch unchanged and retains drafts for review.
Decimal reads and writes use plain strings, and the server uses `BigDecimal`
without intermediate rounding. Zero fees and null fields stay distinct.

`GET /app/profitCalculatorFbeTariffs` returns `{tariffs: [...]}` with IDs,
revisions and decimal-string values. `POST` to the same endpoint accepts
`{changes: [{id, expectedRevision, values: {...}}]}` and acknowledges a
successful commit with `{saved: true}`. The existing application-user role
is required. Reloading after a committed save is separate and never repeats
the write if readback fails.

This step supplies editable storage only: By Category rows 57–60 remain
blank until the classifier question below is resolved. Product dimensions
and weight already exist in the database, but live data coverage has not been
reconciled. Storage tariffs and storage-cost calculations remain separate;
they also need import and stock-duration inputs. The reference workbook
remains read-only.

## Shared manual inputs (rows 40–45)

| Row | Input | Unit and validation | Blank meaning |
| --- | --- | --- | --- |
| 40 | Selling price including VAT | Nonnegative RON amount | Use reference price from row 48 |
| 41 | Commission | Enter a percentage, e.g. `14` for 14%; range 0–100% | Use that product's `product.emag_commission` when known |
| 42 | Purchase price per unit | Nonnegative USD amount | Purchase-cost baseline is not available yet |
| 43 | Transport price per unit | Nonnegative USD amount | Transport-cost baseline is not available yet |
| 44 | USD/RON exchange rate | Positive RON per USD | No exchange-rate default is available yet |
| 45 | Fulfillment source | Blank, `eMAG`, or `Minulescu` | No manual choice |

Zero monetary amounts and zero commission are valid overrides. Clearing a
field means no override; it does not mean zero. Reference rows 48, 71, and 72
are never changed by overrides. An explicit `Minulescu` choice in row 45 gives
zero storage cost in row 88. Other fulfillment charges remain deferred because
the necessary tariffs have not been implemented.

Edits preview the results without writing to the database. Unsaved edits stay
with their product when switching categories or vendor groups. Review changes
before saving; Save commits the reviewed overrides, while Revert discards
drafts and reloads saved values. Leaving the page with unsaved edits triggers
the browser's normal unsaved-change warning.

Overrides live in `profit_calculator_override`, keyed by `product_code`,
separately from `product` master data. Its nullable fields are `sale_price_ron`,
`commission_rate`, `purchase_price_usd`, `transport_price_usd`, `usd_ron_rate`,
and `fulfillment_source`. Commission is stored as a fraction (`0.14` for 14%).
Numeric requests and saved override responses use plain decimal strings to
preserve their exact values across browser reloads. Calculations use
`BigDecimal` without rounding intermediate results; calculated RON outputs are
displayed to two decimal places.

Saves are atomic across all reviewed products. Each override record has a
revision; zero represents an unsaved/absent record. A stale revision causes a
409 conflict and leaves the batch unchanged. The UI retains the unsaved edits
so the user can review the conflict or revert to the latest saved values.
Clearing every override retains the record and its revision to avoid resetting
the concurrency check. Saving does not update product master fields.

Migration 50 creates this storage. The history-import migration guard also
accepts databases already upgraded beyond version 49.

## Additional implemented outputs

The original formulas below are simplified to their value expressions; the
PNK/nonretracted guard still applies to every output. Missing operands produce
blank results unless a known formula branch independently determines zero.

| Row(s) | Rule used by the server |
| --- | --- |
| 70 — Firma de pe care vindem | `vendor.company_name`, joined through `product.vendor = vendor.id` |
| 75 — Pret produs (cu TVA) | Row 40 when present, otherwise row 48 |
| 26 — Total Venit/Unitate (RON) | Row 75 |
| 76 — Comision eMAG [%] | Row 41 when present; otherwise that product's `product.emag_commission` |
| 86 — Cost comision eMAG (cu TVA) | Row 75 × row 76 when both are known |
| 49 and 77 — Cost achizitie | Row 42 × row 44 when both are supplied |
| 50 and 78 — Cost transport | Row 43 × row 44 when both are supplied |

Row 70's source formula looks up local `Date Prod.!K` by PNK. That column
imports `S. Date Prod.!BK`. The product importer resolves those company names
against FBE vendors' `company_name`, so this must not be replaced with
`vendor_name` (which may contain an FBE suffix) or an account label.

The sheet's normal price expressions are `IF(D40<>"",D40,D48)` for row 75 and
`D75` for row 26. Its commission expression is `IF(D41<>"",D41,$C$3)`, followed
by `D75*D76` for row 86. The author clarified that the intended fallback is
**per product**, rather than the sheet's first-match category default in C3.
The application therefore uses `product.emag_commission` when override 41 is
absent. Source column BO is imported unchanged as a fraction: `0.18` means
18%, and is not divided by 100 again. A supplied zero override takes precedence;
a zero product default is also valid. Missing defaults remain blank.

Clearing row 40 restores the reference price; clearing row 41 restores the
product's commission and recalculates row 86 when price and rate are known.
Previews preserve both the product default and the stored monthly quantity.
Category summary row 3 remains blank because there is no single category rate.

### Row 35 — previous-calendar-month gross units

The author confirmed gross finalized units, excluding `Stornata` records and
restricting selling accounts to the selected vendor group. The period is the
previous calendar month in **Europe/Bucharest**, with inclusive start and
exclusive end. It is computed once per category/preview request using a clock
that tests can fix. The database stores API order dates as local, unzoned
timestamps; the boundaries are local midnights, without an extra UTC conversion.

Match selected product PNKs against `product_in_order.part_number_key`, joining
orders by `emag_order_surrogate_id`. Sum **`quantity` on status-4 orders only**.
Apply the account filter through `emag_order.vendor_id → vendor.vendor_group`,
not merely the product owner's vendor. Both FBE and non-FBE selling accounts
in the selected group contribute. Count every legitimate product line and
keep separate selling accounts distinct even when their order IDs match.

Do not reconstruct units from `initial_qty`, subtract `storno_qty` or returns,
filter on `is_storno`, or use a latest-status canonical view. In particular:

- Finalized quantity 3 plus a later storno snapshot contributes **3 gross
  units**, once; the status-5 record contributes nothing.
- A storno-only record contributes no units, without reconstructing a sale.

The batched database query reads selected PNKs together. No matching finalized
sales means a known zero. If a matching line's quantity is null, the product's
total remains unknown rather than displaying a partial sum. The API exposes
`calculated.previousMonthGrossUnits` as a nullable integer. Positive counts
display without monetary decimal places; zero remains blank, matching the
reference row. The usual PNK/nonretracted guard also applies.

This row deliberately does not reuse `OrderQuantityTable`: its status-5
fallback and order/PNK snapshot selection implement a different metric.
Rows 36–37 and financial summaries remain deferred until their cost/profit
inputs are available.

### USD conversion provenance

The **Actual** columns do not reference rows 42–44 in their cost formulas; they
normally load purchase/transport costs from import calculations. The separate
**Simulation** columns contain these expressions:

```excel
BC49 = IF(BC8<>"",BC$42*BC$44,)
BC50 = IF(BC8<>"",BC$43*BC$44,)
BC77 = IF(BC$8<>"",IF(BC$9<>TRUE,BC$49,),)
BC78 = IF(BC$8<>"",IF(BC$9<>TRUE,BC$50,),)
```

Applying these conversions to By Category's Actual area is an agreed extension
of the simulation behavior. Both a USD amount and a positive exchange rate
are required. There is no current purchase/freight ledger or exchange-rate
default to fall back to, so clearing either required operand clears the result.
Import VAT and customs costs (rows 51/52/79/80) remain blank; their additional
rates and baseline data are not inferred from these manual inputs.

### Independently determined zero costs

The following outputs implement literal zero formulas or the zero branch of
a conditional formula. They do not depend on monthly-sales or commission
definitions. The same nonblank-PNK/nonretracted guard applies to
all seven rows, including the literal-zero advertising formulas.

| Row | Label | Implemented result |
| --- | --- | --- |
| 63 | Cost Google ads / unit. (fara TVA) [RON] | `0.00` for an eligible product |
| 64 | Cost SM ads/ unit. (fara TVA) [RON] | `0.00` for an eligible product |
| 88 | Cost depozitare colet (cu TVA) | `0.00` only when row 45 explicitly selects `Minulescu` |
| 100 | Cost impozit / venit | `0.00` only when `product.income_profit_tax` is `Profit` |
| 101 | TVA | `0.00` only when `product.vat_payer` is explicitly false |
| 105 | Impozit pe profit | `0.00` only when `product.income_profit_tax` is `Venit` |
| 106 | Impozit pe dividende | `0.00` only when `product.income_profit_tax` is `Venit` |

Rows 63 and 64 use this complete source formula:

```excel
=IF(D$8<>"",IF(D$9<>TRUE,0,),)
```

That literal zero is a rule in the reference calculator, not measured Google
or social-media advertising spend and not a claim that those channels have no
expenses outside the calculator.

The conditional rows use the following source value expressions, with the
same eligibility guard surrounding each expression:

```excel
D88 = IF(D$45="eMAG",D61*(1+'Date Calcul'!$C$9),0)
D100 = IF(D$71="Venit",((D$75/(1+'Cons. Date Secundare'!$E$3))+'Date Calcul'!$C$22)*'Date Calcul'!$C$10,0)
D101 = IF(D$72=TRUE,(D$75+'Date Calcul'!$C$22)*'Date Calcul'!$C$9-D$79-(D83+D84+D85+D87+D88+D89+D90+D91+D92+D94+D95+D96+D98)*'Date Calcul'!$C$9,0)
D105 = IF(D$71="Profit",IF(D$104>0,'Date Calcul'!$C$11*D$104,0),0)
D106 = IF(D$71="Profit",IF(D$104>0,D$105*'Date Calcul'!$C$12,0),0)
```

Only the branches listed in the table are implemented. The application does
not follow a broad spreadsheet `else 0` branch for an unknown input: blank
fulfillment, null VAT status, and null/blank/unrecognized tax regimes remain
unknown. Known tax labels are recognized case-insensitively after trimming;
the displayed source text in row 71 is unchanged. `eMAG` storage, `Venit`
turnover tax, VAT-payer VAT, and `Profit` profit/dividend tax remain blank
until their required inputs are available, even if a later calculation could
also determine zero.

The server returns these amounts as nullable calculated RON values, with
numeric zero displayed as `0.00`. Changing row 45 clears a previous row-88
result while its preview is pending; a failed preview does not restore a
stale storage amount. No missing cost is converted to zero, and cost/profit
totals remain deferred rather than being calculated from incomplete costs.

### API behavior

- `GET /app/profitCalculatorCategory` returns the saved overrides, revision,
  company name, reference fields, and calculated values for each product.
- `POST /app/profitCalculatorPreview` applies draft changes to database values
  and returns the same category response without writing anything.
- `POST /app/profitCalculatorOverrides` saves changed fields atomically with
  expected revisions and acknowledges a successful commit. Reloading the
  category afterward is separate; a reload failure must not repeat the save.

All routes require the existing application user role. Return/refusal rates,
import VAT, customs, other fulfillment/advertising costs,
the remaining tax branches, profit totals, and financial summary rows 3–6 remain
deferred.

## Resolved author questions (1–3)

The author answers supplied on **2026-11-16** establish these intended rules:

1. **Previous-month units are gross**, without `Stornata` records. Use the
   finalized quantities as described above, without net return/storno deductions.
2. **Sales accounts are restricted to the selected vendor group.** This is
   an explicit account filter, replacing the export's product-name-prefix
   partitioning; the same PNK's sales outside the selected group are excluded.
3. **Commission is per product.** Use that product's `emag_commission` unless
   row 41 overrides it. The first product in the category is not a default for
   the other products. The source's CS/subcategory versus the UI's CN/category
   distinction therefore does not affect commission selection.

## Open questions

The following questions remain open. Their original numbers are retained so
the author can refer to the existing question list. Ignoring manually replaced
calculator cells remains an agreed policy, not an open question.

4. **Calculator settings: scope, ownership, and effective dates.** Who maintains
   the rates and fixed costs in `Date Calcul`, such as VAT/tax rates,
   packaging/warehouse charges, Genius assumptions, financing, and inflation?
   Are these settings shared across groups/companies or specific to a workbook
   or company? Where should the server obtain them, and should a historical
   calculation use the settings valid in that month or the latest settings?
5. **Authoritative import-cost records.** Where are per-import purchase,
   freight, import VAT, customs costs, quantities, transport details, and
   arrival dates maintained outside the linked spreadsheets? Which records
   can the server ingest to supply the missing import-lot and cost inputs?
6. **Authoritative operating-cost inputs.** Who maintains the warehouse/FBE,
   environmental/packaging, SMS, and review-acquisition inputs, and where can
   their underlying tariffs, component measurements, and expenses be obtained?
   General product weight, storefront selling prices, and ordinary eMAG order
   data do not supply all of these inputs.

7. **FBE size and weight tier selection.** The processing formula selects the
   maximum matching tariff-row ID from the dimension thresholds. The four
   extra-large tiers (IDs 9–12) have identical dimensions and different weight
   bands, so that formula selects ID 12 on a dimensional tie regardless of
   weight. Its base processing fee is 177 RON with a 300 kg weight band,
   whereas ID 9 starts at 30 RON with a 10 kg band. Is that selection
   intentional, or should these tiers be selected by weight? Should maximum
   girth and the separate weight-range field also constrain selection? They
   are stored in the grid but not used by the observed classifier.

Questions 1–3 are resolved above. The shared tariff editor supplies the FBE
processing-rate inputs discussed in question 6, but does not
resolve their long-term ownership or the classification rule in question 7.
None of these questions blocks editing the stored tariff grid or the
independently determined zero costs above.
