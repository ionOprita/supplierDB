/**
 * table-common.js
 * Shared utilities to render a month-matrix table with a sticky header and first two columns,
 * plus a delegated click handler that opens a details window.
 */
import {fetchJSON, formatDuration, formatLocalDateTime} from "./common.js";

// --- helpers -------------------------------------------------------------

export function parseKey(key) {
  const extractField = (fieldName) => {
    const m = key.match(new RegExp(`${fieldName}=(.*?)(?=, [a-zA-Z0-9_]+=|\\]$)`));
    return m ? m[1].trim() : '';
  };

  const pnkMatch = key.match(/pnk=([^,\]]+)/);
  const nameMatch = key.match(/name=([^,\]]+)/);
  const vendorNameMatch = key.match(/vendorName=([^,\]]+)/);
  const pnk = extractField('pnk') || (pnkMatch ? pnkMatch[1].trim() : '');
  const name = extractField('name') || (nameMatch ? nameMatch[1].trim() : key);
  const vendorNameRaw = extractField('vendorName') || (vendorNameMatch ? vendorNameMatch[1].trim() : '');
  return {
    pnk,
    name,
    vendorName: vendorNameRaw === 'null' ? '' : vendorNameRaw
  };
}

export function toRows(jsonMap) {
  return Object.entries(jsonMap).map(([key, monthsObj]) => {
    const { pnk, name, vendorName } = parseKey(key);
    return { key, pnk, name, vendorName, months: monthsObj };
  });
}

export function collectAllMonths(rows) {
  const set = new Set();
  for (const row of rows) {
    for (const m of Object.keys(row.months)) set.add(m);
  }
  return [...set].sort();
}


// --- csv export ----------------------------------------------------------

export function csvEscape(value) {
  const normalized = String(value ?? '').replace(/\r?\n|\r/g, ' ').trim();
  if (/[",\n]/.test(normalized)) {
    return `"${normalized.replace(/"/g, '""')}"`;
  }
  return normalized;
}

export function tableToCsv(table) {
  if (!table) return '';
  const rows = Array.from(table.querySelectorAll('tr'));
  if (!rows.length) return '';

  return rows
    .map((row) => Array.from(row.cells).map((cell) => csvEscape(cell.textContent ?? '')).join(','))
    .join('\r\n');
}

export function downloadCsvFromTable(table, fileName) {
  const csv = tableToCsv(table);
  if (!csv) return false;

  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
  return true;
}

export function sanitizeFileNamePart(value) {
  return String(value ?? '')
    .trim()
    .replace(/[\\/:*?"<>|]+/g, '_')
    .replace(/\s+/g, '-');
}

export function bindTableCsvDownload({ buttonId, tableId, filePrefix = 'table', fileNameBuilder }) {
  const button = document.getElementById(buttonId);
  const table = document.getElementById(tableId);
  if (!button || !table) return;

  button.addEventListener('click', () => {
    const datePart = new Date().toISOString().slice(0, 10);
    let fileName = '';
    if (typeof fileNameBuilder === 'function') {
      fileName = String(fileNameBuilder({ datePart, filePrefix }) ?? '');
    }
    if (!fileName) {
      fileName = `${filePrefix}-${datePart}.csv`;
    } else if (!fileName.toLowerCase().endsWith('.csv')) {
      fileName = `${fileName}.csv`;
    }
    downloadCsvFromTable(table, fileName);
  });
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

export function bindVendorFilter({ selectId, rows, onChange, allLabel = 'All vendors' }) {
  const select = document.getElementById(selectId);
  if (!select) return;

  const vendors = [...new Set(rows.map((row) => row.vendorName).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b));

  select.innerHTML = '';
  const allOption = document.createElement('option');
  allOption.value = '';
  allOption.textContent = allLabel;
  select.appendChild(allOption);

  for (const vendor of vendors) {
    const opt = document.createElement('option');
    opt.value = vendor;
    opt.textContent = vendor;
    select.appendChild(opt);
  }

  select.addEventListener('change', () => onChange(select.value));
}

export function bindMonthRangeFilter({
  fromSelectId,
  toSelectId,
  resetButtonId,
  months,
  onChange,
  fromAllLabel = 'From start',
  toAllLabel = 'To end'
}) {
  const fromSelect = fromSelectId ? document.getElementById(fromSelectId) : null;
  const toSelect = toSelectId ? document.getElementById(toSelectId) : null;
  const resetButton = resetButtonId ? document.getElementById(resetButtonId) : null;

  if (!fromSelect && !toSelect && !resetButton) return;

  const populate = (select, allLabel) => {
    if (!select) return;
    select.innerHTML = '';

    const allOption = document.createElement('option');
    allOption.value = '';
    allOption.textContent = allLabel;
    select.appendChild(allOption);

    for (const m of months) {
      const opt = document.createElement('option');
      opt.value = m;
      opt.textContent = m;
      select.appendChild(opt);
    }
  };

  populate(fromSelect, fromAllLabel);
  populate(toSelect, toAllLabel);

  if (fromSelect) fromSelect.value = '';
  if (toSelect) toSelect.value = '';

  const normalize = (changedSide) => {
    const fromMonth = fromSelect?.value || '';
    const toMonth = toSelect?.value || '';
    if (!fromMonth || !toMonth) return;

    const fromIdx = months.indexOf(fromMonth);
    const toIdx = months.indexOf(toMonth);
    if (fromIdx < 0 || toIdx < 0 || fromIdx <= toIdx) return;

    if (changedSide === 'from' && toSelect) {
      toSelect.value = fromMonth;
    } else if (changedSide === 'to' && fromSelect) {
      fromSelect.value = toMonth;
    }
  };

  const emitChange = () => {
    onChange({
      fromMonth: fromSelect?.value || '',
      toMonth: toSelect?.value || ''
    });
  };

  fromSelect?.addEventListener('change', () => {
    normalize('from');
    emitChange();
  });

  toSelect?.addEventListener('change', () => {
    normalize('to');
    emitChange();
  });

  resetButton?.addEventListener('click', () => {
    if (fromSelect) fromSelect.value = '';
    if (toSelect) toSelect.value = '';
    emitChange();
  });

  emitChange();
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
 * @param {string} [cfg.csvButtonId] - DOM id of CSV download button
 * @param {string} [cfg.csvFilenamePrefix] - downloaded CSV filename prefix
 * @param {string} [cfg.vendorFilterSelectId] - DOM id of vendor filter <select>
 * @param {string} [cfg.vendorFilterAllLabel] - "show all" text for vendor filter
 * @param {string} [cfg.monthFromSelectId] - DOM id of "from month" <select>
 * @param {string} [cfg.monthToSelectId] - DOM id of "to month" <select>
 * @param {string} [cfg.monthResetButtonId] - DOM id of reset month-range button
 * @param {string} [cfg.monthFromAllLabel] - label for no lower month bound
 * @param {string} [cfg.monthToAllLabel] - label for no upper month bound
 */
export function initMatrixTable(cfg) {
  const HEAD = document.getElementById(cfg.theadId);
  const BODY = document.getElementById(cfg.tbodyId);
  const TABLE = document.getElementById(cfg.tableId);
  let rows = [];
  let months = [];
  let selectedVendorName = '';
  let selectedFromMonth = '';
  let selectedToMonth = '';

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

  if (cfg.csvButtonId) {
    bindTableCsvDownload({
      buttonId: cfg.csvButtonId,
      tableId: cfg.tableId,
      filePrefix: cfg.csvFilenamePrefix || 'table'
    });
  }

  function resolveVisibleMonths() {
    if (!months.length) return [];

    const startIdx = selectedFromMonth ? months.indexOf(selectedFromMonth) : 0;
    const endIdx = selectedToMonth ? months.indexOf(selectedToMonth) : (months.length - 1);
    if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return [];

    return months.slice(startIdx, endIdx + 1);
  }

  function renderFiltered() {
    const visibleMonths = resolveVisibleMonths();
    const filteredRows = !selectedVendorName
      ? rows
      : rows.filter((row) => row.vendorName === selectedVendorName);
    renderHeader(HEAD, visibleMonths);
    renderBody(BODY, filteredRows, visibleMonths, TABLE);
  }

  (async function init() {
    try {
      const data = await fetchJSON(cfg.dataUrl);
      rows = toRows(data);
      months = collectAllMonths(rows);
      if (cfg.vendorFilterSelectId) {
        bindVendorFilter({
          selectId: cfg.vendorFilterSelectId,
          rows,
          onChange: (vendorName) => {
            selectedVendorName = vendorName;
            renderFiltered();
          },
          allLabel: cfg.vendorFilterAllLabel || 'All vendors'
        });
      }

      if (cfg.monthFromSelectId || cfg.monthToSelectId || cfg.monthResetButtonId) {
        bindMonthRangeFilter({
          fromSelectId: cfg.monthFromSelectId,
          toSelectId: cfg.monthToSelectId,
          resetButtonId: cfg.monthResetButtonId,
          months,
          onChange: ({ fromMonth, toMonth }) => {
            selectedFromMonth = fromMonth;
            selectedToMonth = toMonth;
            renderFiltered();
          },
          fromAllLabel: cfg.monthFromAllLabel || 'From start',
          toAllLabel: cfg.monthToAllLabel || 'To end'
        });
      } else {
        renderFiltered();
      }
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
  };

  document.getElementById('refreshBtn')?.addEventListener('click', loadTasks);
  window.addEventListener('resize', () => applyStickyOffsets(TABLE));

  loadTasks();
}
