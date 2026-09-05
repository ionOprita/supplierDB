import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';
import {
  adsPeriodFilePart,
  adsPeriodPhrase,
  adsPeriodSearchParams,
  bindAdsPeriodControls,
  isValidAdsPeriod,
  replaceAdsPeriodInUrl,
  resolveAdsPeriod
} from './ads-period.js';

const TITLE = document.getElementById('title');
const STATUS = document.getElementById('adsAdsetsStatus');
const HEAD = document.getElementById('adsAdsetsHead');
const BODY = document.getElementById('adsAdsetsBody');

let currentColumns = [];
let activePeriod = null;
let latestRequestId = 0;

function setStatus(message) {
  if (STATUS) {
    STATUS.textContent = message || '';
  }
}

function paramsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  return {
    campaignId: params.get('campaignId') || ''
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
    if (index === 0) {
      const phrasesTh = document.createElement('th');
      phrasesTh.textContent = 'Phrases';
      tr.appendChild(phrasesTh);
      const productsTh = document.createElement('th');
      productsTh.textContent = 'Products';
      tr.appendChild(productsTh);
      const keywordsTh = document.createElement('th');
      keywordsTh.textContent = 'Keywords';
      tr.appendChild(keywordsTh);
      const negativeTh = document.createElement('th');
      negativeTh.textContent = 'Negative';
      tr.appendChild(negativeTh);
    }
  });
  HEAD.innerHTML = '';
  HEAD.appendChild(tr);
}

function renderMessageRow(message) {
  BODY.innerHTML = '';
  const tr = document.createElement('tr');
  const td = document.createElement('td');
  td.colSpan = Math.max(currentColumns.length + 4, 1);
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
    td.dataset.adsetId = String(row.adsetId ?? values.adset_id ?? '');
  }
  tr.appendChild(td);
}

function appendPhrasesCell(tr, row, period) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && isValidAdsPeriod(period)) {
    const link = document.createElement('a');
    const params = adsPeriodSearchParams(period, {campaignId, adsetId});
    link.href = `/private/ads-search-phrases?${params.toString()}`;
    link.textContent = 'phrases';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function appendProductsCell(tr, row, period) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && isValidAdsPeriod(period)) {
    const link = document.createElement('a');
    const params = adsPeriodSearchParams(period, {campaignId, adsetId});
    link.href = `/private/ads-targeted-products?${params.toString()}`;
    link.textContent = 'products';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function appendKeywordsCell(tr, row, period, negativeOnly = false) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && isValidAdsPeriod(period)) {
    const params = adsPeriodSearchParams(period, {
      campaignId,
      adsetId,
      negative: negativeOnly ? 'true' : null
    });
    const link = document.createElement('a');
    link.href = `/private/ads-keywords?${params.toString()}`;
    link.textContent = negativeOnly ? 'negative' : 'keywords';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function renderRows(rows, period) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow(`No adsets found for this campaign ${adsPeriodPhrase(period)}.`);
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const row of rows) {
    const tr = document.createElement('tr');
    currentColumns.forEach((column, index) => {
      appendCell(tr, row, column, index);
      if (index === 0) {
        appendPhrasesCell(tr, row, period);
        appendProductsCell(tr, row, period);
        appendKeywordsCell(tr, row, period);
        appendKeywordsCell(tr, row, period, true);
      }
    });
    fragment.appendChild(tr);
  }
  BODY.appendChild(fragment);
}

function setPageTitle(campaignName) {
  const title = `Adsets for ${campaignName || 'campaign'}`;
  if (TITLE) {
    TITLE.textContent = title;
  }
  document.title = title;
}

async function loadAdsets(campaignId, period, errorMessage = 'Failed to load adsets.') {
  const requestId = ++latestRequestId;
  if (!campaignId) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('Missing campaign id.');
    setStatus('');
    return;
  }
  if (!isValidAdsPeriod(period)) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('No report dates found for this campaign.');
    setStatus(`Campaign ID ${campaignId}`);
    return;
  }

  const periodPhrase = adsPeriodPhrase(period);
  setStatus(`Loading adsets for campaign ID ${campaignId} ${periodPhrase}...`);
  const params = adsPeriodSearchParams(period, {campaignId});
  try {
    const data = await fetchJSON(`/app/adsAdsets?${params.toString()}`);
    if (requestId !== latestRequestId) {
      return;
    }
    setPageTitle(data.campaignName);
    currentColumns = Array.isArray(data.columns) ? data.columns : [];
    const rows = Array.isArray(data.rows) ? data.rows : [];
    renderHeader(currentColumns);
    renderRows(rows, period);
    setStatus(`${rows.length} adset${rows.length === 1 ? '' : 's'} ${periodPhrase}.`);
  } catch (e) {
    if (requestId !== latestRequestId) {
      return;
    }
    HEAD.innerHTML = '';
    currentColumns = [];
    renderMessageRow(errorMessage);
    setStatus('');
    console.error(e);
  }
}

async function init() {
  const {campaignId} = paramsFromUrl();
  if (!campaignId) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('Missing campaign id.');
    setStatus('');
    return;
  }

  const dateParams = new URLSearchParams({campaignId});
  const dates = await fetchJSON(`/app/adsAdsetDates?${dateParams.toString()}`);
  const reportDates = Array.isArray(dates) ? dates : [];
  activePeriod = resolveAdsPeriod(window.location.search, reportDates);
  replaceAdsPeriodInUrl(activePeriod, {campaignId});

  bindAdsPeriodControls({
    periodSelectId: 'adsAdsetPeriodSelect',
    dateSelectId: 'adsAdsetDateSelect',
    dateControlsId: 'adsAdsetDateControls',
    dateLabelId: 'adsAdsetDateLabel',
    customControlsId: 'adsAdsetCustomControls',
    dateFromInputId: 'adsAdsetDateFromInput',
    dateToInputId: 'adsAdsetDateToInput',
    applyButtonId: 'adsAdsetApplyRangeBtn',
    reportDates,
    initialPeriod: activePeriod,
    onApply: (period) => {
      activePeriod = period;
      replaceAdsPeriodInUrl(period, {campaignId});
      loadAdsets(campaignId, period);
    }
  });

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsAdsetsTable',
    fileNameBuilder: ({datePart}) => `ads-adsets-${campaignId}-${adsPeriodFilePart(activePeriod, datePart)}.csv`
  });

  await loadAdsets(campaignId, activePeriod, 'Failed to load adset data.');
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow('Failed to load adset data.');
  setStatus('');
  console.error(e);
});
