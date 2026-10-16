import {downloadCsvFromTable, sanitizeFileNamePart} from './table-common.js';

const money = value => typeof value === 'string' && /^-?\d+\.\d{2}$/.test(value);
const statuses = new Set(['Finalized', 'Remaining after storno', 'Storno deduction', 'Storno']);

export async function fetchGMVForPeriod(request) {
  const response = await fetch(`/app/gmvForPeriod?${new URLSearchParams(request)}`, {
    method: 'GET', headers: {'Accept': 'application/json'}
  });
  if (response.redirected || response.status === 401) {
    throw new Error('Your session expired. Sign in again and retry.');
  }
  if (!response.ok) {
    const message = response.status === 400 ? 'Choose a valid product and date range.'
      : response.status === 404 ? 'This product is no longer available.'
      : response.status === 403 ? 'You are not allowed to calculate GMV.'
      : 'Could not calculate GMV. Try again.';
    throw new Error(`${message} (HTTP ${response.status})`);
  }
  if (!(response.headers.get('content-type') || '').includes('application/json')) {
    throw new Error('Your session may have expired. Sign in again and retry.');
  }
  const data = await response.json();
  if (!money(data?.gmv) || data.productCode !== request.productCode
    || data.startDate !== request.startDate || data.endDate !== request.endDate
    || !Array.isArray(data.contributions) || !data.contributions.every(row => row
      && typeof row.orderId === 'string'
      && typeof row.orderDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(row.orderDate)
      && statuses.has(row.status) && Number.isSafeInteger(row.quantity)
      && money(row.unitPrice) && money(row.gmv))) {
    throw new Error('The server returned an invalid GMV result. Try again.');
  }
  return data;
}

export function monthPeriod(month) {
  const match = /^(\d{4})-(\d{2})$/.exec(month);
  if (!match) throw new Error('Invalid GMV month.');
  const year = Number(match[1]);
  const number = Number(match[2]);
  if (number < 1 || number > 12) throw new Error('Invalid GMV month.');
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][number - 1];
  return {startDate: `${month}-01`, endDate: `${month}-${days}`};
}

export function createGMVDetails(documentRoot) {
  const byId = id => documentRoot.getElementById(id);
  const dialog = byId('gmvDetailsDialog');
  const title = byId('gmvDetailsTitle');
  const context = byId('gmvDetailsContext');
  const status = byId('gmvDetailsStatus');
  const reference = byId('gmvDetailsReference');
  const mismatch = byId('gmvDetailsMismatch');
  const body = byId('gmvDetailsBody');
  const total = byId('gmvDetailsTotal');
  const detailsTable = byId('gmvDetailsTable');
  const download = byId('gmvDetailsDownloadCsv');
  const retry = byId('gmvDetailsRetry');
  const close = byId('gmvDetailsClose');
  const table = byId('monthlyGmvTable');
  if (![dialog, title, context, status, reference, mismatch, body, total,
    detailsTable, download, retry, close, table].every(Boolean)) return null;

  const view = documentRoot.defaultView;
  const wrap = table.closest('.wrap');
  let generation = 0;
  let mode = null;
  let monthlyRequest = null;
  let opener = null;
  let scroll = null;
  let csvFileName = null;

  function clearDownload() {
    csvFileName = null;
    download.disabled = true;
  }

  clearDownload();

  function setStatus(message, isError = false) {
    status.textContent = message;
    status.classList.toggle('is-error', isError);
  }

  function restoreScroll() {
    if (!scroll) return;
    view?.scrollTo(scroll.x, scroll.y);
    if (wrap) {
      wrap.scrollLeft = scroll.left;
      wrap.scrollTop = scroll.top;
    }
  }

  function open(trigger, label, request, referenceValue, kind) {
    ++generation;
    mode = kind;
    monthlyRequest = null;
    clearDownload();
    opener = trigger;
    if (!dialog.open) {
      scroll = {x: view?.scrollX ?? 0, y: view?.scrollY ?? 0,
        left: wrap?.scrollLeft ?? 0, top: wrap?.scrollTop ?? 0};
    }
    title.textContent = 'Contributing orders';
    context.textContent = `${label} · ${request.startDate} to ${request.endDate}`;
    reference.textContent = `${kind === 'monthly' ? 'Stored monthly' : 'Calculated'} GMV: ${referenceValue}`;
    mismatch.hidden = true;
    mismatch.textContent = '';
    body.replaceChildren();
    total.textContent = '—';
    retry.hidden = true;
    setStatus('');
    if (!dialog.open) dialog.showModal();
    restoreScroll();
  }

  function render(data, storedValue = null) {
    body.replaceChildren();
    for (const contribution of data.contributions) {
      const row = documentRoot.createElement('tr');
      const values = [contribution.orderId, contribution.orderDate, contribution.status,
        contribution.quantity, contribution.unitPrice, contribution.gmv];
      for (const [index, value] of values.entries()) {
        const cell = documentRoot.createElement('td');
        cell.textContent = String(value);
        if (index >= 3) cell.classList.add('numeric');
        row.appendChild(cell);
      }
      body.appendChild(row);
    }
    total.textContent = data.gmv;
    mismatch.hidden = storedValue === null || storedValue === data.gmv;
    mismatch.textContent = mismatch.hidden ? ''
      : `Stored monthly GMV is ${storedValue}; the current calculation is ${data.gmv}.`;
    setStatus(data.contributions.length ? `${data.contributions.length} contributions.`
      : 'No contributing orders for this period.');
    dialog.setAttribute('aria-busy', 'false');
    csvFileName = `gmv-details-${sanitizeFileNamePart(data.productCode)}-${data.startDate}-to-${data.endDate}.csv`;
    download.disabled = false;
  }

  async function loadMonthly() {
    const current = monthlyRequest;
    const version = ++generation;
    clearDownload();
    body.replaceChildren();
    total.textContent = '—';
    mismatch.hidden = true;
    mismatch.textContent = '';
    retry.hidden = true;
    setStatus('Loading contributing orders…');
    dialog.setAttribute('aria-busy', 'true');
    try {
      const data = await fetchGMVForPeriod(current.request);
      if (version !== generation || !dialog.open) return;
      render(data, current.reference);
    } catch (error) {
      if (version !== generation || !dialog.open) return;
      setStatus(error.message || 'Could not load contributing orders. Try again.', true);
      retry.hidden = false;
    } finally {
      if (version === generation && dialog.open) dialog.setAttribute('aria-busy', 'false');
    }
  }

  function closeDialog() {
    ++generation;
    monthlyRequest = null;
    clearDownload();
    dialog.close();
  }

  close.addEventListener('click', closeDialog);
  dialog.addEventListener('cancel', () => {
    ++generation;
    monthlyRequest = null;
    clearDownload();
  });
  dialog.addEventListener('close', () => {
    if (dialog.open) return;
    ++generation;
    mode = null;
    monthlyRequest = null;
    clearDownload();
    if (opener && !opener.hidden) opener.focus({preventScroll: true});
    restoreScroll();
    opener = null;
    scroll = null;
  });
  retry.addEventListener('click', () => {
    if (mode === 'monthly' && monthlyRequest) return loadMonthly();
  });
  download.addEventListener('click', () => {
    if (dialog.open && !download.disabled && csvFileName) {
      downloadCsvFromTable(detailsTable, csvFileName);
    }
  });

  for (const trigger of table.querySelectorAll('.gmv-details-trigger')) {
    trigger.addEventListener('click', () => {
      if (!money(trigger.dataset.gmv)) return;
      const period = monthPeriod(trigger.dataset.month);
      const request = {productCode: trigger.dataset.productCode, ...period};
      const name = trigger.dataset.productName;
      const label = name ? `${name} (${request.productCode})` : request.productCode;
      open(trigger, label, request, trigger.dataset.gmv, 'monthly');
      monthlyRequest = {request, reference: trigger.dataset.gmv};
      return loadMonthly();
    });
  }

  return {
    openCalculated(trigger, data, label) {
      open(trigger, label, data, data.gmv, 'calculated');
      render(data);
    },
    invalidateCalculated() {
      if (mode === 'calculated' && dialog.open) closeDialog();
    }
  };
}
