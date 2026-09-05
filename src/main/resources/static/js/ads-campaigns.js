import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';
import {adsVendorErrorMessage, loadAdsVendor} from './ads-vendor.js';
import {
  adsPeriodFilePart,
  adsPeriodPhrase,
  adsPeriodSearchParams,
  bindAdsPeriodControls,
  isValidAdsPeriod,
  parseAdsPeriod,
  replaceAdsPeriodInUrl,
  resolveAdsPeriod
} from './ads-period.js';

const STATUS = document.getElementById('adsCampaignsStatus');
const HEAD = document.getElementById('adsCampaignsHead');
const BODY = document.getElementById('adsCampaignsBody');

let currentColumns = [];
let vendorId = '';
let activePeriod = null;
let latestRequestId = 0;

function setStatus(message) {
  if (STATUS) {
    STATUS.textContent = message || '';
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
  const displayText = values[column.key] ?? '';
  if (column.numeric) {
    td.classList.add('numeric');
  }
  if (index === 0) {
    td.classList.add('sticky-col-1', 'campaign-name-cell');
    const campaignId = String(row.campaignId ?? values.campaign_id ?? '');
    td.dataset.campaignId = campaignId;
    if (campaignId && isValidAdsPeriod(activePeriod)) {
      const link = document.createElement('a');
      const params = adsPeriodSearchParams(activePeriod, {vendorId, campaignId});
      link.href = `/private/ads-adsets?${params.toString()}`;
      link.textContent = displayText;
      td.appendChild(link);
    } else {
      td.textContent = displayText;
    }
  } else {
    td.textContent = displayText;
  }
  tr.appendChild(td);
}

function renderRows(rows, period) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow(`No campaigns found ${adsPeriodPhrase(period)}.`);
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

async function loadCampaigns(period, errorMessage = 'Failed to load campaigns.') {
  const requestId = ++latestRequestId;
  if (!isValidAdsPeriod(period)) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('No campaign report dates found.');
    setStatus('');
    return;
  }

  const periodPhrase = adsPeriodPhrase(period);
  setStatus(`Loading campaigns ${periodPhrase}...`);
  const params = adsPeriodSearchParams(period, {vendorId});
  try {
    const data = await fetchJSON(`/app/adsCampaigns?${params.toString()}`);
    if (requestId !== latestRequestId) {
      return;
    }
    currentColumns = Array.isArray(data.columns) ? data.columns : [];
    const rows = Array.isArray(data.rows) ? data.rows : [];
    renderHeader(currentColumns);
    renderRows(rows, period);
    setStatus(`${rows.length} campaign${rows.length === 1 ? '' : 's'} ${periodPhrase}.`);
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
  activePeriod = parseAdsPeriod(window.location.search);
  const vendor = await loadAdsVendor({
    allowDefault: true,
    selectId: 'adsVendorSelect',
    getPeriod: () => activePeriod
  });
  if (!vendor) {
    renderMessageRow('No vendors with campaign reports found.');
    return;
  }
  vendorId = vendor.vendorId;
  const dateParams = new URLSearchParams({vendorId});
  const dates = await fetchJSON(`/app/adsCampaignDates?${dateParams.toString()}`);
  const reportDates = Array.isArray(dates) ? dates : [];
  activePeriod = resolveAdsPeriod(window.location.search, reportDates);
  replaceAdsPeriodInUrl(activePeriod, {vendorId});

  bindAdsPeriodControls({
    periodSelectId: 'adsCampaignPeriodSelect',
    dateSelectId: 'adsCampaignDateSelect',
    dateControlsId: 'adsCampaignDateControls',
    dateLabelId: 'adsCampaignDateLabel',
    customControlsId: 'adsCampaignCustomControls',
    dateFromInputId: 'adsCampaignDateFromInput',
    dateToInputId: 'adsCampaignDateToInput',
    applyButtonId: 'adsCampaignApplyRangeBtn',
    reportDates,
    initialPeriod: activePeriod,
    onApply: (period) => {
      activePeriod = period;
      replaceAdsPeriodInUrl(period, {vendorId});
      loadCampaigns(period);
    }
  });

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsCampaignsTable',
    fileNameBuilder: ({datePart}) => `ads-campaigns-${vendorId}-${adsPeriodFilePart(activePeriod, datePart)}.csv`
  });

  await loadCampaigns(activePeriod, 'Failed to load campaign data.');
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow(adsVendorErrorMessage(e, 'Failed to load campaign data.'));
  setStatus('');
  console.error(e);
});
