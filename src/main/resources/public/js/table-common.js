/**
 * table-common.js
 * Shared utilities to render a month-matrix table with sticky header & first two columns,
 * plus a delegated click handler that opens a details window.
 */

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

export async function fetchJSON(url) {
  const res = await fetch(url, { headers: { 'Accept': 'application/json' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// --- rendering -----------------------------------------------------------

export function renderHeader(headEl, months) {
  const tr = document.createElement('tr');

  const thName = document.createElement('th');
  thName.textContent = 'Name';
  tr.appendChild(thName);
  const thPnk = document.createElement('th');
  thPnk.textContent = 'PNK';
  tr.appendChild(thPnk);

  for (const m of months) {
    const th = document.createElement('th');
    th.textContent = m;
    tr.appendChild(th);
  }
  headEl.innerHTML = '';
  headEl.appendChild(tr);
}

export function renderBody(tbodyEl, rows, months, tableEl) {
  tbodyEl.innerHTML = '';
  const frag = document.createDocumentFragment();

  for (const row of rows) {
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
    const isAllZero = Object.values(row.months).every(v => !v);
    if (!isAllZero) {
      frag.appendChild(tr);
    } else {
      frag.appendChild(tr); // keep if you don't want to filter empty rows
    }
  }
  tbodyEl.appendChild(frag);
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
 * Initialize a matrix table page.
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
