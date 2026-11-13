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

The By Category selectors use `vendor.vendor_group` and `product.category`.
Products remain visible when retracted or when their PNK is missing, but the
three value rows below are blank for those products. Missing source values also
remain blank: no default price, tax regime, or VAT status is invented.

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
spreadsheet's row 75 can select a separate row-40 price override. Those rows and
override controls are outside this implementation step.

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

All other metric and summary cells remain empty in this step. In particular,
rows 26, 35, 54, 75, 76, and 86 have not been implemented.

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
