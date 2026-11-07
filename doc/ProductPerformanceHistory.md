# Historical Product Performance import

`ImportProductPerformanceHistory` imports the four approved performance spreadsheets through the
existing Google service account. It reads source tabs directly; it never changes a dashboard
selector, writes to Google Sheets, starts the server, refreshes a database, or calls an eMAG collector.
The default database alias is `emagLocal` and the default operation is a read-only dry run.

## Sources and identity

The approved source IDs are fixed in `ProductPerformanceHistoryImporter.APPROVED_SOURCES`:

| Report | Spreadsheet ID |
|---|---|
| Sellfusion | `142uaMMd2wf8XPLdLX7_wcWl_Jp0XkKDNREhc-RnmRlQ` |
| Zoopie | `1-mDyK4JaYIzlgMSShnDMFXLs3N0tBew8WOYB59nn_iQ` |
| Koppel | `1Jd2Oh8KXX_k2mAv7GnR8tghJzZ8-E0RnPcLXoGQ5_VE` |
| Judios | `1RwL9e-fmZgixk797z6yFGUHgnlVguuHtdq8bGSdSH0E` |

The importer first reads tab metadata and grid bounds, then catalogue and source headers. Every
values request is an explicitly bounded rectangle of at most 50,000 cells and uses
`UNFORMATTED_VALUE` with serial dates. Strings, blanks, zeroes, decimals and formula errors are
archived without turning blank cells into zeroes. The previous formatted-string Sheets helpers
are unchanged.

`Date Prod.` provides the product name, company, PNK and product code. Source rows must match that
catalogue by exact PNK/name/company; the catalogue must then match the database by exact
PNK/product code/account. If the database PNK is absent, a unique exact product code/account
match may identify the source PNK; this is explicitly reported and does not update the product
catalogue. A present conflicting database PNK is never overridden. Review/rating tabs lack a company field and use their exact catalogue
PNK/name association. Whitespace at the edges is trimmed; there is no fuzzy matching or fallback
to another company. Missing or ambiguous identities remain in the raw archive and diagnostics,
without becoming report observations. Withdrawn products and products with zero stock remain
eligible. Family IDs remain attached to family review and rating observations.

The confirmed source-account mapping is Judios Concept SRL → `judios`, Koppel → `koppelfbe`,
Sellfusion SRL → `sellfusion`, Zoopie Concept SRL → `zoopieconcept`, and Zoopie Solutions SRL →
`zoopiesolutions`. These are exact configured account aliases, not a request to combine main and
FBE accounts. A database product must belong to that mapped account.

## Stored observations

| Source tab | Domain | Values |
|---|---|---|
| `Date Organic/W.` | `organic` | `stock`, `gmv`, `clicks`, `salesPrice`, `sourceConversion`, `performance` |
| `eMAG ADS/W.` | `ads` | For each `auto`, `broad`, `exact`, `product`: `_impressions`, `_clicks`, `_spend`, `_sales`, `_units`, `_salesCount` |
| `Nr. Rew. Fam./W.` | `reviews` | `reviews`, `familyId` |
| `Rating Fam./W.` | `rating` | `rating`, `familyId` |

The weekly Organic and Ads entries are observations of **rolling 30-day totals**, as confirmed by
the owner. They are not seven-day increments. The original observation date is preserved; it is
never replaced by the import date. Organic and Ads records retain `sourceStartDate`,
`sourceEndDate`, and `sourceWindowDays`. The dates are inclusive and end on the source observation
date. Future headers are ignored. For an identified product and dated source column, explicitly
blank metrics are retained as nulls, including when every metric in that domain is blank.
Review/rating observations are point-in-time family measurements, including older history.

`salesPrice` in the Organic source already includes VAT. The source `gmv` and `sourceConversion`
are archived for audit, not used to infer a tax basis or replace native order totals. Orders and
Total Sales continue to come from native orders for the selected selling account. Ads Spend and
Sales are net of VAT, as confirmed by the owner. The broken historical average-price tab is not
an input; it must not replace the available gross Sales Price observations.

Each normalized observation records its spreadsheet ID, sheet ID/title and exact original cell
range. Null metrics remain null, and source formula errors remain visible in diagnostics. A source
read or formula error makes the run incomplete and prevents `--apply`.

## Suppressed Ads zeroes

Some original Ads matrix formulas use `IF(SUMIFS(...)=0,"",SUMIFS(...))`. Consequently, a blank
matrix cell can represent either a real zero or unavailable data. The importer discovers the
original Ads master from the report's `IMPORTRANGE` formula, reads its metadata and headers, and
archives bounded reads of `Introd. Date`.

A blank is restored to zero only when matching raw records for the same observation, unique PNK
in the campaign name and category contain numeric values, their sum is zero, and the raw source
intervals cover the complete 30-day window. Any missing primitive or incomplete/invalid date
evidence leaves that metric unknown. The raw master does not overwrite a nonzero matrix value or
create extra weekly snapshots. Different observation dates are never summed together.

For example, the Judios 1 May 2026 observation contains raw report partitions 2–30 April and
1 May. The 8 May observation contains 9–30 April and 1–8 May. Each union is 30 days, including the
observation day. A single partition must not be mistaken for the full report window. Raw report
column labels such as `7 Day Total Sales` describe the exported metric; they do not make the
report date interval seven days.

## Dry run, audit, and apply

Build with the project's Java 25/Maven setup. With the runtime dependency classpath already
written to `target/rebuild-classpath.txt`, a dry run is:

```sh
java -cp "target/classes:$(cat target/rebuild-classpath.txt)" \
  ro.sellfluence.app.ImportProductPerformanceHistory \
  --db=emagLocal --through=2026-11-06 --out=/tmp/product-performance-history
```

Optional `--only=Judios` (or Sellfusion, Zoopie, Koppel) limits a diagnostic run to one approved
report. Omit it for the full import. Pin `--through` when comparing or applying a reviewed run.

The command writes an immutable `<sha256>.json` raw archive and a `<sha256>-report.json` containing
per-product/domain date coverage, observation counts, the count of recovered zero metrics, and
unresolved/error diagnostics. Empty catalogue slots with neither PNK nor product code are counted
once per source rather than generating hundreds of duplicate warnings. Recovered Ads metrics are
identified by the `zeroRestoredMetrics` provenance field. The hash
includes parser version, source metadata, captured typed values/formulas and normalized mappings;
identical content produces the same revision. A failed acquisition is recorded in
`last-failure.json`, and incomplete source runs retain their available raw evidence and diagnostics.
The archive contains business data and should remain in the local audit location.

If the local Google service-account credential is unavailable, the connected Google Sheets tools
can acquire the same bounded, typed ranges without changing the source. Supply these explicitly
captured files to the same dry-run/apply workflow:

```sh
java -cp "target/classes:$(cat target/rebuild-classpath.txt)" \
  ro.sellfluence.app.ImportProductPerformanceHistory --through=2026-11-06 \
  --capture-file=/tmp/history-capture-sellfusion.json,/tmp/history-capture-zoopie.json,/tmp/history-capture-koppel.json,/tmp/history-capture-judios.json
```

The capture format is `{metadata: {spreadsheetId: [{sheetId,title,rows,columns}]}, captures:
[{spreadsheetId,sheet,range,render,values}]}`. `range` is a bounded cell-only rectangle such as
`A1:F20`; `render` is `UNFORMATTED_VALUE` or `FORMULA`. Numeric values and booleans remain typed.
The metadata and every requested cell must be present. Empty trailing cells inside a captured
rectangle are known blanks; an uncovered cell is an error. Overlapping captures must agree.
This permits reproducible offline parsing and apply of the reviewed capture without needing the
credential or rereading a live source. Apply still revalidates product identities against the
selected database and checks the expected hash. Keep the same capture files and cutoff for apply.

After reviewing the concrete dry-run report, apply that exact content using the printed hash:

```sh
java -cp "target/classes:$(cat target/rebuild-classpath.txt)" \
  ro.sellfluence.app.ImportProductPerformanceHistory \
  --db=emagLocal --through=2026-11-06 --out=/tmp/product-performance-history \
  --apply --expected-hash=THE_REVIEWED_SHA256
```

Apply rereads the selected live/captured sources and refuses a changed hash or incomplete run.
By default writes require a local database host. Local apply can run only the feature's schema
migration from version 48 to 49. Remote dry runs are already permitted; remote writes require the
explicit `--allow-remote` flag together with `--apply --expected-hash=<reviewed-hash>`. Remote apply
requires schema version 49 to be installed already and never runs a migration. Both paths store
the raw batch and normalized observations in one database transaction without running general
application startup migrations. A failure rolls
back the transaction and records a failed apply report. Reapplying the same content hash returns
the original batch without duplicating observations. Corrections produce a new immutable revision;
report reads select the newest normalized revision for each product/date/domain. Prior raw batches
remain available for audit.

Normalized observations are sent in JDBC batches of at most 500 rows, avoiding a network round
trip for every observation during remote imports. Every row must report exactly one insertion,
including its product/account ownership check. All batches share the same transaction; a failure
in a later batch rolls back the earlier batches and the raw archive record as well.

Parser `product-performance-history-v2` also records explicitly empty dated snapshots. Clearing
a rating cell, an individual Organic/Ads metric, or every metric in an identified snapshot and
reimporting replaces those historical values with nulls. Empty snapshots do not create report
periods or provisional preview dates, and do not override native data. Ads blanks are still restored
to zero only when the raw source proves zero as described above. Deleting a source row or date
header does **not** withdraw its saved history; unresolved identities likewise emit no withdrawal.
Only the selected reports and dates through `--through` are considered.

The correction uses existing nullable metric JSON and immutable revisions; it requires no new
schema migration. Existing imported batches are unchanged. To apply the v2 interpretation, run
a new dry run, review its new content hash and report, then apply that reviewed hash. An earlier
v1 hash cannot approve the changed parser output.

The import is a deliberate CLI action, not a recurring collection job. Native daily collection
continues independently, and historical spreadsheet observations remain separate from native
order, offer and Ads tables.

## Verified local import on 6 November 2026

The service-account credential was absent locally, so the four reports and their original Ads
masters were acquired through the connected Google Sheets reader and replayed through the same
v1 parser. The completed dry run produced 42,374 observations for 157 products, including 84 withdrawn
products, and restored 739 Ads metric zeroes from raw numeric evidence. There were no unresolved
real PNK/code/account conflicts. Diagnostics retain catalogue entries without identifiers and the
43 `KOPPELNOPNK` placeholder rows, without turning them into invented products.

| Account | Organic | Ads | Reviews | Rating |
|---|---:|---:|---:|---:|
| `judios` | 270 | 113 | 2,550 | 2,550 |
| `koppelfbe` | 414 | 343 | 1,641 | 1,641 |
| `sellfusion` | 647 | 340 | 1,976 | 1,976 |
| `zoopieconcept` | 2 | 0 | 1,950 | 1,950 |
| `zoopiesolutions` | 1,041 | 700 | 11,135 | 11,135 |

Overall observed coverage is 1 May–6 November 2026 for Organic, 1 May–30 October 2026 for Ads,
and 29 December 2023–6 November 2026 for family reviews/ratings. Coverage varies by product and
metric; those overall bounds do not imply every product has every observation.

The reviewed content hash is
`63e430fdd582c6dfdc10f232e74ef4bba5e0e8ebdae72e1c69be2e6f19eeb505`.
It was applied to the local database as batch `11494e51-320b-40f1-a4f2-bda4cd4fc81c`.
This is a local import record, not evidence of deployment or import on the production server.

## Verified production import on 6 November 2026

Merging application code does not transfer imported database history. The read-only production
check found schema version 49 installed on `emagRemote` and no historical observations, while the
earlier local import contained 42,374 valued observations.

A v2 dry run against `emagRemote`, using the four existing captured source reports and
`--through=2026-11-06`, completed with 61,620 normalized observations: 42,374 with metric values
and 19,246 explicitly empty dated snapshots. The valued observations cover 157 products.
Diagnostics concern catalogue entries without identifiers and the `KOPPELNOPNK` placeholders;
they do not introduce invented product identities.

The prepared production dry-run hash is
`7c6d98560143eca5ef7d2a6cb28a6c0fab44e1731e09d84e8580b86d865d8e6d`.
Apply must reuse those same four capture files, target alias and cutoff, with
`--db=emagRemote --through=2026-11-06 --allow-remote --apply
--expected-hash=7c6d98560143eca5ef7d2a6cb28a6c0fab44e1731e09d84e8580b86d865d8e6d`.
The import was applied to `emagRemote` on 6 November 2026 at 20:45 Romania time as batch
`47215489-c0bf-4f4f-898b-6b1afc3a0208`, with all 61,620 observations committed in one transaction.
Schema version remained 49 and the apply report records `schemaMigrated=false`.

Read-only checks through the actual Product Performance API verified a product in each of
`judios`, `koppelfbe`, `sellfusion`, `zoopieconcept`, and `zoopiesolutions`, in both 14-day and
30-day modes. Every checked report returned historical Clicks with `estimated` provenance,
which the existing interface renders as yellow cells. Production live visits captures were also
confirmed for Sellfusion and Zoopie Solutions on 6 November; the current day's captures become
eligible for provisional previews the following day.
