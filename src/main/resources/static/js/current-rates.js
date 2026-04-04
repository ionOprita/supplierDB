import { fetchJSON } from './common.js';
import { bindTableCsvDownload, toRows } from './table-common.js';

const HEAD = document.getElementById('currentRatesHead');
const BODY = document.getElementById('currentRatesBody');
const WINDOW_MONTHS_SELECT = document.getElementById('currentRatesWindowMonthsSelect');
const CONFIDENCE_LEVEL_SELECT = document.getElementById('currentRatesConfidenceLevelSelect');
const BEFORE_MONTH_SELECT = document.getElementById('currentRatesBeforeMonthSelect');
const UPDATE_BUTTON = document.getElementById('currentRatesUpdateBtn');

function formatYearMonth(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
}

function addMonths(yearMonth, delta) {
  const [year, month] = String(yearMonth).split('-').map(Number);
  const date = new Date(year, (month - 1) + delta, 1);
  return formatYearMonth(date);
}

function populateBeforeMonthSelect() {
  if (!BEFORE_MONTH_SELECT) {
    return;
  }

  BEFORE_MONTH_SELECT.innerHTML = '';
  const cursor = new Date();
  cursor.setDate(1);

  for (let i = 0; i < 12; i += 1) {
    const option = document.createElement('option');
    const yearMonth = formatYearMonth(cursor);
    option.value = yearMonth;
    option.textContent = yearMonth;
    BEFORE_MONTH_SELECT.appendChild(option);
    cursor.setMonth(cursor.getMonth() - 1);
  }
}

function formatPercent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '';
}

function formatEstimate(estimate, partCount, totalCount) {
  const hasCounts = Number.isFinite(partCount) && Number.isFinite(totalCount) && totalCount > 0;
  const countsText = hasCounts ? ` (${partCount}/${totalCount})` : '';

  if (!estimate || !Number.isFinite(estimate.rate)) {
    return countsText.trimStart();
  }

  const rate = formatPercent(estimate.rate);
  const lowerBound = formatPercent(estimate.lowerBound);
  const upperBound = formatPercent(estimate.upperBound);

  if (!lowerBound || !upperBound) {
    return `${rate}${countsText}`;
  }

  return `${rate} [${lowerBound} - ${upperBound}]${countsText}`;
}

function renderHeader() {
  const tr = document.createElement('tr');
  for (const label of ['Product', 'PNK', 'Return %', 'Storno %', 'Rel %']) {
    const th = document.createElement('th');
    th.textContent = label;
    tr.appendChild(th);
  }
  HEAD.innerHTML = '';
  HEAD.appendChild(tr);
}

function renderBody(rows) {
  BODY.innerHTML = '';
  const frag = document.createDocumentFragment();
  for (const row of rows) {
    const tr = document.createElement('tr');

    const tdName = document.createElement('td');
    tdName.textContent = row.name ?? '';
    tr.appendChild(tdName);

    const tdPnk = document.createElement('td');
    tdPnk.textContent = row.pnk ?? '';
    tr.appendChild(tdPnk);

    const tdReturn = document.createElement('td');
    tdReturn.textContent = formatEstimate(row.returnRate, row.returnCount, row.orderCount);
    tr.appendChild(tdReturn);

    const tdStorno = document.createElement('td');
    tdStorno.textContent = formatEstimate(row.stornoRate, row.stornoCount, row.orderCount);
    tr.appendChild(tdStorno);

    const tdRel = document.createElement('td');
    tdRel.textContent = formatEstimate(row.relRate, row.relCount, row.orderCount);
    tr.appendChild(tdRel);

    frag.appendChild(tr);
  }
  BODY.appendChild(frag);
}

function toCurrentRateRows(data, selectedMonth) {
  return toRows(data).map((row) => {
    const stats = row.months?.[selectedMonth] ?? {};
    return {
      name: row.name,
      pnk: row.pnk,
      orderCount: stats.orderCount ?? null,
      returnCount: stats.returnCount ?? null,
      stornoCount: stats.stornoCount ?? null,
      relCount: stats.relCount ?? null,
      returnRate: stats.returnRate ?? null,
      stornoRate: stats.stornoRate ?? null,
      relRate: stats.relRate ?? null
    };
  });
}

async function loadTable() {
  const selectedMonth = BEFORE_MONTH_SELECT?.value;
  const aggregateMonths = WINDOW_MONTHS_SELECT?.value;
  const confidenceLevel = CONFIDENCE_LEVEL_SELECT?.value;

    if (!selectedMonth || !aggregateMonths ||!confidenceLevel) {
    return;
  }

  const params = new URLSearchParams({
    startMonth: selectedMonth,
    endMonth: addMonths(selectedMonth, 1),
    aggregateMonths,
    confidenceLevel
  });

  const data = await fetchJSON(`/app/monthStats?${params.toString()}`);
  renderHeader();
  renderBody(toCurrentRateRows(data, selectedMonth));
}

(async function init() {
  try {
    populateBeforeMonthSelect();
    UPDATE_BUTTON?.addEventListener('click', () => {
      loadTable().catch((e) => {
        HEAD.innerHTML = '';
        BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
        console.error(e);
      });
    });
    await loadTable();
    bindTableCsvDownload({
      buttonId: 'downloadCsvBtn',
      tableId: 'currentRatesTable',
      filePrefix: 'current-rates-table'
    });
  } catch (e) {
    HEAD.innerHTML = '';
    BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
    console.error(e);
  }
})();
