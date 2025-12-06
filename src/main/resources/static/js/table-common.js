/**
 * table-common.js
 * Shared utilities to render a month-matrix table with a sticky header and first two columns,
 * plus a delegated click handler that opens a details window.
 */
import {fetchJSON, formatDuration, formatLocalDateTime} from "./common.js";

// --- helpers -------------------------------------------------------------

export function parseKey(key) {
  const pnkMatch = key.match(/pnk=([^,\]]+)/);
  const nameMatch = key.match(/name=([^,\]]+)/);
  return {
    pnk: pnkMatch ? pnkMatch[1].trim() : '',
    name: nameMatch ? nameMatch[1].trim() : key
  };
}

export function toRows(jsonMap) {
  return Object.entries(jsonMap).map(([key, monthsObj]) => {
    const { pnk, name } = parseKey(key);
    return { key, pnk, name, months: monthsObj };
  });
}

export function collectAllMonths(rows) {
  const set = new Set();
  for (const row of rows) {
    for (const m of Object.keys(row.months)) set.add(m);
  }
  return [...set].sort();
}

// --- small shared DOM builders ------------------------------------------

function buildHeaderRow(labels) {
  const tr = document.createElement('tr');
  for (const label of labels) {
    const th = document.createElement('th');
    th.textContent = label;
    tr.appendChild(th);
  }
  return tr;
}

function renderTbody(tbodyEl, items, renderRowFn) {
  tbodyEl.innerHTML = '';
  const frag = document.createDocumentFragment();
  for (const item of items) {
    frag.appendChild(renderRowFn(item));
  }
  tbodyEl.appendChild(frag);
}

// --- rendering -----------------------------------------------------------

export function renderHeader(headEl, months) {
  const tr = buildHeaderRow(['Name', 'PNK', ...months]);
  headEl.innerHTML = '';
  headEl.appendChild(tr);
}

export function renderBody(tbodyEl, rows, months, tableEl) {
  function renderRow(row) {
    const tr = document.createElement('tr');

    const tdName = document.createElement('td');
    tdName.textContent = row.name;
    tr.appendChild(tdName);
    const tdPnk = document.createElement('td');
    tdPnk.textContent = row.pnk;
    tr.appendChild(tdPnk);

    for (const m of months) {
      const td = document.createElement('td');
      const val = row.months[m] ?? '';
      td.textContent = Number.isFinite(val) ? String(val) : '';
      td.dataset.clickable = "true";
      td.dataset.pnk = row.pnk;
      td.dataset.month = m;
      tr.appendChild(td);
    }
    return tr;
    }

  renderTbody(tbodyEl, rows, renderRow);
  applyStickyOffsets(tableEl);
}

export function applyStickyOffsets(table) {
  if (!table) return;

  const ths = table.tHead?.rows?.[0]?.cells ?? [];
  if (ths.length >= 1) ths[0].classList.add('sticky-col-1');
  if (ths.length >= 2) ths[1].classList.add('sticky-col-2');

  for (const row of table.tBodies[0].rows) {
    if (row.cells.length >= 1) row.cells[0].classList.add('sticky-col-1');
    if (row.cells.length >= 2) row.cells[1].classList.add('sticky-col-2');
  }

  const firstColCell =
    (ths.length ? ths[0] : null) ||
    (table.tBodies[0].rows[0]?.cells[0] ?? null);

  if (!firstColCell) return;

  const firstColWidth = firstColCell.getBoundingClientRect().width;
  const stickyCol2 = table.querySelectorAll('.sticky-col-2');
  for (const el of stickyCol2) {
    el.style.left = `${firstColWidth}px`;
  }
}

// --- main init -----------------------------------------------------------

/**
 * Initialise a matrix table page.
 * @param {Object} cfg
 * @param {string} cfg.tableId - DOM id of <table>
 * @param {string} cfg.theadId - DOM id of <thead>
 * @param {string} cfg.tbodyId - DOM id of <tbody>
 * @param {string} cfg.dataUrl - endpoint to load the matrix JSON from
 * @param {function} cfg.detailsUrlBuilder - (pnk, month) => string details URL
 * @param {string} [cfg.detailsWindowName] - name for the popup window
 */
export function initMatrixTable(cfg) {
  const HEAD = document.getElementById(cfg.theadId);
  const BODY = document.getElementById(cfg.tbodyId);
  const TABLE = document.getElementById(cfg.tableId);

  let detailsWin = null;

  function openOrUpdateDetails(pnk, month) {
    const url = cfg.detailsUrlBuilder(pnk, month);
    detailsWin = window.open(url, cfg.detailsWindowName || 'details');
    if (!detailsWin) {
      window.location.href = url;
      return;
    }
    detailsWin.focus?.();
  }

  TABLE.addEventListener('click', (ev) => {
    const td = ev.target.closest('td[data-clickable="true"]');
    if (!td) return;
    const { pnk, month } = td.dataset;
    openOrUpdateDetails(pnk, month);
  });

  (async function init() {
    try {
      const data = await fetchJSON(cfg.dataUrl);
      const rows = toRows(data);
      const months = collectAllMonths(rows);
      renderHeader(HEAD, months);
      renderBody(BODY, rows, months, TABLE);
    } catch (e) {
      HEAD.innerHTML = '';
      BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
      console.error(e);
    }
  })();

  window.addEventListener('resize', () => applyStickyOffsets(TABLE));
}

export function arrayToDateTime(arr) {
  if (!Array.isArray(arr)) return null;
  const [y, m, d, hh, mm, ss, nano] = arr;
  // JS Date months are 0-based; nano to millis
  const ms = Math.floor((nano ?? 0) / 1_000_000);
  return new Date(Date.UTC(y, (m ?? 1) - 1, d ?? 1, hh ?? 0, mm ?? 0, ss ?? 0, ms));
}

export function toTaskRows(jsonData) {
  if (!Array.isArray(jsonData)) return [];

  return jsonData.map((item) => {
    const {
      name,
      started,
      terminated,
      lastSuccessfulRun,
      durationOfLastRun,
      unsuccessfulRuns,
      error,
    } = item || {};

    return {
      name: name ?? "",
      started: arrayToDateTime(started),
      terminated: arrayToDateTime(terminated),
      lastSuccessfulRun: arrayToDateTime(lastSuccessfulRun),
      durationOfLastRunSeconds:
          typeof durationOfLastRun === "number" ? durationOfLastRun : null,
      unsuccessfulRuns: typeof unsuccessfulRuns === "number" ? unsuccessfulRuns : 0,
      error: typeof error === "string" ? error : "",
      // include original item if needed:
      // raw: item
    };
  });
}

export function renderTasksBody(tbodyEl, rows) {
  function renderRow(row) {
    const tr = document.createElement('tr');

    const tdName = document.createElement('td');
    tdName.textContent = row.name;
    tr.appendChild(tdName);
    const tdStatus = document.createElement('td');
    if (row.started != null && row.terminated == null) {
      tdStatus.textContent = "RUNNING";
    } else if (row.error != null && String(row.error).trim() !== "") {
      tdStatus.textContent = "ERROR";
    } else {
      tdStatus.textContent = "-";
    }
    tr.appendChild(tdStatus);
    const tdLastRun = document.createElement('td');
    tdLastRun.textContent = formatLocalDateTime(row.terminated);
    tr.appendChild(tdLastRun);
    const tdDuration = document.createElement('td');
    tdDuration.textContent = formatDuration(row.durationOfLastRunSeconds);
    tr.appendChild(tdDuration);
    const tdLastSuccess = document.createElement('td');
    tdLastSuccess.textContent = formatLocalDateTime(row.lastSuccessfulRun);
    tr.appendChild(tdLastSuccess);
    const tdFailures = document.createElement('td');
    tdFailures.textContent = row.unsuccessfulRuns;
    tr.appendChild(tdFailures);
    const tdError = document.createElement('td');
    tdError.textContent = row.error;
    tr.appendChild(tdError);

    return tr;
  }

  renderTbody(tbodyEl, rows, renderRow);
}


/**
 * Initialise a task table page.
 * @param {Object} cfg
 * @param {string} cfg.tableId - DOM id of <table>
 * @param {string} cfg.theadId - DOM id of <thead>
 * @param {string} cfg.tbodyId - DOM id of <tbody>
 * @param {string} cfg.dataUrl - endpoint to load the matrix JSON from
 */
export function initTaskTable(cfg) {
  const HEAD = document.getElementById(cfg.theadId);
  const BODY = document.getElementById(cfg.tbodyId);
  const TABLE = document.getElementById(cfg.tableId);

  async function loadTasks() {
    try {
      const data = await fetchJSON(cfg.dataUrl);
      const rows = toTaskRows(data);
      const tr = buildHeaderRow([
        'Name', 'Status', 'Last Run', 'Runtime', 'Last Successful', 'Failures', 'Error'
      ]);
      HEAD.innerHTML = '';
      HEAD.appendChild(tr);
      renderTasksBody(BODY, rows);
    } catch (e) {
      HEAD.innerHTML = '';
      BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
      console.error(e);
    }
  }

  document.getElementById('refreshBtn')?.addEventListener('click', loadTasks);
  window.addEventListener('resize', () => applyStickyOffsets(TABLE));

  loadTasks();
}
