const integerFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits: 0});
const monthFormat = new Intl.DateTimeFormat('en-US', {month: 'long', year: 'numeric', timeZone: 'UTC'});
const companyPalette = ['#3b82f6', '#f97316', '#14b8a6', '#a855f7', '#ef4444', '#06b6d4', '#eab308', '#ec4899', '#6366f1', '#84cc16', '#d97706', '#64748b'];

export function overviewCalendar(month) {
  if (typeof month !== 'string' || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
    throw new Error('Invalid dashboard month');
  }
  const lastDay = new Date(`${month}-01T00:00:00Z`);
  lastDay.setUTCMonth(lastDay.getUTCMonth() + 1, 0);
  return Array.from({length: lastDay.getUTCDate()}, (_, index) => `${month}-${String(index + 1).padStart(2, '0')}`);
}

function validateReport(report) {
  const labels = overviewCalendar(report.month);
  const throughIndex = labels.indexOf(report.throughDate);
  if (throughIndex < 0 || !Array.isArray(report.days) || report.days.length !== throughIndex + 1
      || report.days.some((day, index) => day !== labels[index]) || !Array.isArray(report.companies)
      || !Number.isSafeInteger(report.unassignedReturns) || report.unassignedReturns < 0) {
    throw new Error('Invalid dashboard report');
  }
  const keys = new Set();
  for (const company of report.companies) {
    if (typeof company.key !== 'string' || !company.key || keys.has(company.key)
        || typeof company.name !== 'string' || ['orders', 'returns'].some(key =>
      !Array.isArray(company[key]) || company[key].length !== report.days.length
      || company[key].some(count => !Number.isSafeInteger(count) || count < 0))) {
      throw new Error('Invalid company counts');
    }
    keys.add(company.key);
  }
  return labels;
}

export function initOverview({document = globalThis.document, fetch = globalThis.fetch,
  Chart = globalThis.Chart, getComputedStyle = globalThis.getComputedStyle} = {}) {
  const byId = suffix => document.getElementById(`overview${suffix}`);
  const dashboard = byId('Dashboard');
  if (!dashboard) return null;
  const refreshButton = byId('Refresh');
  const monthInput = byId('MonthSelect');
  const status = byId('Status');
  const companies = byId('Companies');
  let panels = [];
  let loading = false;
  let hasReport = false;
  let disposed = false;
  let currentMonth = null;
  let displayedMonth = null;
  let localChartError = null;
  const companyColors = new Map();
  const selectedKeys = {orders: '', returns: ''};

  function monthName(month) {
    return monthFormat.format(new Date(`${month}-01T00:00:00Z`));
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function companyLabels(report) {
    const names = new Map();
    for (const company of report.companies) {
      const group = names.get(company.name) ?? [];
      group.push(company.key);
      names.set(company.name, group);
    }
    for (const keys of names.values()) keys.sort();
    return new Map(report.companies.map(company => {
      const group = names.get(company.name);
      return [company.key, group.length > 1 ? `${company.name} (${group.indexOf(company.key) + 1})` : company.name];
    }));
  }

  function series(report, labels, metric, selectedKey, names) {
    const title = metric === 'orders' ? 'Orders' : 'Returns';
    const visible = selectedKey ? report.companies.filter(company => company.key === selectedKey) : report.companies;
    const scope = selectedKey ? names.get(selectedKey) : 'All companies';
    const total = visible.reduce((sum, company) => sum + company[metric].reduce((count, value) => count + value, 0), 0);
    const styles = getComputedStyle?.(dashboard);
    const textColor = styles?.getPropertyValue('--muted').trim() || '#5f6f85';
    const gridColor = styles?.getPropertyValue('--border').trim() || '#dce3ee';
    return {scope, total, config: {
      type: 'line',
      data: {
        labels,
        datasets: visible.map(company => ({label: names.get(company.key),
          data: labels.map((_, day) => company[metric][day] ?? null),
          borderColor: companyColors.get(company.key), backgroundColor: companyColors.get(company.key), borderWidth: 2,
          fill: false, cubicInterpolationMode: 'monotone', tension: 0.3, spanGaps: false,
          pointRadius: 2, pointHoverRadius: 4, pointHitRadius: 8}))
      },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        interaction: {mode: 'index', intersect: false},
        scales: {
          x: {title: {display: true, text: 'Day of month', color: textColor}, grid: {display: false},
            ticks: {color: textColor, maxRotation: 0, autoSkip: true, maxTicksLimit: 12,
              callback(value) { return String(Number(this.getLabelForValue(value).slice(-2))); }}},
          y: {min: 0, beginAtZero: true, grid: {color: gridColor},
            ticks: {color: textColor, precision: 0, callback: value => Number.isInteger(value) ? integerFormat.format(value) : ''}}
        },
        plugins: {
          legend: {display: true, position: 'bottom', onClick() {},
            labels: {color: textColor, usePointStyle: true, boxWidth: 8, boxHeight: 8}},
          tooltip: {callbacks: {
            title: items => items[0]?.label ?? '',
            label: context => `${context.dataset.label}: ${integerFormat.format(context.parsed.y)} ${title.toLowerCase()}`
          }}
        }
      }
    }};
  }

  function setSummary(panel, selection) {
    const period = panel.report.month === (currentMonth ?? panel.report.month) ? 'month to date' : 'monthly total';
    panel.total.textContent = integerFormat.format(selection.total);
    panel.scope.textContent = `${selection.scope} · ${period}`;
    panel.canvas.setAttribute('aria-label', `${selection.scope}: daily ${panel.metric} in ${panel.report.month}, through ${panel.report.throughDate}`);
  }

  function canvasFor(panel) {
    const canvas = element('canvas');
    canvas.id = `${panel.prefix}Chart`;
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-describedby', `${panel.prefix}Summary`);
    return canvas;
  }

  function chartCard(metric, report, labels, names, selectedKey) {
    const title = metric === 'orders' ? 'Orders' : 'Returns';
    const prefix = `overview${title}`;
    const card = element('article', `overview-card overview-card--${metric}`);
    card.setAttribute('aria-labelledby', `${prefix}Title`);
    const header = element('div', 'overview-card-header');
    const heading = element('h2', 'overview-card-title', title);
    heading.id = `${prefix}Title`;
    header.appendChild(heading);
    const field = element('div', 'overview-company-field');
    const label = element('label', '', 'Company');
    label.setAttribute('for', `${prefix}Company`);
    const select = element('select');
    select.id = `${prefix}Company`;
    select.disabled = loading || report.companies.length === 0;
    const all = element('option', '', 'All companies');
    all.value = '';
    select.appendChild(all);
    for (const company of report.companies) {
      const option = element('option', '', names.get(company.key));
      option.value = company.key;
      select.appendChild(option);
    }
    select.value = selectedKey;
    field.appendChild(label);
    field.appendChild(select);
    header.appendChild(field);
    card.appendChild(header);
    const summary = element('p', 'overview-total');
    summary.id = `${prefix}Summary`;
    const total = element('strong');
    total.id = `${prefix}Total`;
    const scope = element('span');
    scope.id = `${prefix}Scope`;
    summary.appendChild(total);
    summary.appendChild(scope);
    card.appendChild(summary);
    const wrap = element('div', 'overview-chart');
    card.appendChild(wrap);
    const panel = {card, prefix, select, total, scope, wrap, metric, report, labels, names, selectedKey};
    panel.canvas = canvasFor(panel);
    wrap.appendChild(panel.canvas);
    const selection = series(report, labels, metric, selectedKey, names);
    setSummary(panel, selection);
    panel.config = selection.config;
    panel.onChange = () => filterPanel(panel);
    select.addEventListener('change', panel.onChange);
    return panel;
  }

  function filterPanel(panel) {
    if (loading || disposed) return;
    const key = panel.report.companies.some(company => company.key === panel.select.value) ? panel.select.value : '';
    const selection = series(panel.report, panel.labels, panel.metric, key, panel.names);
    const oldCanvas = panel.canvas;
    const canvas = canvasFor(panel);
    panel.wrap.replaceChildren(canvas);
    try {
      const replacement = new Chart(canvas, selection.config);
      panel.chart.destroy();
      panel.chart = replacement;
      panel.canvas = canvas;
      panel.selectedKey = key;
      selectedKeys[panel.metric] = key;
      panel.select.value = key;
      setSummary(panel, selection);
      if (localChartError?.metric === panel.metric) {
        status.dataset.state = localChartError.previousState;
        status.textContent = localChartError.previousText;
        localChartError = null;
      }
    } catch {
      panel.wrap.replaceChildren(oldCanvas);
      panel.select.value = panel.selectedKey;
      localChartError ??= {previousState: status.dataset.state, previousText: status.textContent};
      localChartError.metric = panel.metric;
      status.dataset.state = 'error';
      status.textContent = 'Unable to update the chart. Select a company to retry.';
    }
  }

  function destroyPanel(panel) {
    panel.select.removeEventListener('change', panel.onChange);
    panel.chart?.destroy();
  }

  function render(report) {
    const labels = validateReport(report);
    if (currentMonth && report.month < currentMonth && report.days.length !== labels.length) {
      throw new Error('Historical dashboard report must cover the full month');
    }
    for (const company of [...report.companies].sort((a, b) => a.key.localeCompare(b.key))) {
      if (!companyColors.has(company.key)) {
        const index = companyColors.size;
        companyColors.set(company.key, companyPalette[index] ?? `hsl(${(index * 137.508 + 220) % 360} 65% 50%)`);
      }
    }
    const names = companyLabels(report);
    const replacements = ['orders', 'returns'].map(metric => {
      const key = report.companies.some(company => company.key === selectedKeys[metric]) ? selectedKeys[metric] : '';
      return chartCard(metric, report, labels, names, key);
    });
    // Build the replacement before discarding the last successful report.
    const oldChildren = Array.from(companies.children);
    companies.replaceChildren(...replacements.map(panel => panel.card));
    try {
      for (const panel of replacements) panel.chart = new Chart(panel.canvas, panel.config);
    } catch (error) {
      replacements.forEach(destroyPanel);
      companies.replaceChildren(...oldChildren);
      throw error;
    }
    panels.forEach(destroyPanel);
    panels = replacements;
    for (const panel of panels) selectedKeys[panel.metric] = panel.selectedKey;
    byId('Month').textContent = monthName(report.month);
    byId('Context').textContent = `Daily orders and returns through ${report.throughDate}.`
      + (report.month === (currentMonth ?? report.month) ? ' Today is still in progress.' : ' Full month.');
    byId('Empty').hidden = report.companies.length > 0;
    const notice = byId('Unassigned');
    notice.hidden = report.unassignedReturns === 0;
    notice.textContent = report.unassignedReturns > 0
      ? `${integerFormat.format(report.unassignedReturns)} return${report.unassignedReturns === 1 ? '' : 's'} could not be assigned to a company and ${report.unassignedReturns === 1 ? 'is' : 'are'} excluded from these charts.`
      : '';
    hasReport = true;
    displayedMonth = report.month;
    currentMonth ??= report.month;
    monthInput.value = report.month;
    monthInput.max = currentMonth;
  }

  async function refresh() {
    if (loading || disposed) return;
    const requestedMonth = currentMonth ? monthInput.value : null;
    if (currentMonth && (!/^\d{4}-(0[1-9]|1[0-2])$/.test(requestedMonth)
        || requestedMonth.slice(0, 4) === '0000' || requestedMonth > currentMonth)) {
      status.dataset.state = 'error';
      status.textContent = `Choose a valid month up to ${monthName(currentMonth)}.`;
      localChartError = null;
      return;
    }
    localChartError = null;
    loading = true;
    refreshButton.disabled = true;
    monthInput.disabled = true;
    panels.forEach(panel => { panel.select.disabled = true; });
    dashboard.setAttribute('aria-busy', 'true');
    status.dataset.state = 'loading';
    status.textContent = hasReport ? 'Refreshing dashboard…' : 'Loading dashboard…';
    try {
      const url = requestedMonth ? `/app/overview?month=${encodeURIComponent(requestedMonth)}` : '/app/overview';
      const response = await fetch(url, {headers: {Accept: 'application/json'}, cache: 'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const report = await response.json();
      if (disposed) return;
      if (requestedMonth && report.month !== requestedMonth) throw new Error('Dashboard month does not match selection');
      render(report);
      status.dataset.state = 'ready';
      status.textContent = '';
    } catch {
      if (disposed) return;
      status.dataset.state = 'error';
      status.textContent = hasReport
        ? `Unable to load ${monthName(requestedMonth)}. Displayed figures are for ${monthName(displayedMonth)} from the previous successful load. Select Refresh to retry.`
        : 'Unable to load the dashboard. Select Refresh to retry.';
    } finally {
      loading = false;
      if (!disposed) {
        refreshButton.disabled = false;
        monthInput.disabled = currentMonth === null;
        panels.forEach(panel => { panel.select.disabled = panel.report.companies.length === 0; });
        dashboard.setAttribute('aria-busy', 'false');
      }
    }
  }

  refreshButton.addEventListener('click', refresh);
  monthInput.addEventListener('change', refresh);
  const ready = refresh();
  return {ready, refresh, destroy() {
    disposed = true;
    refreshButton.removeEventListener('click', refresh);
    monthInput.removeEventListener('change', refresh);
    panels.forEach(destroyPanel);
    panels = [];
  }};
}

if (typeof document !== 'undefined') initOverview();
