import { fetchJSON } from "./common.js";

const selectEl = document.getElementById("productSelect");
const rrrCanvasEl = document.getElementById("rrrChart");
const metaEl = document.getElementById("rrrMeta");
const dateFromEl = document.getElementById("dateFromInput");
const dateToEl = document.getElementById("dateToInput");
const resetRangeEl = document.getElementById("dateRangeResetBtn");

const pctFmt = new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 1 });
const intFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const numFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

const COUNT_SERIES = [
  {
    key: "orders",
    canvasId: "ordersChart",
    title: "Orders",
    yLabel: "Orders",
    endpoint: (id) => `/app/orders/${encodeURIComponent(id)}`,
    getY: (row) => Number(row.count ?? 0),
    tooltipLabel: (y) => ` Orders: ${intFmt.format(y)}`
  },
  {
    key: "returns",
    canvasId: "returnChart",
    title: "Returns",
    yLabel: "Returns",
    endpoint: (id) => `/app/returns/${encodeURIComponent(id)}`,
    getY: (row) => Number(row.count ?? 0),
    tooltipLabel: (y) => ` Returns: ${intFmt.format(y)}`
  }
];

const charts = new Map();
let currentProductId = null;
let currentData = null;
let latestLoadSeq = 0;

async function populateProducts() {
  const items = await fetchJSON("/app/products");
  //items.sort((a, b) => (a.name || "").localeCompare(b.name || ""));

  selectEl.innerHTML = "";
  const placeholder = document.createElement("option");
  placeholder.textContent = "Select a product...";
  placeholder.value = "";
  placeholder.disabled = true;
  placeholder.selected = true;
  selectEl.appendChild(placeholder);

  for (const product of items) {
    const option = document.createElement("option");
    option.value = product.id;
    option.textContent = product.name || product.id;
    selectEl.appendChild(option);
  }

  selectEl.disabled = false;
}

function parseDateFromInput(value) {
  if (!value) return null;
  const [y, m, d] = value.split("-").map(Number);
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) return null;
  const date = new Date(y, m - 1, d);
  return Number.isNaN(date.getTime()) ? null : date;
}

function getUserRange() {
  let min = parseDateFromInput(dateFromEl?.value ?? "");
  let max = parseDateFromInput(dateToEl?.value ?? "");
  if (min && max && min > max) {
    const tmp = min;
    min = max;
    max = tmp;
  }
  return { min, max };
}

function seriesBounds(points) {
  if (!points || points.length === 0) return null;
  return {
    min: points[0].x,
    max: points[points.length - 1].x
  };
}

function computeAutoSharedRange(smoothedPoints, countPointsByKey) {
  const ranges = [seriesBounds(smoothedPoints), ...countPointsByKey.map(seriesBounds)].filter(Boolean);
  if (!ranges.length) return { min: null, max: null };

  const mins = ranges.map((r) => r.min.getTime());
  const maxs = ranges.map((r) => r.max.getTime());
  const overlapMin = Math.max(...mins);
  const overlapMax = Math.min(...maxs);

  if (overlapMin <= overlapMax) {
    return { min: new Date(overlapMin), max: new Date(overlapMax) };
  }

  return { min: new Date(Math.min(...mins)), max: new Date(Math.max(...maxs)) };
}

function resolveRange(smoothedPoints, countPointsByKey) {
  const userRange = getUserRange();
  const autoRange = computeAutoSharedRange(smoothedPoints, countPointsByKey);
  let min = userRange.min ?? autoRange.min;
  let max = userRange.max ?? autoRange.max;
  if (min && max && min > max) {
    const tmp = min;
    min = max;
    max = tmp;
  }
  return { min, max };
}

function toSmoothedPoints(rows) {
  const points = [];
  for (const row of rows ?? []) {
    const arr = row.date;
    if (!Array.isArray(arr) || arr.length < 3) continue;

    const date = new Date(arr[0], arr[1] - 1, arr[2]);
    if (isNaN(date.getTime())) continue;

    points.push({
      x: date,
      y: Number(row.smoothedRate ?? 0),
      rawRate: row.rawRate == null ? null : Number(row.rawRate),
      soldQty: Number(row.soldQty ?? 0),
      returnedQty: Number(row.returnedQty ?? 0),
      reliable: Boolean(row.reliable)
    });
  }
  return points;
}

function toCountPoints(rows, def) {
  const points = [];
  for (const row of rows ?? []) {
    const arr = row.date;
    if (!Array.isArray(arr) || arr.length < 3) continue;
    const date = new Date(arr[0], arr[1] - 1, arr[2]);
    if (isNaN(date.getTime())) continue;
    points.push({ x: date, y: def.getY(row) });
  }
  return points;
}

function renderSmoothedChart(points, productId, range) {
  const tracked = charts.get("rrr-smoothed");
  if (tracked) tracked.destroy();

  const previous = Chart.getChart(rrrCanvasEl);
  if (previous) previous.destroy();

  const rawPoints = points.map((pt) => ({
    x: pt.x,
    y: pt.rawRate,
    soldQty: pt.soldQty,
    returnedQty: pt.returnedQty
  }));

  const chart = new Chart(rrrCanvasEl.getContext("2d"), {
    type: "line",
    data: {
      datasets: [
        {
          label: `Smoothed cohort RRR (${productId})`,
          data: points,
          parsing: false,
          tension: 0.25,
          fill: true,
          pointRadius: (ctx) => (ctx.raw?.reliable ? 2 : 0),
          pointHoverRadius: (ctx) => (ctx.raw?.reliable ? 3 : 2)
        },
        {
          label: "Raw cohort RRR",
          data: rawPoints,
          parsing: false,
          borderDash: [5, 3],
          fill: false,
          pointRadius: 0,
          pointHoverRadius: 0,
          tension: 0.2,
          hidden: true
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      scales: {
        x: {
          type: "time",
          time: {
            unit: "month",
            displayFormats: { month: "yyyy-MM" },
            tooltipFormat: "yyyy-MM-dd"
          },
          min: range.min ?? (points.length ? points[0].x : undefined),
          max: range.max ?? (points.length ? points[points.length - 1].x : undefined),
          bounds: "ticks",
          ticks: { source: "auto" }
        },
        y: {
          title: { display: true, text: "Return rate" },
          beginAtZero: true,
          ticks: {
            callback: (value) => pctFmt.format(value)
          }
        }
      },
      plugins: {
        legend: { display: true },
        tooltip: {
          callbacks: {
            title: (items) =>
              items?.[0]?.parsed?.x ? new Date(items[0].parsed.x).toISOString().slice(0, 10) : "",
            label: (ctx) => {
              const value = ctx.parsed?.y;
              const soldQty = Number(ctx.raw?.soldQty ?? 0);
              const returnedQty = Number(ctx.raw?.returnedQty ?? 0);
              if (ctx.datasetIndex === 0) {
                const rawRate = ctx.raw?.rawRate;
                const rawTxt = rawRate == null ? "n/a" : pctFmt.format(rawRate);
                return ` Smoothed: ${pctFmt.format(value)} | Raw: ${rawTxt} | S_d: ${intFmt.format(soldQty)} | Y_d: ${intFmt.format(returnedQty)}`;
              }
              return ` Raw: ${value == null ? "n/a" : pctFmt.format(value)} | S_d: ${intFmt.format(soldQty)} | Y_d: ${intFmt.format(returnedQty)}`;
            }
          }
        }
      }
    }
  });

  charts.set("rrr-smoothed", chart);
}

function renderCountChart(def, points, productId, range) {
  const tracked = charts.get(def.key);
  if (tracked) tracked.destroy();

  const canvasEl = document.getElementById(def.canvasId);
  const previous = Chart.getChart(canvasEl);
  if (previous) previous.destroy();

  const chart = new Chart(canvasEl.getContext("2d"), {
    type: "line",
    data: {
      datasets: [{
        label: `${def.title} by Day (${productId})`,
        data: points,
        parsing: false,
        fill: true,
        tension: 0.25,
        pointRadius: 2
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: "index", intersect: false },
      scales: {
        x: {
          type: "time",
          time: {
            unit: "month",
            displayFormats: { month: "yyyy-MM" },
            tooltipFormat: "yyyy-MM-dd"
          },
          min: range.min ?? (points.length ? points[0].x : undefined),
          max: range.max ?? (points.length ? points[points.length - 1].x : undefined),
          bounds: "ticks",
          ticks: { source: "auto" }
        },
        y: {
          title: { display: true, text: def.yLabel },
          beginAtZero: true,
          ticks: {
            stepSize: 1,
            callback: (value) => (Number.isInteger(value) ? intFmt.format(value) : null)
          }
        }
      },
      plugins: {
        legend: { display: true },
        tooltip: {
          callbacks: {
            title: (items) =>
              items?.[0]?.parsed?.x ? new Date(items[0].parsed.x).toISOString().slice(0, 10) : "",
            label: (ctx) => (def.tooltipLabel
              ? def.tooltipLabel(ctx.parsed.y ?? 0)
              : ` ${def.title}: ${numFmt.format(ctx.parsed.y ?? 0)}`)
          }
        }
      }
    }
  });

  charts.set(def.key, chart);
}

async function loadProductData(productId) {
  const smoothedPromise = fetchJSON(`/app/rrr-smoothed/${encodeURIComponent(productId)}`)
    .then((rows) => ({ ok: true, rows }))
    .catch((err) => {
      console.error("Failed to load smoothed RRR:", err);
      return { ok: false, rows: [] };
    });

  const countPromises = COUNT_SERIES.map((def) =>
    fetchJSON(def.endpoint(productId))
      .then((rows) => ({ key: def.key, rows }))
      .catch((err) => {
        console.error(`Failed to load ${def.key}:`, err);
        return { key: def.key, rows: [] };
      })
  );

  const smoothedResult = await smoothedPromise;
  const countResults = await Promise.all(countPromises);
  const countsByKey = new Map(countResults.map((it) => [it.key, it.rows]));

  return {
    smoothedOk: smoothedResult.ok,
    smoothedRows: smoothedResult.rows,
    countsByKey
  };
}

function renderCurrent() {
  if (!currentProductId || !currentData) return;

  const smoothedPoints = toSmoothedPoints(currentData.smoothedRows);
  const countPointsByKey = COUNT_SERIES.map((def) =>
    toCountPoints(currentData.countsByKey.get(def.key), def)
  );
  const range = resolveRange(smoothedPoints, countPointsByKey);

  renderSmoothedChart(smoothedPoints, currentProductId, range);
  COUNT_SERIES.forEach((def, index) => {
    renderCountChart(def, countPointsByKey[index], currentProductId, range);
  });

  if (!currentData.smoothedOk) {
    metaEl.textContent = "Failed to load data for this product.";
  } else if (!smoothedPoints.length) {
    metaEl.textContent = "No data available for this product.";
  } else {
    metaEl.textContent = "Cohort-linked 90-day rolling return rate with Bayesian smoothing (m = 100). Markers are shown when S_d >= 20.";
  }
}

async function onProductChange() {
  const productId = selectEl.value;
  if (!productId) return;

  const seq = ++latestLoadSeq;
  const data = await loadProductData(productId);
  if (seq !== latestLoadSeq) return;

  currentProductId = productId;
  currentData = data;
  renderCurrent();
}

function onRangeChange() {
  renderCurrent();
}

document.addEventListener("DOMContentLoaded", async () => {
  try {
    await populateProducts();
    selectEl.addEventListener("change", onProductChange);
    dateFromEl.addEventListener("change", onRangeChange);
    dateToEl.addEventListener("change", onRangeChange);
    resetRangeEl.addEventListener("click", () => {
      dateFromEl.value = "";
      dateToEl.value = "";
      onRangeChange();
    });
  } catch (err) {
    console.error("Failed to populate products:", err);
    selectEl.innerHTML = "<option>Failed to load products</option>";
    selectEl.disabled = true;
    metaEl.textContent = "Failed to initialize the page.";
  }
});
