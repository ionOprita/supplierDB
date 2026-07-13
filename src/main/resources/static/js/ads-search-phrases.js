import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';

const TITLE = document.getElementById('title');
const STATUS = document.getElementById('adsSearchPhrasesStatus');
const HEAD = document.getElementById('adsSearchPhrasesHead');
const BODY = document.getElementById('adsSearchPhrasesBody');

let currentColumns = [];
let pageTitle = 'Search phrases';

function setStatus(message) {
  if (STATUS) {
    STATUS.textContent = message || '';
  }
}

function paramsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  return {
    campaignId: params.get('campaignId') || '',
    adsetId: params.get('adsetId') || '',
    reportDate: params.get('date') || ''
  };
}

function renderHeader(columns) {
  const tr = document.createElement('tr');
  columns.forEach((column, index) => {
    const th = document.createElement('th');
    th.textContent = column.label || column.key || '';
    if (column.numeric) {
      th.classList.add('numeric');
    }
    if (index === 0) {
      th.classList.add('sticky-col-1');
    }
    tr.appendChild(th);
  });
  HEAD.innerHTML = '';
  HEAD.appendChild(tr);
}

function renderMessageRow(message) {
  BODY.innerHTML = '';
  const tr = document.createElement('tr');
  const td = document.createElement('td');
  td.colSpan = Math.max(currentColumns.length, 1);
  td.textContent = message;
  tr.appendChild(td);
  BODY.appendChild(tr);
}

function appendCell(tr, row, column, index) {
  const td = document.createElement('td');
  const values = row.values || {};
  td.textContent = values[column.key] ?? '';
  if (column.numeric) {
    td.classList.add('numeric');
  }
  if (index === 0) {
    td.classList.add('sticky-col-1', 'campaign-name-cell');
  }
  tr.appendChild(td);
}

function renderRows(rows) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow('No search phrases found for this adset.');
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const row of rows) {
    const tr = document.createElement('tr');
    currentColumns.forEach((column, index) => appendCell(tr, row, column, index));
    fragment.appendChild(tr);
  }
  BODY.appendChild(fragment);
}

function setPageTitle(data, fallbackDate) {
  const campaignName = data.campaignName || 'campaign';
  const adsetName = data.adsetName || 'adset';
  const reportDate = data.reportDate || fallbackDate;
  pageTitle = `Search phrases for ${campaignName} ${adsetName} on ${reportDate}`;
  if (TITLE) {
    TITLE.textContent = pageTitle;
  }
  document.title = pageTitle;
}

async function loadSearchPhrases(campaignId, adsetId, reportDate) {
  if (!campaignId || !adsetId || !reportDate) {
    HEAD.innerHTML = '';
    currentColumns = [];
    renderMessageRow('Missing campaign id, adset id, or report date.');
    setStatus('');
    return;
  }

  setStatus('Loading search phrases...');
  const params = new URLSearchParams({campaignId, adsetId, date: reportDate});
  const data = await fetchJSON(`/app/adsSearchPhrases?${params.toString()}`);
  setPageTitle(data, reportDate);
  currentColumns = Array.isArray(data.columns) ? data.columns : [];
  const rows = Array.isArray(data.rows) ? data.rows : [];
  renderHeader(currentColumns);
  renderRows(rows);
  setStatus(`${rows.length} phrase${rows.length === 1 ? '' : 's'}.`);
}

async function init() {
  const {campaignId, adsetId, reportDate} = paramsFromUrl();

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsSearchPhrasesTable',
    fileNameBuilder: ({datePart}) => {
      const date = reportDate || datePart;
      return `ads-search-phrases-${campaignId || 'campaign'}-${adsetId || 'adset'}-${date}.csv`;
    }
  });

  await loadSearchPhrases(campaignId, adsetId, reportDate);
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow('Failed to load search phrase data.');
  setStatus('');
  console.error(e);
});
