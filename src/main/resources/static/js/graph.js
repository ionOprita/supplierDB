// public/js/graph.js
// Assumes Chart.js UMD is loaded first (global Chart)
// Uses the existing DOM structure/IDs from index.html

import {fetchJSON} from "./common.js";

const selectEl = document.getElementById("productSelect");

// -------- Per-series configuration (new) --------
const pctFmt = new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 1 });
const intFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const numFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

// Define the series in one place to avoid duplicate code
const SERIES = [
  {
    key: "rrr",
    canvasId: "rrrChart",
    title: "Rolling Return Rate (90 days)",
    yLabel: "RRR",
    endpoint: (id) => `/app/rrr/${encodeURIComponent(id)}`,
    // RRR API shape: { date:[y,m,d], value:number in [0..1] }
    getY: (row) => Number(row.value ?? 0),
    yTicks: {
      stepSize: undefined, // let Chart.js choose (can set to 0.05 if you want fixed 5% steps)
      callback: (v) => pctFmt.format(v)
    },
    tooltipLabel: (y) => ` RRR: ${pctFmt.format(y)}`,
    yBeginAtZero: true,
    // ySuggestedMax: 1 // optional; comment out if you don’t want to cap around 100%
  },
  {
    key: "orders",
    canvasId: "ordersChart",
    title: "Orders",
    yLabel: "Orders",
    endpoint: (id) => `/app/orders/${encodeURIComponent(id)}`,
    // Integer counts: { date:[y,m,d], count:int }
    getY: (row) => Number(row.count ?? 0),
    yTicks: {
      stepSize: 1,
      callback: (v) => (Number.isInteger(v) ? intFmt.format(v) : null)
    },
    tooltipLabel: (y) => ` Orders: ${intFmt.format(y)}`,
    yBeginAtZero: true
  },
  {
    key: "stornos",
    canvasId: "stornoChart",
    title: "Stornos",
    yLabel: "Stornos",
    endpoint: (id) => `/app/stornos/${encodeURIComponent(id)}`,
    getY: (row) => Number(row.count ?? 0),
    yTicks: {
      stepSize: 1,
      callback: (v) => (Number.isInteger(v) ? intFmt.format(v) : null)
    },
    tooltipLabel: (y) => ` Stornos: ${intFmt.format(y)}`,
    yBeginAtZero: true
  },
  {
    key: "returns",
    canvasId: "returnChart",
    title: "Returns",
    yLabel: "Returns",
    endpoint: (id) => `/app/returns/${encodeURIComponent(id)}`,
    getY: (row) => Number(row.count ?? 0),
    yTicks: {
      stepSize: 1,
      callback: (v) => (Number.isInteger(v) ? intFmt.format(v) : null)
    },
    tooltipLabel: (y) => ` Returns: ${intFmt.format(y)}`,
    yBeginAtZero: true
  }
];

// Hold active Chart instances so we can destroy/recreate cleanly
const charts = new Map();
const numberFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });

/** Build (or rebuild) the product dropdown **/
async function populateProducts() {
  const items = await fetchJSON("/products"); // [{name, id}, ...]
  items.sort((a, b) => (a.name || "").localeCompare(b.name || ""));

  // Clear + placeholder
  selectEl.innerHTML = "";
  const ph = document.createElement("option");
  ph.textContent = "Select a product…";
  ph.value = "";
  ph.disabled = true;
  ph.selected = true;
  selectEl.appendChild(ph);

  // Options
  for (const p of items) {
    const opt = document.createElement("option");
    opt.value = p.id;
    opt.textContent = p.name || p.id;
    selectEl.appendChild(opt);
  }
  selectEl.disabled = false;
}

/** Map API rows -> {x: Date, y: number} using per-series accessor **/
function toPoints(series, def) {
  const pts = [];
  for (const row of series ?? []) {
    // date is [yyyy, mm, dd] (mm is 1-based)
    const d = new Date(row.date[0], row.date[1] - 1, row.date[2]);
    if (!isNaN(d.getTime())) {
      pts.push({ x: d, y: def.getY(row) });
    }
  }
  return pts;
}

/** Create or replace a chart in a canvas **/
function renderLineChart(def, points, productId) {
  // 1) Destroy the chart we track (by key)
  const tracked = charts.get(def.key);
  if (tracked) tracked.destroy();

  // 2) Destroy any chart already registered on this canvas (defensive)
  const canvasEl = document.getElementById(def.canvasId);
  const prevOnCanvas = Chart.getChart(canvasEl);
  if (prevOnCanvas) prevOnCanvas.destroy();
  const ctx = canvasEl.getContext("2d");

  const chart = new Chart(ctx, {
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
      maintainAspectRatio: false, // honor CSS height (vh)
      interaction: { mode: "index", intersect: false },
      scales: {
        x: {
          type: "time",                 // or "timeseries"
          time: {
            unit: "month",              // monthly ticks
            displayFormats: { month: "yyyy-MM" }, // date-fns tokens
            tooltipFormat: "yyyy-MM-dd" // tooltip detail
          },
          // Ensure the full span of your data is visible
          min: points.length ? points[0].x : undefined,
          max: points.length ? points[points.length - 1].x : undefined,
          bounds: "ticks",
          // Ensures tick positions land on months; 'auto' is fine, but 'data' also works
          ticks: { source: "auto" }
        },
        y: {
          title: { display: true, text: def.yLabel },
          beginAtZero: def.yBeginAtZero ?? true,
          ...(def.ySuggestedMax != null ? { suggestedMax: def.ySuggestedMax } : {}),
          ticks: {
            stepSize: def.yTicks?.stepSize,
            callback: def.yTicks?.callback ?? ((v) => numFmt.format(v))
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

/** Load one series + render **/
async function loadAndRender(def, productId) {
  try {
    const series = await fetchJSON(def.endpoint(productId));
    const points = toPoints(series, def);
    if (!points.length) {
      console.warn(`[${def.key}] no points from API`, { productId, seriesSample: series?.slice?.(0, 3) });
    }
    renderLineChart(def, points, productId);
  } catch (err) {
    // Render an empty chart as a visible fallback
    renderLineChart(def, [], productId);
    // Log error to console for debugging
    console.error(`Failed to load ${def.key}:`, err);
  }
}

/** When the product changes, (re)draw all charts **/
function onProductChange() {
  const productId = selectEl.value;
  if (!productId) return;
  SERIES.forEach((def) => loadAndRender(def, productId));
}

/** Bootstrap **/
document.addEventListener("DOMContentLoaded", async () => {
  try {
    await populateProducts();
    selectEl.addEventListener("change", onProductChange);
  } catch (e) {
    console.error("Failed to populate products:", e);
    selectEl.innerHTML = '<option>Failed to load products</option>';
    selectEl.disabled = true;
  }
});