import {fetchJSON} from './common.js';

const numberFormats = {
  integer: new Intl.NumberFormat('en-US', {maximumFractionDigits: 0}),
  decimal: new Intl.NumberFormat('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2}),
  percent: new Intl.NumberFormat('en-US', {style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2})
};
const dateFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', year: '2-digit', timeZone: 'UTC'
});
const monthFormat = new Intl.DateTimeFormat('en-US', {
  month: 'long', year: 'numeric', timeZone: 'UTC'
});

function parsePerformanceDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : date;
}

export function formatPerformanceValue(value, type) {
  if (value == null || value === '') return '—';
  if (type === 'date') {
    const date = parsePerformanceDate(value);
    return date ? dateFormat.formatToParts(date)
        .map(part => part.type === 'month' ? part.value.slice(0, 3) : part.value).join('') : '—';
  }
  const formatter = numberFormats[type];
  if (formatter) return typeof value === 'number' && Number.isFinite(value) ? formatter.format(value) : '—';
  return String(value);
}

export function formatPerformancePeriod(value, mode = 'week') {
  const date = parsePerformanceDate(value);
  if (!date) return '—';
  if (mode === 'month') return monthFormat.format(date);
  const end = new Date(date.getTime());
  end.setUTCDate(end.getUTCDate() + 6);
  return `${formatPerformanceValue(value, 'date')} - ${dateFormat.formatToParts(end)
    .map(part => part.type === 'month' ? part.value.slice(0, 3) : part.value).join('')}`;
}

export function visiblePerformanceRows(rows, showEverything) {
  return showEverything ? rows : rows.slice(-20);
}

export function initProductPerformance() {
  const byId = suffix => document.getElementById(`productPerformance${suffix}`);
  const head = byId('Head');
  const body = byId('Body');
  const wrap = byId('Wrap');
  const status = byId('Status');
  const retry = byId('Retry');
  const table = byId('Table');
  const vendorSelect = byId('VendorSelect');
  const productSelect = byId('ProductSelect');
  let vendors = [];
  let currentVendor = null;
  let currentProduct = null;
  let requestVersion = 0;
  let retryAction = loadOptions;
  let columns = [];
  let frozenIndexes = new Map();
  let frozenHeaders = [];
  let resizeFrame = 0;
  let tableWidth = 0;
  let periodMode = 'week';
  let showEverything = false;
  const resizeObserver = new ResizeObserver(() => {
    if (resizeFrame) return;
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = 0;
      updateFrozenOffsets();
    });
  });

  function markFrozen(cell, key) {
    const index = frozenIndexes.get(key);
    if (index === undefined) return;
    cell.classList.add('pp-frozen');
    cell.dataset.frozenIndex = index;
    cell.style.setProperty('--pp-frozen-left', `var(--pp-left-${index}, 0px)`);
    cell.style.setProperty('--pp-frozen-right', `var(--pp-right-${index}, 0px)`);
    if (index === frozenIndexes.size - 1) cell.classList.add('pp-frozen-end');
  }

  function updateFrozenOffsets() {
    if (wrap.hidden || !frozenHeaders.length) return;
    // Widths remain valid while scrolled; sticky element positions do not.
    const widths = frozenHeaders.map(header => header.getBoundingClientRect().width);
    tableWidth = table.getBoundingClientRect().width;
    let left = 0;
    widths.forEach((width, index) => {
      table.style.setProperty(`--pp-left-${index}`, `${left}px`);
      left += width;
      table.style.setProperty(`--pp-right-${index}`, `${left}px`);
    });
    table.style.setProperty('--pp-week-width', `${widths[0]}px`);
    table.style.setProperty('--pp-frozen-width', `${left}px`);
    updateTableEdge();
  }

  function updateTableEdge() {
    // Cancel native sticky clamping at the table's end when the frozen block
    // is wider than the viewport. Offscreen frozen cells must not cover earlier ones.
    table.style.setProperty('--pp-table-right', `${tableWidth - wrap.scrollLeft}px`);
  }

  function renderProduct(product) {
    byId('Pnk').textContent = product.pnk || '—';
    const link = byId('Link');
    link.hidden = true;
    link.removeAttribute('href');
    try {
      const url = new URL(product.url);
      if (url.protocol === 'https:' || url.protocol === 'http:') {
        link.href = url.href;
        link.hidden = false;
      }
    } catch { /* A product without a URL still has useful weekly metrics. */ }
    byId('Product').hidden = false;
  }

  function renderHeaders(groups) {
    resizeObserver.disconnect();
    head.replaceChildren();
    table.querySelectorAll('colgroup').forEach(group => group.remove());
    for (const group of groups) {
      const colgroup = document.createElement('colgroup');
      colgroup.span = group.columns.length;
      table.insertBefore(colgroup, head);
    }
    columns = groups.flatMap(group => group.columns.map((column, index) => ({
      ...column, group: group.key, groupStart: index === 0
    })));
    frozenIndexes = new Map(columns.filter(column => column.group === 'overview')
      .map((column, index) => [column.key, index]));
    const groupRow = document.createElement('tr');
    groupRow.className = 'pp-group-row';
    const columnRow = document.createElement('tr');
    columnRow.className = 'pp-column-row';

    for (const group of groups) {
      const week = group.columns.find(column => column.key === 'week');
      if (week) {
        const header = document.createElement('th');
        header.scope = 'col';
        header.rowSpan = 2;
        header.className = 'pp-week';
        const controls = document.createElement('div');
        controls.className = 'pp-period-controls';

        const periodSelect = document.createElement('select');
        periodSelect.className = 'pp-period-select';
        periodSelect.setAttribute('aria-label', 'Performance period');
        for (const [value, label] of [['week', 'Week'], ['month', 'Month']]) {
          const option = document.createElement('option');
          option.value = value;
          option.textContent = label;
          periodSelect.appendChild(option);
        }
        periodSelect.value = periodMode;
        periodSelect.addEventListener('change', () => {
          periodMode = periodSelect.value;
          renderRows(currentRows);
          updateStatus(currentRows);
        });

        const everythingLabel = document.createElement('label');
        everythingLabel.className = 'pp-everything-label';
        const everything = document.createElement('input');
        everything.type = 'checkbox';
        everything.checked = showEverything;
        everything.addEventListener('change', () => {
          showEverything = everything.checked;
          renderRows(currentRows);
          updateStatus(currentRows);
        });
        everythingLabel.append(everything, document.createTextNode('Show everything'));
        controls.append(periodSelect, everythingLabel);
        header.appendChild(controls);
        markFrozen(header, week.key);
        groupRow.appendChild(header);
      }
      const metrics = group.columns.filter(column => column.key !== 'week');
      if (metrics.length) {
        const header = document.createElement('th');
        const label = document.createElement('span');
        label.textContent = group.label;
        label.className = 'pp-group-label';
        header.appendChild(label);
        header.scope = 'colgroup';
        header.colSpan = metrics.length;
        header.dataset.group = group.key;
        header.className = 'pp-group-start';
        if (group.key === 'overview') header.classList.add('pp-frozen-group', 'pp-frozen-end');
        groupRow.appendChild(header);
      }
      metrics.forEach((column, index) => {
        const header = document.createElement('th');
        header.scope = 'col';
        header.textContent = column.label;
        header.dataset.group = group.key;
        header.dataset.type = column.type;
        if (index === 0) header.classList.add('pp-group-start');
        if (numberFormats[column.type]) header.classList.add('numeric');
        markFrozen(header, column.key);
        columnRow.appendChild(header);
      });
    }
    head.append(groupRow, columnRow);
    frozenHeaders = [...head.querySelectorAll('[data-frozen-index]')]
      .sort((a, b) => Number(a.dataset.frozenIndex) - Number(b.dataset.frozenIndex));
    resizeObserver.observe(wrap);
    resizeObserver.observe(table);
    frozenHeaders.forEach(header => resizeObserver.observe(header));
  }

  let currentRows = [];

  function renderRows(rows) {
    currentRows = rows;
    body.replaceChildren();
    const fragment = document.createDocumentFragment();
    for (const row of visiblePerformanceRows(rows, showEverything)) {
      const tr = document.createElement('tr');
      for (const column of columns) {
        const isWeek = column.key === 'week';
        const cell = document.createElement(isWeek ? 'th' : 'td');
        cell.textContent = isWeek
          ? formatPerformancePeriod(row.values?.[column.key], periodMode)
          : formatPerformanceValue(row.values?.[column.key], column.type);
        if (isWeek) {
          cell.scope = 'row';
          cell.className = 'pp-week';
        } else {
          if (numberFormats[column.type]) cell.classList.add('numeric');
          if (column.groupStart) cell.classList.add('pp-group-start');
        }
        cell.dataset.type = column.type;
        markFrozen(cell, column.key);
        tr.appendChild(cell);
      }
      fragment.appendChild(tr);
    }
    body.appendChild(fragment);
  }

  function updateStatus(rows) {
    const visibleRows = visiblePerformanceRows(rows, showEverything);
    if (!visibleRows.length) {
      status.textContent = `No ${periodMode} product performance data available.`;
      retry.hidden = false;
      return;
    }
    const first = formatPerformancePeriod(visibleRows[0].values?.week, periodMode);
    const last = formatPerformancePeriod(visibleRows[visibleRows.length - 1].values?.week, periodMode);
    const noun = periodMode === 'week' ? 'week' : 'month';
    status.textContent = `${visibleRows.length} ${visibleRows.length === 1 ? noun : `${noun}s`} · ${first} – ${last}. Scroll horizontally for all advertising groups.`;
  }

  function fillSelect(select, items, emptyLabel, key, label) {
    select.replaceChildren();
    if (!items.length) {
      const option = document.createElement('option');
      option.value = '';
      option.textContent = emptyLabel;
      select.appendChild(option);
    } else {
      for (const item of items) {
        const option = document.createElement('option');
        option.value = item[key];
        option.textContent = label(item);
        select.appendChild(option);
      }
    }
    select.disabled = !items.length;
  }

  function clearReport() {
    byId('Product').hidden = true;
    body.replaceChildren();
    wrap.hidden = true;
  }

  function updateSelectionUrl() {
    const url = new URL(window.location.href);
    if (currentVendor) url.searchParams.set('vendorId', currentVendor.vendorId);
    else url.searchParams.delete('vendorId');
    if (currentProduct) url.searchParams.set('productCode', currentProduct.productCode);
    else url.searchParams.delete('productCode');
    window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  }

  function selectVendor(vendorId, requestedProductCode) {
    ++requestVersion;
    currentVendor = vendors.find(vendor => vendor.vendorId === vendorId) || null;
    const products = currentVendor?.products || [];
    currentProduct = products.find(product => product.productCode === requestedProductCode) || products[0] || null;
    vendorSelect.value = currentVendor?.vendorId || '';
    fillSelect(productSelect, products, currentVendor ? 'No products for this vendor' : 'Select a vendor first', 'productCode', product => {
      const name = product.name || product.productCode;
      const identifier = product.pnk || product.productCode;
      return name === identifier ? name : `${name} — ${identifier}`;
    });
    productSelect.value = currentProduct?.productCode || '';
    updateSelectionUrl();
    clearReport();
    if (currentProduct) return load();
    status.textContent = currentVendor ? 'No products found for this vendor.' : 'No vendors available.';
    retryAction = loadOptions;
    retry.hidden = false;
    retry.disabled = false;
    wrap.setAttribute('aria-busy', 'false');
  }

  async function loadOptions() {
    const version = ++requestVersion;
    retryAction = loadOptions;
    clearReport();
    fillSelect(vendorSelect, [], 'Loading vendors…', 'vendorId');
    fillSelect(productSelect, [], 'Select a vendor first', 'productCode');
    retry.hidden = true;
    retry.disabled = true;
    wrap.setAttribute('aria-busy', 'true');
    status.textContent = 'Loading vendors and products…';
    try {
      const data = await fetchJSON('/app/productPerformanceOptions');
      if (version !== requestVersion) return;
      if (!Array.isArray(data.vendors)) throw new Error('Invalid product options response');
      vendors = data.vendors;
      fillSelect(vendorSelect, vendors, 'No vendors available', 'vendorId', vendor => {
        const name = vendor.name || vendor.account || vendor.vendorId;
        return vendor.account && vendor.account !== name ? `${name} (${vendor.account})` : name;
      });
      const params = new URLSearchParams(window.location.search);
      const requestedVendor = params.get('vendorId')?.toLowerCase();
      const vendor = vendors.find(item => item.vendorId === requestedVendor)
        || vendors.find(item => item.vendorId === data.defaultVendorId) || vendors[0];
      return selectVendor(vendor?.vendorId, params.get('productCode'));
    } catch (error) {
      if (version !== requestVersion) return;
      fillSelect(vendorSelect, [], 'Vendors unavailable', 'vendorId');
      fillSelect(productSelect, [], 'Products unavailable', 'productCode');
      status.textContent = 'Unable to load vendors and products. Please try again.';
      retry.hidden = false;
      console.error('Product performance options request failed.', error);
    } finally {
      if (version === requestVersion) {
        retry.disabled = false;
        wrap.setAttribute('aria-busy', 'false');
      }
    }
  }

  async function load() {
    if (!currentVendor || !currentProduct) return;
    const version = ++requestVersion;
    const params = new URLSearchParams({vendorId: currentVendor.vendorId, productCode: currentProduct.productCode});
    retryAction = load;
    clearReport();
    retry.hidden = true;
    retry.disabled = true;
    wrap.setAttribute('aria-busy', 'true');
    status.textContent = 'Loading product performance…';
    try {
      const data = await fetchJSON(`/app/productPerformance?${params.toString()}`);
      if (version !== requestVersion) return;
      if (!Array.isArray(data.groups) || !data.groups.length || !Array.isArray(data.rows)) {
        throw new Error('Invalid product performance response');
      }
      renderProduct(data.product || {});
      byId('Preview').hidden = data.mock !== true;
      if (data.mock === true) table.setAttribute('aria-describedby', 'productPerformancePreview');
      else table.removeAttribute('aria-describedby');
      renderHeaders(data.groups);
      table.setAttribute('aria-label', `${periodMode === 'week' ? 'Weekly' : 'Monthly'} product and advertising performance`);
      renderRows(data.rows);
      wrap.hidden = false;
      updateFrozenOffsets();
      updateStatus(data.rows);
    } catch (error) {
      if (version !== requestVersion) return;
      status.textContent = 'Unable to load product performance. Please try again.';
      retry.hidden = false;
      console.error('Product performance request failed.', error);
    } finally {
      if (version === requestVersion) {
        retry.disabled = false;
        wrap.setAttribute('aria-busy', 'false');
      }
    }
  }

  retry.addEventListener('click', () => retryAction());
  vendorSelect.addEventListener('change', () => selectVendor(vendorSelect.value));
  productSelect.addEventListener('change', () => {
    currentProduct = currentVendor?.products.find(product => product.productCode === productSelect.value) || null;
    updateSelectionUrl();
    load();
  });
  wrap.addEventListener('scroll', updateTableEdge, {passive: true});
  document.querySelector('a[href="/private/product-performance"]')?.setAttribute('aria-current', 'page');
  return loadOptions();
}

if (typeof document !== 'undefined' && document.getElementById('productPerformancePage')) {
  initProductPerformance();
}
