import { fetchJSON } from './common.js';
import { bindTableCsvDownload } from './table-common.js';

const HEAD = document.getElementById('currentRatesHead');
const BODY = document.getElementById('currentRatesBody');

function formatPercent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '';
}

function renderHeader() {
  const tr = document.createElement('tr');
  for (const label of ['Product', 'PNK', 'Return % (Current Month)', 'Storno % (Current Month)']) {
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
    tdReturn.textContent = formatPercent(row.returnRate);
    tr.appendChild(tdReturn);

    const tdStorno = document.createElement('td');
    tdStorno.textContent = formatPercent(row.stornoRate);
    tr.appendChild(tdStorno);

    frag.appendChild(tr);
  }
  BODY.appendChild(frag);
}

(async function init() {
  try {
    const rows = await fetchJSON('/app/currentRatesTable');
    renderHeader();
    renderBody(Array.isArray(rows) ? rows : []);
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
