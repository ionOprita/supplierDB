import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';
import {adsVendorErrorMessage, loadAdsVendor} from './ads-vendor.js';
import {
  adsPeriodFilePart,
  adsPeriodPhrase,
  adsPeriodSearchParams,
  isValidAdsPeriod,
  parseAdsPeriod,
  replaceAdsPeriodInUrl
} from './ads-period.js';

const TITLE = document.getElementById('title');
const STATUS = document.getElementById('adsSearchPhrasesStatus');
const HEAD = document.getElementById('adsSearchPhrasesHead');
const BODY = document.getElementById('adsSearchPhrasesBody');
const HIDDEN_COLUMN_KEYS = new Set([
  'is_aggregated',
  'summary_active_offer_count',
  'summary_offer_count',
  'summary_paused_offer_count',
  'summary_adset_count',
  'summary_keyword_count',
  'summary_product_target_count',
  'last_seen_at'
]);

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
    period: parseAdsPeriod(params)
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

function renderRows(rows, period) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow(`No search phrases found for this adset ${adsPeriodPhrase(period)}.`);
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

function setPageTitle(data, period) {
  const campaignName = data.campaignName || 'campaign';
  const adsetName = data.adsetName || 'adset';
  pageTitle = `Search phrases for ${campaignName} ${adsetName} ${adsPeriodPhrase(period)}`;
  if (TITLE) {
    TITLE.textContent = pageTitle;
  }
  document.title = pageTitle;
}

async function loadSearchPhrases(vendorId, campaignId, adsetId, period) {
  if (!campaignId || !adsetId || !isValidAdsPeriod(period)) {
    HEAD.innerHTML = '';
    currentColumns = [];
    renderMessageRow('Missing campaign id, adset id, or valid report period.');
    setStatus('');
    return;
  }

  const periodPhrase = adsPeriodPhrase(period);
  setStatus(`Loading search phrases ${periodPhrase}...`);
  const params = adsPeriodSearchParams(period, {vendorId, campaignId, adsetId});
  const data = await fetchJSON(`/app/adsSearchPhrases?${params.toString()}`);
  setPageTitle(data, period);
  currentColumns = Array.isArray(data.columns)
    ? data.columns.filter((column) => !HIDDEN_COLUMN_KEYS.has(column.key))
    : [];
  const rows = Array.isArray(data.rows) ? data.rows : [];
  renderHeader(currentColumns);
  renderRows(rows, period);
  setStatus(`${rows.length} phrase${rows.length === 1 ? '' : 's'} ${periodPhrase}.`);
}

async function init() {
  const {vendorId} = await loadAdsVendor();
  const {campaignId, adsetId, period} = paramsFromUrl();
  replaceAdsPeriodInUrl(period, {vendorId, campaignId, adsetId});

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsSearchPhrasesTable',
    fileNameBuilder: ({datePart}) => {
      const periodPart = adsPeriodFilePart(period, datePart);
      return `ads-search-phrases-${vendorId}-${campaignId || 'campaign'}-${adsetId || 'adset'}-${periodPart}.csv`;
    }
  });

  await loadSearchPhrases(vendorId, campaignId, adsetId, period);
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow(adsVendorErrorMessage(e, 'Failed to load search phrase data.'));
  setStatus('');
  console.error(e);
});
