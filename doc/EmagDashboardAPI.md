# eMAG dashboard API observations

This documents the dashboard source used by `FetchOffers`, separate from the official
Marketplace API specification in `eMAG-API-Specification.json`. The provided OpenAPI
v4.5.2 specification does not describe these product visit counters. The dashboard
schema and authenticated responses were inspected during the November 2026 investigation.

## Product visits

- Endpoint: `POST https://marketplace.emag.ro/global-listing`
- Operation: GraphQL `offers`, with `OfferFilterInput!`.
- Authentication: the existing Playwright seller dashboard session, including its login
  and two-factor authentication. Official Marketplace API credentials are not used here.
- Headers: `Content-Type: application/json`, `Accept: application/json, text/plain, */*`,
  and `X-Requested-With: XMLHttpRequest`.

The relevant portion of the query is:

```graphql
query offers($filters: OfferFilterInput!) {
  offers(filters: $filters) {
    items {
      id
      extStock
      productPerformanceVisits
      productPerformanceVisitsLast14
    }
    totalNumberOfItems
  }
}
```

The production collector requests these fields alongside the existing complete offer
record and its product PNK. Its variables use `platformType: "ROMANIA"`, pagination
with `page` and `limit: 25`, and ascending `extId` sorting.

| Field | Meaning used in Product Performance |
|---|---|
| `productPerformanceVisits` | Visits in the rolling last 30 days |
| `productPerformanceVisitsLast14` | Visits in the rolling last 14 days |

These are product visits as labelled by the seller dashboard, not advertising clicks.
The inspected schema exposes no date arguments for these fields or applicable historical
date filters. No daily product visits source was verified. A stored daily observation
preserves what the dashboard reported then; it does not establish how many visits occurred
on that calendar day. Neither sums nor differences of these observations produce reliable
weekly or calendar-month totals.

## Conversion and hotness investigation — 2026-11-06

Authenticated read-only queries of `global-listing` confirmed these additional fields:

| Source | Field | Verified scope |
|---|---|---|
| `offers.items` | `productPerformanceConversion` | `Float!`, no field arguments; returns numeric values, but its source window was not confirmed |
| `performanceByProducts.productList.items` | `conversionRate` | Used by the Analytics Product Performance page for its current last-30-days view |
| `offers.items.offerStock` | `hotnessType`, `hotnessName` | eMAG performance classification, already collected and stored |

The inspected `PerformanceByProductsFilterInput` supports identifier, category, brand,
sorting and pagination, but exposes no date or window argument. The captured Analytics
request likewise supplied no dates. No separate 14-day product conversion field or
date-selectable conversion query was verified. These findings describe the inspected
schema, not a guarantee that no other eMAG endpoint exists. The local OpenAPI v4.5.2
specification does not document these dashboard conversion fields.

Product Performance deliberately calculates its own **Conv. = Orders units / Clicks**
for both 14-day and 30-day rows, using the exact selected account and existing report
inputs. The API returns a fraction and the UI/CSV display a percentage. Neither native
conversion field is added to the collector or database, and Ads conversion remains a
separate calculation. See [Product performance calculations](ProductPerformance.md)
for missing-data and zero-denominator behavior.

The existing offer query already includes:

```graphql
offerStock {
  hotnessType
  hotnessName
}
```

Live responses included `super_hot` / `Super Hot`, `regular` / `Regular`, `cold` / `Cold`
and `super_cold` / `Super Cold`. The legacy offers page labels this classification as
30-day performance. The report displays the stored classification observed on the exact
closing date of each interval, including 14-day rows; it does not calculate a new 14-day
hotness or assign current classifications to historical rows. Values are already persisted
in `offers_stock.hotness_type` and `hotness_name`, so no migration or new collection is
needed. Older hotness observations do not require the newer visits capture timestamp.

## Collection and validation

`Fetch offers for <account>` already runs hourly in the account's serialized dashboard
lane. Both visit fields are now requested on every page. A response that omits a requested
field is an error; an explicitly null value remains unknown. HTTP/GraphQL errors,
incomplete pagination and duplicate offer identities fail the collection. All pages must
pass validation before the database transaction replaces the day's snapshot.

Counters are nullable `BIGINT` columns in `offers_offer`. `offers_snapshot.visits_fetched_at`
records fetch completion; its Europe/Bucharest date determines the daily snapshot key.
Only the live collection path supplies this timestamp. Imports and pre-existing snapshots
are not reported as newly collected visits. No Excel upload is needed.

This dashboard interface can change independently of the official API. Validation failures
remain visible in [Task history](TaskHistory.md), and the previous successful snapshot stays
available. No seller credentials, session cookies or tokens belong in this document.
