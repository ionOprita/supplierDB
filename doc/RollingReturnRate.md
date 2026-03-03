# Rolling Return Rate computation

## Materialized Views

`EmagMirrorDBVersion31` builds three materialized views that precompute the expensive joins needed by `getCohortSmoothedRollingReturnRate`.

The views are designed as a pipeline:
- `orders_canonical`: normalize orders to one canonical row per `(order_id, vendor_id)`.
- `sales_daily`: pre-aggregate sold quantities by product and sale day (denominator input).
- `returns_linked`: pre-link return quantities to both sale day and return day (numerator input).

### 1. `orders_canonical`

```sql
CREATE MATERIALIZED VIEW orders_canonical AS
SELECT DISTINCT ON (eo.id, eo.vendor_id)
  eo.id           AS order_id,
  eo.surrogate_id AS order_surrogate_id,
  eo.date         AS order_ts,
  eo.vendor_id    AS vendor_id,
  eo.status       AS status
FROM emag_order eo
ORDER BY eo.id, eo.vendor_id, eo.status DESC;
```

Role:
- Deduplicates `emag_order` into one canonical row per `(order_id, vendor_id)`.
- Uses `DISTINCT ON` with `ORDER BY ... status DESC`, so if multiple rows exist for the same order/vendor pair, the row with the highest status is kept.

Output columns:
- `order_id`: natural order identifier used by RMA data.
- `order_surrogate_id`: surrogate key used by `product_in_order`.
- `order_ts`: original order timestamp.
- `vendor_id`: vendor scope.
- `status`: chosen canonical status.

Indexes:
- `(order_id, vendor_id)` for lookups by natural order identity.
- `(order_surrogate_id)` for joins from `product_in_order`.
- Expression index on `(order_ts::date)` for date-based access.

Why it exists:
- `product_in_order` links via `emag_order_surrogate_id`, while returns data links via natural `order_id`.
- This view provides a stable bridge between those two identities without recomputing deduplication at query time.

### 2. `sales_daily`

```sql
CREATE MATERIALIZED VIEW sales_daily AS
SELECT
  p.product_code as product_code,
  pio.product_id AS product_id,
  (oc.order_ts::date) AS sale_d,
  SUM(pio.quantity)::bigint AS sold_qty
FROM product_in_order pio
JOIN product p
  ON p.emag_pnk = pio.part_number_key
JOIN orders_canonical oc
  ON oc.order_surrogate_id = pio.emag_order_surrogate_id
WHERE oc.status IN (4,5)
GROUP BY 1,2,3;
```

Role:
- Produces daily sold quantities used as the rolling denominator.

Grain:
- One row per `(product_code, product_id, sale_d)`.

Join/filter logic:
- Joins line items (`product_in_order`) to product metadata (`product`) using `part_number_key -> emag_pnk`.
- Joins to canonicalized orders via surrogate id to get sale timestamp and final status.
- Keeps only orders with status in `(4,5)` (the statuses treated as valid completed sales for this metric).

Output column:
- `sold_qty`: total sold quantity for that product on that sale date.

Index:
- `(product_id, sale_d)` to support date-series scans by product.

Why it exists:
- Avoids repeatedly joining raw line items and orders in analytical queries.
- Stores the already-aggregated daily denominator, which dramatically reduces runtime work.

### 3. `returns_linked`

```sql
CREATE MATERIALIZED VIEW returns_linked AS
SELECT
  erp.product_id,
  p.product_code,
  (oc.order_ts::date) AS sale_d,
  (rr.date::date)     AS return_d,
  SUM(erp.quantity)::bigint AS returned_qty
FROM emag_returned_products erp
JOIN rma_result rr
  ON rr.emag_id = erp.emag_id
 AND rr.request_status = 7
JOIN orders_canonical oc
  ON oc.order_id = rr.order_id
JOIN product_in_order pio
  ON pio.emag_order_surrogate_id = oc.order_surrogate_id
 AND pio.product_id = erp.product_id
 AND pio.mkt_id = erp.product_emag_id
JOIN product p
 ON p.emag_pnk = pio.part_number_key
GROUP BY 1,2,3,4;
```

Role:
- Produces return quantities already linked back to the originating sale cohort.

Grain:
- One row per `(product_id, product_code, sale_d, return_d)`.

Join/filter logic:
- Starts from returned items (`emag_returned_products`).
- Joins to RMA records by `emag_id` and keeps only `request_status = 7` (finalized/accepted return records for this metric).
- Joins to `orders_canonical` on natural `order_id` to recover the original sale timestamp (`sale_d`).
- Joins to `product_in_order` with three keys:
  - same order surrogate id,
  - same internal `product_id`,
  - same marketplace product id (`mkt_id = product_emag_id`).
- This triple match ensures return quantities are linked to the correct sold line item.

Output column:
- `returned_qty`: quantity returned for that product, for that specific sale date and return date.

Index:
- `(product_id, sale_d, return_d)` to support cohort-window filtering by product and date.

Why it exists:
- The rolling algorithm needs both `sale_d` and `return_d` per returned quantity to enforce:
  - maximum return delay,
  - rolling cohort horizon.
- Pre-linking returns to sale cohorts eliminates expensive runtime reconciliation across multiple raw tables.

### Practical Impact On Query Runtime

- `getCohortSmoothedRollingReturnRate` reads only `sales_daily` and `returns_linked`, not raw order/line-item/RMA tables.
- Most heavy joins, deduplication, and aggregation are paid once during materialized-view build/refresh.
- Runtime SQL can then focus on rolling-window math (delta events + cumulative sums), which is much cheaper.



## SQL statement in `getCohortSmoothedRollingReturnRate`

The method computes a per-day rolling return rate for one `product_code`, then applies Bayesian smoothing so low-volume days are less noisy.

The SQL is built as a chain of CTEs. Each CTE has one clear role.

### 1. `params`

```sql
WITH params AS (
  SELECT
    ?::int AS roll_days,
    ?::int AS max_return_days,
    ?::numeric AS m,
    ?::varchar AS product_code
)
```

Role:
- Captures runtime inputs in one row:
  - `roll_days`: denominator window length.
  - `max_return_days`: maximum allowed delay between sale and return.
  - `m`: Bayesian prior strength.
  - `product_code`: product to compute for.
- Makes these values reusable in later CTEs without repeating placeholders.

### 2. `sales_filtered`

```sql
sales_filtered AS (
  SELECT
    sd.sale_d,
    sd.sold_qty
  FROM sales_daily sd
  CROSS JOIN params p
  WHERE sd.product_code = p.product_code
)
```

Role:
- Reads pre-aggregated daily sales from `sales_daily`.
- Keeps only rows for the selected `product_code`.
- Output is one stream of `(sale_date, sold_qty)`.

### 3. `returns_filtered`

```sql
returns_filtered AS (
  SELECT
    rl.sale_d,
    rl.return_d,
    rl.returned_qty
  FROM returns_linked rl
  CROSS JOIN params p
  WHERE rl.product_code = p.product_code
    AND rl.return_d <= rl.sale_d + p.max_return_days
    AND rl.return_d <= rl.sale_d + (p.roll_days - 1)
)
```

Role:
- Reads pre-linked returns from `returns_linked` for the same `product_code`.
- Applies two eligibility rules for return events:
  - Return must happen within `max_return_days` after sale.
  - Return must also fit inside the rolling horizon (`roll_days - 1`) for that sale cohort.
- Output rows carry both `sale_d` and `return_d`, needed later for event math.

### 4. `all_dates`

```sql
all_dates AS (
  SELECT sf.sale_d AS d FROM sales_filtered sf
  UNION ALL
  SELECT rf.sale_d AS d FROM returns_filtered rf
  UNION ALL
  SELECT rf.return_d AS d FROM returns_filtered rf
)
```

Role:
- Collects all relevant dates touched by sales and returns.
- Includes both sale dates and return dates.
- Provides the timeline bounds for the daily calendar.

### 5. `bounds`

```sql
bounds AS (
  SELECT MIN(d) AS min_d, MAX(d) AS max_d
  FROM all_dates
)
```

Role:
- Computes the first and last date that matter for this product and filters.

### 6. `calendar`

```sql
calendar AS (
  SELECT
    gs::date AS d
  FROM bounds b
  CROSS JOIN LATERAL generate_series(b.min_d, b.max_d, interval '1 day') gs
  WHERE b.min_d IS NOT NULL
    AND b.max_d IS NOT NULL
)
```

Role:
- Generates one row per day from `min_d` to `max_d`.
- Ensures output has a continuous daily series, including days with no activity.

### 7. `sales_events`

```sql
sales_events AS (
  SELECT sf.sale_d AS d, sf.sold_qty AS delta
  FROM sales_filtered sf
  UNION ALL
  SELECT
    (sf.sale_d + p.roll_days)::date AS d,
    -sf.sold_qty AS delta
  FROM sales_filtered sf
  CROSS JOIN params p
)
```

Role:
- Converts each sale cohort into two delta events:
  - `+sold_qty` on `sale_d` (cohort enters rolling denominator).
  - `-sold_qty` on `sale_d + roll_days` (cohort leaves window).
- This is an interval-to-delta transform so rolling sums can be computed with a cumulative window (fast and scalable).

### 8. `sales_deltas`

```sql
sales_deltas AS (
  SELECT d, SUM(delta)::bigint AS delta
  FROM sales_events
  GROUP BY d
)
```

Role:
- Collapses multiple same-day sales delta events into one daily net delta.

### 9. `returns_events`

```sql
returns_events AS (
  SELECT rf.return_d AS d, rf.returned_qty AS delta
  FROM returns_filtered rf
  UNION ALL
  SELECT
    (rf.sale_d + p.roll_days)::date AS d,
    -rf.returned_qty AS delta
  FROM returns_filtered rf
  CROSS JOIN params p
)
```

Role:
- Same delta technique for eligible returns:
  - `+returned_qty` on `return_d` (return counts in numerator when it happens).
  - `-returned_qty` on `sale_d + roll_days` (drop that sale cohort’s return contribution when cohort exits denominator window).
- Keeps numerator aligned with the same rolling cohort logic used by the denominator.

### 10. `returns_deltas`

```sql
returns_deltas AS (
  SELECT d, SUM(delta)::bigint AS delta
  FROM returns_events
  GROUP BY d
)
```

Role:
- Collapses return deltas to one net delta per day.

### 11. `series`

```sql
series AS (
  SELECT
    c.d,
    SUM(COALESCE(sd.delta, 0)) OVER (
      ORDER BY c.d
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    )::bigint AS sold_qty,
    SUM(COALESCE(rd.delta, 0)) OVER (
      ORDER BY c.d
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    )::bigint AS returned_qty
  FROM calendar c
  LEFT JOIN sales_deltas sd
    ON sd.d = c.d
  LEFT JOIN returns_deltas rd
    ON rd.d = c.d
)
```

Role:
- Joins the daily calendar with daily deltas.
- Uses cumulative sums to reconstruct the active rolling totals for each day:
  - `sold_qty`: rolling denominator.
  - `returned_qty`: rolling numerator.

This is the core time series used for chart points.

### 12. `totals`

```sql
totals AS (
  SELECT
    COALESCE(SUM(returned_qty)::numeric / NULLIF(SUM(sold_qty), 0), 0)::numeric AS p0
  FROM series
)
```

Role:
- Computes the global baseline rate `p0` across the full computed series.
- `p0` is the prior mean used for Bayesian smoothing.

### 13. Final `SELECT`

```sql
SELECT
  s.d AS event_date,
  s.sold_qty,
  s.returned_qty,
  (s.returned_qty::double precision / NULLIF(s.sold_qty::double precision, 0)) AS raw_rate,
  (
    (s.returned_qty::numeric + (t.p0 * p.m))
    / (s.sold_qty::numeric + p.m)
  )::double precision AS smoothed_rate,
  (s.sold_qty >= ?) AS reliable
FROM series s
CROSS JOIN totals t
CROSS JOIN params p
ORDER BY s.d;
```

Role:
- Emits one chart point per day:
  - `event_date`: date.
  - `sold_qty`: rolling sold quantity.
  - `returned_qty`: rolling returned quantity.
  - `raw_rate = returned_qty / sold_qty`.
  - `smoothed_rate` uses Bayesian shrinkage:
    - Formula: `(returned_qty + p0 * m) / (sold_qty + m)`.
    - When `sold_qty` is small, result stays closer to baseline `p0`.
    - When `sold_qty` is large, result approaches `raw_rate`.
  - `reliable`: boolean flag based on `sold_qty >= reliableMinSoldQty`.

### Why this design

- Uses materialized views to avoid expensive runtime joins.
- Uses delta events + cumulative sums instead of repeated rolling-window scans.
- Produces a complete day-by-day series with a reliability indicator and a smoothed metric suitable for visualization.
