import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';

const TITLE = document.getElementById('title');
const DATE_SELECT = document.getElementById('adsAdsetDateSelect');
const STATUS = document.getElementById('adsAdsetsStatus');
const HEAD = document.getElementById('adsAdsetsHead');
const BODY = document.getElementById('adsAdsetsBody');

let currentColumns = [];

function setStatus(message) {
  if (STATUS) {
    STATUS.textContent = message || '';
  }
}

function paramsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  return {
    campaignId: params.get('campaignId') || '',
    requestedDate: params.get('date') || ''
  };
}

function setDateInUrl(campaignId, reportDate) {
  const url = new URL(window.location.href);
  url.searchParams.set('campaignId', campaignId);
  if (reportDate) {
    url.searchParams.set('date', reportDate);
  } else {
    url.searchParams.delete('date');
  }
  window.history.replaceState(null, '', url);
}

function selectedDate(reportDates, requestedDate) {
  if (requestedDate && reportDates.includes(requestedDate)) {
    return requestedDate;
  }
  return reportDates[0] || '';
}

function populateDateSelect(dates) {
  DATE_SELECT.innerHTML = '';
  for (const reportDate of dates) {
    const option = document.createElement('option');
    option.value = reportDate;
    option.textContent = reportDate;
    DATE_SELECT.appendChild(option);
  }
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
    }
  });
  HEAD.innerHTML = '';
  HEAD.appendChild(tr);
}

function renderMessageRow(message) {
  BODY.innerHTML = '';
  const tr = document.createElement('tr');
  const td = document.createElement('td');
  td.colSpan = Math.max(currentColumns.length + 3, 1);
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

function appendPhrasesCell(tr, row, reportDate) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && reportDate) {
    const link = document.createElement('a');
    link.href = `/private/ads-search-phrases?campaignId=${encodeURIComponent(campaignId)}&adsetId=${encodeURIComponent(adsetId)}&date=${encodeURIComponent(reportDate)}`;
    link.textContent = 'phrases';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function appendProductsCell(tr, row, reportDate) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && reportDate) {
    const link = document.createElement('a');
    link.href = `/private/ads-targeted-products?campaignId=${encodeURIComponent(campaignId)}&adsetId=${encodeURIComponent(adsetId)}&date=${encodeURIComponent(reportDate)}`;
    link.textContent = 'products';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function appendKeywordsCell(tr, row, reportDate) {
  const td = document.createElement('td');
  const campaignId = String(row.campaignId ?? row.values?.campaign_id ?? '');
  const adsetId = String(row.adsetId ?? row.values?.adset_id ?? '');
  if (campaignId && adsetId && reportDate) {
    const link = document.createElement('a');
    link.href = `/private/ads-keywords?campaignId=${encodeURIComponent(campaignId)}&adsetId=${encodeURIComponent(adsetId)}&date=${encodeURIComponent(reportDate)}`;
    link.textContent = 'keywords';
    td.appendChild(link);
  }
  tr.appendChild(td);
}

function renderRows(rows, reportDate) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow('No adsets found for this campaign and report date.');
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const row of rows) {
    const tr = document.createElement('tr');
    currentColumns.forEach((column, index) => {
      appendCell(tr, row, column, index);
      if (index === 0) {
        appendPhrasesCell(tr, row, reportDate);
        appendProductsCell(tr, row, reportDate);
        appendKeywordsCell(tr, row, reportDate);
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

async function loadAdsets(campaignId, reportDate) {
  if (!campaignId) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('Missing campaign id.');
    setStatus('');
    return;
  }
  if (!reportDate) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('No report dates found for this campaign.');
    setStatus(`Campaign ID ${campaignId}`);
    return;
  }

  setStatus(`Loading adsets for campaign ID ${campaignId}...`);
  const params = new URLSearchParams({campaignId, date: reportDate});
  const data = await fetchJSON(`/app/adsAdsets?${params.toString()}`);
  setPageTitle(data.campaignName);
  currentColumns = Array.isArray(data.columns) ? data.columns : [];
  const rows = Array.isArray(data.rows) ? data.rows : [];
  renderHeader(currentColumns);
  renderRows(rows, reportDate);
  setStatus(`${rows.length} adset${rows.length === 1 ? '' : 's'} on ${reportDate}.`);
}

async function init() {
  const {campaignId, requestedDate} = paramsFromUrl();
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
  populateDateSelect(reportDates);

  const reportDate = selectedDate(reportDates, requestedDate);
  if (reportDate) {
    DATE_SELECT.value = reportDate;
    setDateInUrl(campaignId, reportDate);
  }

  DATE_SELECT.addEventListener('change', () => {
    const nextDate = DATE_SELECT.value;
    setDateInUrl(campaignId, nextDate);
    loadAdsets(campaignId, nextDate).catch((e) => {
      HEAD.innerHTML = '';
      renderMessageRow('Failed to load adsets.');
      setStatus('');
      console.error(e);
    });
  });

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsAdsetsTable',
    fileNameBuilder: ({datePart}) => `ads-adsets-${campaignId}-${DATE_SELECT.value || datePart}.csv`
  });

  await loadAdsets(campaignId, reportDate);
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow('Failed to load adset data.');
  setStatus('');
  console.error(e);
});
