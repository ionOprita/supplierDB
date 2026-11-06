import {fetchJSON} from './common.js';
import {downloadCsvFromTable, sanitizeFileNamePart} from './table-common.js';
import {createInfoTooltip} from './info-tooltip.js';

const numberFormats = {
  integer: new Intl.NumberFormat('en-US', {maximumFractionDigits: 0}),
  decimal: new Intl.NumberFormat('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2}),
  percent: new Intl.NumberFormat('en-US', {style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2})
};
const dateFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', year: '2-digit', timeZone: 'UTC'
});
const periodDateFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC'
});
const captureFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  timeZone: 'Europe/Bucharest', hour12: false
});

export function normalizePerformancePeriod(value) {
  return value === '30days' || value === 'month' ? '30days' : '14days';
}

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

export function formatPerformancePeriod(period) {
  const start = parsePerformanceDate(period?.startDate);
  const end = parsePerformanceDate(period?.endDate);
  if (!start || !end || start > end) return '—';
  return `${periodDateFormat.format(start)} - ${periodDateFormat.format(end)}${period.provisional ? ' (provisional)' : ''}`;
}

function visitsDescription(row) {
  if (row.visitsStatus === 'estimated' || row.cellProvenance?.clicks?.quality === 'estimated') {
    return 'Estimated total eMAG visits, including paid and organic traffic. The historical source window may differ from the displayed report window; see the source and calculation below.';
  }
  const captured = typeof row.visitsCapturedAt === 'string'
    && /(?:Z|[+-]\d{2}:\d{2})$/.test(row.visitsCapturedAt)
    && !Number.isNaN(Date.parse(row.visitsCapturedAt))
    ? `${captureFormat.format(new Date(row.visitsCapturedAt))} (Romania)` : 'unavailable';
  const availability = row.visitsStatus === 'ambiguous'
    ? 'Multiple matching offers: visits cannot be attributed to one offer.'
    : row.visitsStatus === 'missing' || row.values?.clicks == null
      ? 'Visits are unavailable. — means unknown, not zero.' : 'Total eMAG visits, including paid and organic traffic.';
  return `${availability} ${row.visitsReason || ''} Rolling ${row.period.windowDays}-day counter; the displayed window is approximate because eMAG does not expose its exact cutoff. Snapshot: ${row.visitsSnapshotDate || 'unavailable'}. Captured: ${captured}.`.replace(/ {2,}/g, ' ');
}

function conversionDescription(row, column) {
  const formula = column.description || 'Units from Orders / Clicks × 100, for the selected account and period. Returns are not subtracted from Orders.';
  let availability = '';
  if (row.values?.conversion == null) {
    availability = row.values?.clicks != null && row.values.clicks <= 0
      ? 'Conversion requires a positive number of Clicks; — is not 0%.'
      : row.values?.orders == null
        ? 'Ordered units are unavailable; — is not 0%.'
        : 'Conversion is unavailable because Clicks could not be determined; — is not 0%.';
  }
  return `${formula} ${availability} ${visitsDescription(row)}`.replace(/ {2,}/g, ' ');
}

function performanceDescription(row) {
  if (row.performanceStatus === 'estimated' || row.cellProvenance?.performance?.quality === 'estimated') {
    return `Historical eMAG 30-day classification. It was observed on ${row.performanceSnapshotDate || row.cellProvenance?.performance?.sourceDate || 'an unavailable date'}, which may differ from this period end date; it is not a separate 14-day classification.`;
  }
  const availability = row.performanceStatus === 'ambiguous'
    ? 'Multiple matching offers prevent a reliable performance classification.'
    : row.performanceStatus === 'missing' || row.values?.performance == null
      ? 'No performance classification is available for this period end date.'
      : 'Classification reported by eMAG.';
  return `${availability} ${row.performanceReason || ''} This is the eMAG 30-day classification observed at the end of the period, including for 14-day rows. Snapshot: ${row.performanceSnapshotDate || 'unavailable'}.${row.performanceType ? ` Type: ${row.performanceType}.` : ''}`.replace(/ {2,}/g, ' ');
}

function provenanceDescription(provenance) {
  const quality = provenance.quality === 'estimated' ? 'Estimated history.' : 'Partial data; this value does not cover the complete report window.';
  return [quality,
    provenance.source && `Source: ${provenance.source}.`,
    provenance.sourceDate && `Source date: ${provenance.sourceDate}.`,
    provenance.sourceWindowDays != null && `Source window: ${provenance.sourceWindowDays} days.`,
    provenance.method && `Method: ${provenance.method}.`,
    provenance.details,
    provenance.sourceUrl && `Source URL: ${provenance.sourceUrl}`
  ].filter(Boolean).join(' ');
}

function describeProvenance(cell, row, column) {
  const provenance = row.cellProvenance?.[column.key];
  if (!provenance || !['estimated', 'partial'].includes(provenance.quality)) return;
  cell.classList.add(provenance.quality === 'estimated' ? 'pp-estimated' : 'pp-partial');
  cell.title = [cell.title, provenanceDescription(provenance)].filter(Boolean).join(' ');
  // A derived conversion must retain the specific source of its estimated Clicks input.
  if (column.key === 'conversion' && row.cellProvenance?.clicks?.quality === 'estimated') {
    cell.title += ` Clicks input: ${provenanceDescription(row.cellProvenance.clicks)}`;
  }
  cell.setAttribute('aria-label', `${cell.textContent}. ${column.label}. ${cell.title}`);
}

export function visiblePerformanceRows(rows, showEverything) {
  return showEverything ? rows : rows.slice(-20);
}

function describeColumn(element, column, includeValue = false) {
  if (typeof column.description !== 'string' || !column.description.trim()) return;
  element.title = column.description;
  element.setAttribute('aria-label',
    `${includeValue ? `${element.textContent}. ` : ''}${column.label}. ${column.description}`);
}

export function initProductPerformance() {
  const byId = suffix => document.getElementById(`productPerformance${suffix}`);
  const head = byId('Head');
  const body = byId('Body');
  const wrap = byId('Wrap');
  const status = byId('Status');
  const retry = byId('Retry');
  const download = byId('DownloadCsv');
  const table = byId('Table');
  const vendorSelect = byId('VendorSelect');
  const productSelect = byId('ProductSelect');
  const reportErrors = byId('Errors');
  const errorSummary = byId('ErrorSummary');
  const errorList = byId('ErrorList');
  const help = createInfoTooltip({document, prefix: 'productPerformance',
    label: 'About report periods and data quality', sections: [
      {title: 'Estimated history — yellow cells',
        text: 'Historical observations fill gaps in native data. Rolling 30-day totals are scaled by 14/30 for 14-day rows, assuming uniform activity, and used unchanged for 30-day rows. Source dates can differ from the row end. Stock, price, classification, rating and review observations are not scaled. Cell details and CSV retain the source date and calculation.'},
      {title: 'Partial data — ◐',
        text: 'Only part of the reporting window has native data. The value covers the available observations; it is not a complete-period total. Missing values (—) are different from known zeroes.'},
      {title: 'Provisional period',
        text: 'A full rolling 14- or 30-day preview ending on the latest completed observation before the next scheduled report close. It can overlap earlier rows; do not add it to closed rows. Provisional describes the reporting date, not whether clicks are estimated. Yellow cells independently identify estimates. A 30-day window is not necessarily a calendar month.'}
    ]});
  byId('InfoHost').appendChild(help.wrap);
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
  let periodMode = normalizePerformancePeriod(new URLSearchParams(window.location.search).get('period'));
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
    // Long date ranges must leave space to read advertising metrics while scrolling.
    table.dataset.compactOverview = String(left + 240 > wrap.clientWidth);
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
    } catch { /* A product without a URL still has useful period metrics. */ }
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
      ...column, group: group.key, groupLabel: group.label, groupStart: index === 0
    })));
    frozenIndexes = new Map(columns.filter(column => column.group === 'overview')
      .map((column, index) => [column.key, index]));
    const groupRow = document.createElement('tr');
    groupRow.className = 'pp-group-row';
    const columnRow = document.createElement('tr');
    columnRow.className = 'pp-column-row';

    for (const group of groups) {
      const period = group.columns.find(column => column.key === 'period');
      if (period) {
        const header = document.createElement('th');
        header.scope = 'col';
        header.rowSpan = 2;
        header.className = 'pp-week';
        const controls = document.createElement('div');
        controls.className = 'pp-period-controls';

        const periodSelect = document.createElement('select');
        periodSelect.className = 'pp-period-select';
        periodSelect.setAttribute('aria-label', 'Performance period');
        for (const [value, label] of [['14days', '14 days'], ['30days', '30 days']]) {
          const option = document.createElement('option');
          option.value = value;
          option.textContent = label;
          periodSelect.appendChild(option);
        }
        periodSelect.value = periodMode;
        periodSelect.addEventListener('change', () => {
          periodMode = normalizePerformancePeriod(periodSelect.value);
          updateSelectionUrl();
          // Period metrics and ratios are aggregated together on the server.
          load();
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
        markFrozen(header, period.key);
        groupRow.appendChild(header);
      }
      const metrics = group.columns.filter(column => column.key !== 'period');
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
        if (column.key === 'clicks') {
          header.title = 'Total eMAG visits for this rolling window, including paid and organic traffic. Hover over a value for its capture time and availability.';
        }
        describeColumn(header, column);
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

  function renderErrors(errors) {
    errorList.replaceChildren();
    reportErrors.hidden = !errors.length;
    reportErrors.open = false;
    if (!errors.length) {
      errorSummary.textContent = '';
      table.removeAttribute('aria-describedby');
      return;
    }
    errorSummary.textContent = `Partial report: ${errors.length} data ${errors.length === 1 ? 'issue' : 'issues'}. Affected metrics are shown as —.`;
    table.setAttribute('aria-describedby', 'productPerformanceErrorSummary');
    for (const error of errors) {
      const item = document.createElement('li');
      const matchTypes = Array.isArray(error.matchTypes) && error.matchTypes.length
        ? error.matchTypes.join(', ') : 'none';
      item.textContent = `Campaign ${error.campaignId} · Adset ${error.adsetId} · Report date ${error.reportDate} · Match types: ${matchTypes}. ${error.message || ''}`;
      errorList.appendChild(item);
    }
  }

  function renderRows(rows) {
    currentRows = rows;
    body.replaceChildren();
    const fragment = document.createDocumentFragment();
    for (const row of visiblePerformanceRows(rows, showEverything)) {
      const tr = document.createElement('tr');
      for (const column of columns) {
        const isPeriod = column.key === 'period';
        const cell = document.createElement(isPeriod ? 'th' : 'td');
        cell.textContent = isPeriod
          ? formatPerformancePeriod(row.period)
          : (['stock', 'salesPrice'].includes(column.key) && row.values?.[column.key] === '???'
              ? '???' : formatPerformanceValue(row.values?.[column.key], column.type));
        if (isPeriod) {
          cell.scope = 'row';
          cell.className = 'pp-week';
        } else {
          if (numberFormats[column.type]) cell.classList.add('numeric');
          if (column.groupStart) cell.classList.add('pp-group-start');
        }
        cell.dataset.type = column.type;
        describeColumn(cell, column, true);
        if (['stock', 'salesPrice'].includes(column.key) && row.values?.[column.key] === '???') {
          cell.title = column.key === 'salesPrice'
            ? 'Multiple matching offers in a daily snapshot for this period'
            : 'Multiple matching offers in the latest daily snapshot';
          cell.setAttribute('aria-label', cell.title);
        }
        if (column.key === 'clicks') {
          if (row.visitsStatus === 'ambiguous') cell.textContent = '???';
          else if (row.visitsStatus === 'missing') cell.textContent = '—';
          cell.title = visitsDescription(row);
          cell.setAttribute('aria-label', `${cell.textContent}. ${cell.title}`);
        }
        if (column.key === 'conversion') {
          cell.title = conversionDescription(row, column);
          cell.setAttribute('aria-label', `${cell.textContent}. Conv. ${cell.title}`);
        }
        if (column.key === 'performance') {
          if (row.performanceStatus === 'ambiguous') cell.textContent = '???';
          else if (row.performanceStatus === 'missing') cell.textContent = '—';
          cell.title = performanceDescription(row);
          cell.setAttribute('aria-label', `${cell.textContent}. Perform. ${cell.title}`);
        }
        describeProvenance(cell, row, column);
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
      status.textContent = `No ${periodMode === '14days' ? '14-day' : '30-day'} product performance data available.`;
      retry.hidden = false;
      return;
    }
    const first = formatPerformancePeriod(visibleRows[0].period);
    const last = formatPerformancePeriod(visibleRows[visibleRows.length - 1].period);
    const noun = 'period';
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
    download.disabled = true;
    byId('Product').hidden = true;
    currentRows = [];
    help.dismiss();
    body.replaceChildren();
    renderErrors([]);
    wrap.hidden = true;
  }

  function updateSelectionUrl() {
    const url = new URL(window.location.href);
    if (currentVendor) url.searchParams.set('vendorId', currentVendor.vendorId);
    else url.searchParams.delete('vendorId');
    if (currentProduct) url.searchParams.set('productCode', currentProduct.productCode);
    else url.searchParams.delete('productCode');
    url.searchParams.set('period', periodMode);
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
    const params = new URLSearchParams({vendorId: currentVendor.vendorId,
      productCode: currentProduct.productCode, period: periodMode});
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
      if (data.rows.some(row => formatPerformancePeriod(row.period) === '—'
          || ![14, 30].includes(row.period.windowDays)
          || typeof row.period.provisional !== 'boolean'
          || !['available', 'missing', 'ambiguous', 'estimated'].includes(row.visitsStatus))) {
        throw new Error('Invalid product performance period');
      }
      renderProduct(data.product || {});
      renderErrors(Array.isArray(data.errors) ? data.errors : []);
      renderHeaders(data.groups);
      table.setAttribute('aria-label', `${periodMode === '14days' ? '14-day' : '30-day'} product and advertising performance`);
      wrap.setAttribute('aria-label', `${periodMode === '14days' ? '14-day' : '30-day'} product performance; scroll to view all advertising groups`);
      renderRows(data.rows);
      wrap.hidden = false;
      updateFrozenOffsets();
      updateStatus(data.rows);
      download.disabled = !data.rows.length;
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

  download.addEventListener('click', () => {
    if (download.disabled || wrap.hidden || !currentVendor || !currentProduct) return;
    // Flatten grouped headers and omit the controls embedded in the period header.
    const exportTable = document.createElement('table');
    const header = document.createElement('tr');
    for (const column of columns) {
      const cell = document.createElement('th');
      cell.textContent = column.key === 'period' ? 'Period'
        : column.group === 'overview' ? column.label : `${column.groupLabel} - ${column.label}`;
      header.appendChild(cell);
    }
    const metadata = [
      ['Period start', row => row.period.startDate],
      ['Period end', row => row.period.endDate],
      ['Window days', row => row.period.windowDays],
      ['Provisional', row => row.period.provisional],
      ['Visits snapshot date', row => row.visitsSnapshotDate],
      ['Visits captured at', row => row.visitsCapturedAt],
      ['Visits status', row => row.visitsStatus],
      ['Visits reason', row => row.visitsReason],
      ['Performance snapshot date', row => row.performanceSnapshotDate],
      ['Performance status', row => row.performanceStatus],
      ['Performance reason', row => row.performanceReason],
      ['Performance type', row => row.performanceType],
      ['Cell provenance', row => JSON.stringify(row.cellProvenance || {})]
    ];
    for (const [label] of metadata) {
      const cell = document.createElement('th');
      cell.textContent = label;
      header.appendChild(cell);
    }
    exportTable.appendChild(header);
    const visibleRows = visiblePerformanceRows(currentRows, showEverything);
    let rowIndex = 0;
    for (const row of body.querySelectorAll('tr')) {
      const exportRow = document.createElement('tr');
      for (const sourceCell of row.cells) {
        const cell = document.createElement('td');
        cell.textContent = sourceCell.textContent;
        exportRow.appendChild(cell);
      }
      for (const [, value] of metadata) {
        const cell = document.createElement('td');
        cell.textContent = value(visibleRows[rowIndex]) ?? '';
        exportRow.appendChild(cell);
      }
      rowIndex++;
      exportTable.appendChild(exportRow);
    }
    const date = new Date().toISOString().slice(0, 10);
    const productCode = sanitizeFileNamePart(currentProduct.productCode);
    downloadCsvFromTable(exportTable,
      `product-performance-${currentVendor.vendorId}-${productCode}-${periodMode}-${date}.csv`);
  });
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
