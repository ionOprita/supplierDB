const monthFormat = new Intl.DateTimeFormat('en-US', {month: 'long', year: 'numeric', timeZone: 'UTC'});
const axisFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits: 2});
const palette = ['#3b82f6', '#f97316', '#14b8a6', '#a855f7', '#ef4444', '#06b6d4', '#eab308', '#ec4899', '#6366f1', '#84cc16', '#d97706', '#64748b'];
const currencyError = 'GMV supports RON amounts only. Source data contains a missing or unsupported currency.';
const visibleErrors = new Set([currencyError, 'Your session expired. Sign in again and retry.',
  'Your session may have expired. Sign in again and retry.', 'You are not allowed to calculate GMV.']);

export function gmvCalendar(month) {
  if (typeof month !== 'string' || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || month.startsWith('0000')) {
    throw new Error('Invalid GMV month');
  }
  const end = new Date(`${month}-01T00:00:00Z`);
  end.setUTCMonth(end.getUTCMonth() + 1, 0);
  return Array.from({length: end.getUTCDate()}, (_, index) => `${month}-${String(index + 1).padStart(2, '0')}`);
}

export function gmvCents(value) {
  if (typeof value !== 'string' || !/^-?\d+\.\d{2}$/.test(value)) throw new Error('Invalid GMV amount');
  const negative = value.startsWith('-');
  const [whole, fraction] = (negative ? value.slice(1) : value).split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction);
  return negative ? -cents : cents;
}

export function formatDashboardGMV(value) {
  const cents = gmvCents(value);
  const absolute = cents < 0n ? -cents : cents;
  return `${cents < 0n ? '-' : ''}${(absolute / 100n).toLocaleString('en-US')}.${String(absolute % 100n).padStart(2, '0')} RON`;
}

function validateReport(report, currentMonth, requestedMonth) {
  const labels = gmvCalendar(report?.month);
  const through = labels.indexOf(report.throughDate);
  if (report.currency !== 'RON' || through < 0 || (requestedMonth && report.month !== requestedMonth)
      || !Array.isArray(report.days) || report.days.length !== through + 1
      || report.days.some((day, index) => day !== labels[index]) || !Array.isArray(report.companies)
      || (currentMonth && report.month < currentMonth && report.days.length !== labels.length)) {
    throw new Error('Invalid GMV report');
  }
  const keys = new Set();
  let grandTotal = 0n;
  for (const company of report.companies) {
    if (!company || typeof company.key !== 'string' || !company.key || keys.has(company.key)
        || typeof company.name !== 'string' || !Array.isArray(company.gmv) || company.gmv.length !== report.days.length) {
      throw new Error('Invalid GMV company');
    }
    keys.add(company.key);
    const total = company.gmv.reduce((sum, amount) => {
      if (!Number.isFinite(Number(amount))) throw new Error('GMV amount cannot be plotted');
      return sum + gmvCents(amount);
    }, 0n);
    if (total !== gmvCents(company.total)) throw new Error('GMV company total does not reconcile');
    grandTotal += total;
  }
  if (grandTotal !== gmvCents(report.total)) throw new Error('GMV total does not reconcile');
  return labels;
}

export function initGMVDashboard({document = globalThis.document, fetch = globalThis.fetch,
  Chart = globalThis.Chart, getComputedStyle = globalThis.getComputedStyle} = {}) {
  const byId = suffix => document.getElementById(`gmvDashboard${suffix}`);
  const dashboard = byId('');
  if (!dashboard) return null;
  const monthInput = byId('MonthSelect');
  const calculate = byId('Calculate');
  const status = byId('Status');
  const cards = byId('Cards');
  const colors = new Map();
  let panel = null;
  let currentMonth = null;
  let displayedMonth = null;
  let selectedKey = '';
  let loading = false;
  let disposed = false;
  let localError = null;
  const monthName = month => monthFormat.format(new Date(`${month}-01T00:00:00Z`));

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function validSelection() {
    if (!currentMonth) return true;
    try { gmvCalendar(monthInput.value); } catch { return false; }
    return monthInput.value <= currentMonth;
  }

  function updateControls() {
    calculate.disabled = loading || disposed || !validSelection();
    monthInput.disabled = loading || currentMonth === null;
    if (panel) panel.select.disabled = loading || panel.report.companies.length === 0;
  }

  function companyNames(report) {
    const groups = new Map();
    for (const company of report.companies) {
      const keys = groups.get(company.name) ?? [];
      keys.push(company.key);
      groups.set(company.name, keys);
    }
    for (const keys of groups.values()) keys.sort();
    return new Map(report.companies.map(company => {
      const keys = groups.get(company.name);
      return [company.key, keys.length === 1 ? company.name : `${company.name} (${keys.indexOf(company.key) + 1})`];
    }));
  }

  function createInfo(report) {
    const wrap = element('div', 'overview-info');
    wrap.id = 'gmvDashboardInfoWrap';
    const button = element('button', 'overview-info-button');
    button.id = 'gmvDashboardInfo';
    button.type = 'button';
    button.setAttribute('aria-label', 'About GMV');
    button.setAttribute('aria-describedby', 'gmvDashboardInfoText');
    button.setAttribute('aria-controls', 'gmvDashboardInfoText');
    const icon = element('span', 'overview-info-icon', 'i');
    icon.setAttribute('aria-hidden', 'true');
    button.appendChild(icon);
    const tooltip = element('div', 'overview-info-text');
    tooltip.id = 'gmvDashboardInfoText';
    tooltip.setAttribute('role', 'tooltip');
    tooltip.textContent = `Daily GMV in RON through ${report.throughDate}. `
      + (report.month === (currentMonth ?? report.month) ? 'Today is still in progress. ' : 'Full month. ')
      + 'GMV includes VAT and seller-funded voucher adjustments, deducts storno, and excludes shipping. '
      + 'Daily values use the same allocation as monthly GMV. Later changes can revise earlier days.';
    let hovered = false;
    let focused = false;
    let pinned = false;
    let dismissed = false;
    const update = () => {
      tooltip.hidden = dismissed || !(hovered || focused || pinned);
      button.setAttribute('aria-expanded', String(!tooltip.hidden));
    };
    const reset = () => { if (!hovered && !focused) dismissed = false; };
    const enter = event => { if (event.pointerType !== 'touch') { reset(); hovered = true; update(); } };
    const leave = () => { hovered = false; reset(); update(); };
    const focus = () => { reset(); focused = true; update(); };
    const blur = event => {
      if (wrap.contains(event.relatedTarget)) return;
      focused = false; pinned = false; reset(); update();
    };
    const click = () => { pinned = !pinned; dismissed = !pinned; update(); };
    const listeners = [[wrap, 'pointerenter', enter], [wrap, 'pointerleave', leave],
      [button, 'focus', focus], [button, 'blur', blur], [button, 'click', click]];
    listeners.forEach(([target, type, listener]) => target.addEventListener(type, listener));
    wrap.appendChild(button);
    wrap.appendChild(tooltip);
    update();
    return {wrap, tooltip, dismiss() { pinned = false; dismissed = true; update(); },
      destroy() { listeners.forEach(([target, type, listener]) => target.removeEventListener(type, listener)); }};
  }

  function selection(report, labels, names, key) {
    const visible = key ? report.companies.filter(company => company.key === key) : report.companies;
    const scope = key ? names.get(key) : 'All companies';
    const total = key ? visible[0].total : report.total;
    const styles = getComputedStyle?.(dashboard);
    const textColor = styles?.getPropertyValue('--muted').trim() || '#5f6f85';
    const gridColor = styles?.getPropertyValue('--border').trim() || '#dce3ee';
    return {scope, total, config: {
      type: 'line',
      data: {labels, datasets: visible.map(company => ({
        label: names.get(company.key), data: labels.map((_, day) => day < company.gmv.length ? Number(company.gmv[day]) : null),
        rawGMV: company.gmv, borderColor: colors.get(company.key), backgroundColor: colors.get(company.key),
        borderWidth: 2, fill: false, cubicInterpolationMode: 'monotone', tension: 0.3,
        spanGaps: false, pointRadius: 2, pointHoverRadius: 4, pointHitRadius: 8
      }))},
      options: {
        responsive: true, maintainAspectRatio: false, animation: false,
        interaction: {mode: 'index', intersect: false},
        scales: {
          x: {title: {display: true, text: 'Day of month', color: textColor}, grid: {display: false},
            ticks: {color: textColor, maxRotation: 0, maxTicksLimit: 12,
              callback(value) { return String(Number(this.getLabelForValue(value).slice(-2))); }}},
          y: {beginAtZero: true, title: {display: true, text: 'GMV (RON)', color: textColor},
            grid: {color: gridColor}, ticks: {color: textColor, callback: value => axisFormat.format(value)}}
        },
        plugins: {
          legend: {display: true, position: 'bottom', onClick() {},
            labels: {color: textColor, usePointStyle: true, boxWidth: 8, boxHeight: 8}},
          tooltip: {callbacks: {
            title: items => items[0]?.label ?? '',
            label: context => `${context.dataset.label}: ${formatDashboardGMV(context.dataset.rawGMV[context.dataIndex])}`
          }}
        }
      }
    }};
  }

  function newCanvas() {
    const canvas = element('canvas');
    canvas.id = 'gmvDashboardChart';
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-describedby', 'gmvDashboardSummary');
    return canvas;
  }

  function setSummary(target, data) {
    target.total.textContent = formatDashboardGMV(data.total);
    target.scope.textContent = `${data.scope} · ${target.report.month === (currentMonth ?? target.report.month) ? 'month to date' : 'monthly total'}`;
    target.canvas.setAttribute('aria-label', `${data.scope}: daily GMV in RON in ${target.report.month}, through ${target.report.throughDate}`);
  }

  function buildPanel(report, labels, names, key) {
    const card = element('article', 'overview-card');
    card.setAttribute('aria-labelledby', 'gmvDashboardTitle');
    const header = element('div', 'overview-card-header');
    const heading = element('div', 'overview-card-heading');
    const info = createInfo(report);
    const title = element('h2', 'overview-card-title', 'GMV');
    title.id = 'gmvDashboardTitle';
    heading.appendChild(info.wrap);
    heading.appendChild(title);
    header.appendChild(heading);
    const field = element('div', 'overview-company-field');
    const label = element('label', '', 'Company');
    label.setAttribute('for', 'gmvDashboardCompany');
    const select = element('select');
    select.id = 'gmvDashboardCompany';
    for (const [value, name] of [['', 'All companies'], ...report.companies.map(company => [company.key, names.get(company.key)])]) {
      const option = element('option', '', name);
      option.value = value;
      select.appendChild(option);
    }
    select.value = key;
    field.appendChild(label);
    field.appendChild(select);
    header.appendChild(field);
    card.appendChild(header);
    const summary = element('p', 'overview-total');
    summary.id = 'gmvDashboardSummary';
    const total = element('strong');
    total.id = 'gmvDashboardTotal';
    const scope = element('span');
    scope.id = 'gmvDashboardScope';
    summary.appendChild(total);
    summary.appendChild(scope);
    card.appendChild(summary);
    const wrap = element('div', 'overview-chart');
    const canvas = newCanvas();
    wrap.appendChild(canvas);
    card.appendChild(wrap);
    const target = {card, info, select, total, scope, wrap, canvas, report, labels, names, key};
    const data = selection(report, labels, names, key);
    target.config = data.config;
    setSummary(target, data);
    target.onChange = () => filterCompany(target);
    select.addEventListener('change', target.onChange);
    return target;
  }

  function filterCompany(target) {
    if (loading || disposed) return;
    const key = target.report.companies.some(company => company.key === target.select.value) ? target.select.value : '';
    const data = selection(target.report, target.labels, target.names, key);
    const oldCanvas = target.canvas;
    const canvas = newCanvas();
    target.wrap.replaceChildren(canvas);
    try {
      const replacement = new Chart(canvas, data.config);
      target.chart.destroy();
      target.chart = replacement;
      target.canvas = canvas;
      target.key = selectedKey = key;
      target.select.value = key;
      setSummary(target, data);
      if (localError) {
        status.dataset.state = localError.state;
        status.textContent = localError.text;
        localError = null;
      }
    } catch {
      target.wrap.replaceChildren(oldCanvas);
      target.select.value = target.key;
      localError ??= {state: status.dataset.state, text: status.textContent};
      status.dataset.state = 'error';
      status.textContent = 'Unable to update the chart. Select a company to retry.';
    }
  }

  function destroyPanel(target) {
    if (!target) return;
    target.info.destroy();
    target.select.removeEventListener('change', target.onChange);
    target.chart?.destroy();
  }

  function render(report, requestedMonth) {
    const labels = validateReport(report, currentMonth, requestedMonth);
    for (const company of [...report.companies].sort((a, b) => a.key.localeCompare(b.key))) {
      if (!colors.has(company.key)) {
        const index = colors.size;
        colors.set(company.key, palette[index] ?? `hsl(${(index * 137.508 + 220) % 360} 65% 50%)`);
      }
    }
    const key = report.companies.some(company => company.key === selectedKey) ? selectedKey : '';
    const replacement = buildPanel(report, labels, companyNames(report), key);
    const oldCards = Array.from(cards.children);
    cards.replaceChildren(replacement.card);
    try { replacement.chart = new Chart(replacement.canvas, replacement.config); }
    catch (error) { destroyPanel(replacement); cards.replaceChildren(...oldCards); throw error; }
    destroyPanel(panel);
    panel = replacement;
    selectedKey = key;
    displayedMonth = report.month;
    currentMonth ??= report.month;
    monthInput.value = report.month;
    monthInput.max = currentMonth;
    byId('Month').textContent = monthName(report.month);
    byId('Empty').hidden = report.companies.length > 0;
  }

  async function load() {
    if (loading || disposed) return;
    if (!validSelection()) {
      status.dataset.state = 'error';
      status.textContent = `Choose a valid month up to ${monthName(currentMonth)}.`;
      localError = null;
      updateControls();
      return;
    }
    const requestedMonth = currentMonth ? monthInput.value : null;
    loading = true;
    localError = null;
    updateControls();
    dashboard.setAttribute('aria-busy', 'true');
    status.dataset.state = 'loading';
    status.textContent = 'Calculating GMV…';
    try {
      const response = await fetch(requestedMonth ? `/app/gmvDashboard?month=${encodeURIComponent(requestedMonth)}` : '/app/gmvDashboard',
        {headers: {Accept: 'application/json'}, cache: 'no-store'});
      if (response.redirected || response.status === 401) throw new Error('Your session expired. Sign in again and retry.');
      if (response.status === 403) throw new Error('You are not allowed to calculate GMV.');
      if (response.status === 422) throw new Error(currencyError);
      if (!response.ok) throw new Error('Unable to calculate GMV.');
      if (!(response.headers.get('content-type') || '').toLowerCase().includes('application/json')) {
        throw new Error('Your session may have expired. Sign in again and retry.');
      }
      const report = await response.json();
      if (disposed) return;
      render(report, requestedMonth);
      status.dataset.state = 'ready';
      status.textContent = '';
    } catch (error) {
      if (disposed) return;
      status.dataset.state = 'error';
      const failed = requestedMonth ? `Unable to calculate ${monthName(requestedMonth)}.` : 'Unable to calculate GMV.';
      status.textContent = `${failed} ${panel ? `Displayed figures are for ${monthName(displayedMonth)} from the previous successful calculation. ` : ''}`
        + `${visibleErrors.has(error?.message) ? `${error.message} ` : ''}Select Calculate GMV to retry.`;
    } finally {
      loading = false;
      if (!disposed) { dashboard.setAttribute('aria-busy', 'false'); updateControls(); }
    }
  }

  function inputMonth() {
    if (disposed || loading) return;
    updateControls();
    if (!validSelection()) {
      localError = null;
      status.dataset.state = 'error';
      status.textContent = `Choose a valid month up to ${monthName(currentMonth)}.`;
    }
  }
  const escape = event => { if (event.key === 'Escape' && panel && !panel.info.tooltip.hidden) panel.info.dismiss(); };
  const outside = event => { if (panel && !panel.info.wrap.contains(event.target)) panel.info.dismiss(); };
  calculate.addEventListener('click', load);
  monthInput.addEventListener('input', inputMonth);
  monthInput.addEventListener('change', load);
  document.addEventListener('keydown', escape);
  document.addEventListener('pointerdown', outside);
  const ready = load();
  return {ready, calculate: load, destroy() {
    disposed = true;
    calculate.removeEventListener('click', load);
    monthInput.removeEventListener('input', inputMonth);
    monthInput.removeEventListener('change', load);
    document.removeEventListener('keydown', escape);
    document.removeEventListener('pointerdown', outside);
    destroyPanel(panel);
    panel = null;
  }};
}

if (typeof document !== 'undefined') initGMVDashboard();
