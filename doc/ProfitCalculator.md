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
| 48 | Pret vanzare prod. cu TVA (RON) A. | `product.emag_sale_price_ron` | RON amount, two decimal places |
| 71 | Impozit profit / venit [%] | `product.income_profit_tax` | Tax-regime text, e.g. `Profit` or `Venit` |
| 72 | Platitor de TVA | `product.vat_payer` | Display-only checkbox |

A genuine zero price is displayed as `0.00`. A nonnull `false` VAT status is an
unchecked checkbox; an unknown/null VAT status has no checkbox. Row 71's
existing label is preserved, but its value is **not a percentage**.

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

## Shared manual inputs (rows 40–45)

| Row | Input | Unit and validation | Blank meaning |
| --- | --- | --- | --- |
| 40 | Selling price including VAT | Nonnegative RON amount | Use reference price from row 48 |
| 41 | Commission | Enter a percentage, e.g. `14` for 14%; range 0–100% | No override; default commission remains unresolved |
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
| 76 — Comision eMAG [%] | Row 41 when present; otherwise blank until the default commission is agreed |
| 86 — Cost comision eMAG (cu TVA) | Row 75 × row 76 when both are known |
| 49 and 77 — Cost achizitie | Row 42 × row 44 when both are supplied |
| 50 and 78 — Cost transport | Row 43 × row 44 when both are supplied |

Row 70's source formula looks up local `Date Prod.!K` by PNK. That column
imports `S. Date Prod.!BK`. The product importer resolves those company names
against FBE vendors' `company_name`, so this must not be replaced with
`vendor_name` (which may contain an FBE suffix) or an account label.

The sheet's normal price expressions are `IF(D40<>"",D40,D48)` for row 75 and
`D75` for row 26. Its commission expression is `IF(D41<>"",D41,$C$3)`, followed
by `D75*D76` for row 86. The application implements the commission override but
deliberately leaves the unresolved `$C$3` fallback blank. Clearing row 40
therefore restores the reference price; clearing row 41 clears rows 76 and 86.

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
a conditional formula. They do not require the unresolved monthly-sales or
commission definitions. The same nonblank-PNK/nonretracted guard applies to
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

All routes require the existing application user role. Monthly quantities,
return/refusal rates, import VAT, customs, other fulfillment/advertising costs,
the remaining tax branches, profit totals, and summary outputs remain deferred.

## Open questions

These questions concern the intended business definitions. The observed
spreadsheet mechanics are documented separately from the choices requiring the
sheet author's confirmation. Ignoring manually replaced calculator cells is
already an agreed implementation policy, not an open question.

1. **Previous-month units: gross or net?** Row 35 ultimately sums order-line
   quantities by PNK and order date, excluding `Stornata` rows. The existing
   database exporter supplies finalized/status-4 quantities and negative
   status-5 storno quantities; the sheet excludes the latter. It does not
   explicitly deduct returns or partial-storno quantities from finalized
   lines. Should the calculator retain gross finalized units or compute net
   units after those deductions? This must be settled before reusing an
   existing Orders metric whose snapshot-selection rules differ.
2. **Previous-month units: which selling accounts?** The orders-sheet export
   groups rows by product-name prefix, and the monthly formula filters by PNK
   without a selling-account/FBE criterion. Should a selected product include
   sales through every account, or only accounts in the selected vendor group?
3. **Commission: per product or first product in the category?** The normal
   fallback in row 76 uses category cell `C3`, which takes the first matching
   product's commission. The database instead provides a commission for each
   product in `product.emag_commission`. Is that first-match category default
   intentional, or should future calculations use each product's own rate?
   Also note that the reference category uses external `CS` (`Subcategory`,
   stored as `product.subcategory`), whereas the approved UI uses external `CN`
   (`Subcategory Country`, stored as `product.category`). Category-wide
   assumptions need to account for this distinction.
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

Questions 4–6 concern later fields and do not block the independently
determined zero costs above. The original three questions remain unanswered.
