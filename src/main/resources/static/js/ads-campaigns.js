import {fetchJSON} from './common.js';
import {bindTableCsvDownload} from './table-common.js';

const DATE_SELECT = document.getElementById('adsCampaignDateSelect');
const STATUS = document.getElementById('adsCampaignsStatus');
const HEAD = document.getElementById('adsCampaignsHead');
const BODY = document.getElementById('adsCampaignsBody');

let currentColumns = [];

function setStatus(message) {
  if (STATUS) {
    STATUS.textContent = message || '';
  }
}

function selectedDateFromUrl(dates) {
  const requestedDate = new URLSearchParams(window.location.search).get('date');
  if (requestedDate && dates.includes(requestedDate)) {
    return requestedDate;
  }
  return dates[0] || '';
}

function setDateInUrl(reportDate) {
  const url = new URL(window.location.href);
  if (reportDate) {
    url.searchParams.set('date', reportDate);
  } else {
    url.searchParams.delete('date');
  }
  window.history.replaceState(null, '', url);
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
    if (campaignId) {
      const link = document.createElement('a');
      link.href = `/private/ads-adsets?campaignId=${encodeURIComponent(campaignId)}&date=${encodeURIComponent(DATE_SELECT.value || '')}`;
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

function renderRows(rows) {
  BODY.innerHTML = '';
  if (!rows.length) {
    renderMessageRow('No campaigns found for this report date.');
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

async function loadCampaigns(reportDate) {
  if (!reportDate) {
    currentColumns = [];
    HEAD.innerHTML = '';
    renderMessageRow('No campaign report dates found.');
    setStatus('');
    return;
  }

  setStatus('Loading campaigns...');
  const params = new URLSearchParams({date: reportDate});
  const data = await fetchJSON(`/app/adsCampaigns?${params.toString()}`);
  currentColumns = Array.isArray(data.columns) ? data.columns : [];
  const rows = Array.isArray(data.rows) ? data.rows : [];
  renderHeader(currentColumns);
  renderRows(rows);
  setStatus(`${rows.length} campaign${rows.length === 1 ? '' : 's'} for ${reportDate}.`);
}

async function init() {
  const dates = await fetchJSON('/app/adsCampaignDates');
  const reportDates = Array.isArray(dates) ? dates : [];
  populateDateSelect(reportDates);

  const selectedDate = selectedDateFromUrl(reportDates);
  if (selectedDate) {
    DATE_SELECT.value = selectedDate;
    setDateInUrl(selectedDate);
  }

  DATE_SELECT.addEventListener('change', () => {
    const reportDate = DATE_SELECT.value;
    setDateInUrl(reportDate);
    loadCampaigns(reportDate).catch((e) => {
      HEAD.innerHTML = '';
      renderMessageRow('Failed to load campaigns.');
      setStatus('');
      console.error(e);
    });
  });

  bindTableCsvDownload({
    buttonId: 'downloadCsvBtn',
    tableId: 'adsCampaignsTable',
    fileNameBuilder: ({datePart}) => `ads-campaigns-${DATE_SELECT.value || datePart}.csv`
  });

  await loadCampaigns(selectedDate);
}

init().catch((e) => {
  HEAD.innerHTML = '';
  currentColumns = [];
  renderMessageRow('Failed to load campaign data.');
  setStatus('');
  console.error(e);
});
