# Rolling Return Rate computation

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
