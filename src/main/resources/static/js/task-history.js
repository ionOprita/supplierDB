import {fetchJSON} from './common.js';

const dayMilliseconds = 86_400_000;
const executionStatuses = ['RUNNING', 'SUCCESS', 'FAILED', 'INTERRUPTED'];
const coverageStatuses = ['COLLECTED', 'PENDING', 'RETRYING', 'MISSING'];
const timestampFormat = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  second: '2-digit', hour12: false, timeZone: 'Europe/Bucharest'
});

function dateValue(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : date;
}

export function taskHistoryToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Bucharest', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now);
  const part = type => parts.find(value => value.type === type).value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

export function formatTaskHistoryTime(value) {
  if (typeof value !== 'string' || !/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : timestampFormat.format(date);
}

export function initTaskHistory({documentRoot = document, request = fetchJSON,
  now = () => new Date()} = {}) {
  const byId = suffix => documentRoot.getElementById(`taskHistory${suffix}`);
  const coverage = suffix => documentRoot.getElementById(`taskCoverage${suffix}`);
  const panel = byId('Panel');
  if (!panel) return null;
  const form = byId('Filters');
  const from = byId('From');
  const to = byId('To');
  const task = byId('Task');
  const statusFilter = byId('StatusFilter');
  const apply = byId('Apply');
  const previous = byId('Previous');
  const next = byId('Next');
  const status = byId('Status');
  const pageLabel = byId('Page');
  const namesStatus = byId('NamesStatus');
  const body = byId('Body');
  const wrap = byId('Wrap');
  const coverageBody = coverage('Body');
  const coverageWrap = coverage('Wrap');
  const coverageStatus = coverage('Status');
  const controls = [from, to, task, statusFilter, apply];
  let initialized = false;
  let disposed = false;
  let version = 0;
  let page = 1;
  let total = 0;
  let busy = false;
  let namesLoaded = false;
  let appliedFilters = null;
  let appliedStatus = '';
  const today = taskHistoryToday(now());
  from.max = to.max = today;
  to.value = today;
  from.value = new Date(dateValue(today).getTime() - 29 * dayMilliseconds).toISOString().slice(0, 10);

  function element(tag, text) {
    const result = documentRoot.createElement(tag);
    if (text != null) result.textContent = String(text);
    return result;
  }

  function setBusy(value) {
    busy = value;
    controls.forEach(control => { control.disabled = value; });
    wrap.setAttribute('aria-busy', String(value));
    coverageWrap.setAttribute('aria-busy', String(value));
    previous.disabled = value || page <= 1;
    next.disabled = value || page * 50 >= total;
  }

  function readFilters() {
    const start = dateValue(from.value);
    const end = dateValue(to.value);
    if (!start || !end || start > end || to.value > taskHistoryToday(now())
        || (end - start) / dayMilliseconds >= 366
        || (statusFilter.value && !executionStatuses.includes(statusFilter.value))) return null;
    const params = new URLSearchParams({from: from.value, to: to.value});
    if (task.value) params.set('taskName', task.value);
    return params;
  }

  function errorCell(error) {
    const cell = element('td');
    if (!error) {
      cell.textContent = '—';
      return cell;
    }
    const details = element('details');
    details.className = 'task-history-error';
    const summary = element('summary', 'View error');
    const pre = element('pre', error);
    pre.tabIndex = 0;
    details.append(summary, pre);
    cell.appendChild(details);
    return cell;
  }

  function statusCell(value) {
    const cell = element('td');
    const badge = element('span', value.charAt(0) + value.slice(1).toLowerCase());
    badge.className = `task-history-badge task-history-badge--${value.toLowerCase()}`;
    cell.appendChild(badge);
    return cell;
  }

  function renderHistory(data) {
    if (!Array.isArray(data?.items) || !Number.isSafeInteger(data.total) || data.total < 0
        || data.page !== page || data.pageSize !== 50
        || data.items.some(item => typeof item?.taskName !== 'string'
          || !executionStatuses.includes(item.status))) throw new Error('Invalid history response');
    body.replaceChildren();
    for (const item of data.items) {
      const row = element('tr');
      row.append(element('td', item.taskName), element('td', formatTaskHistoryTime(item.startedAt)),
        element('td', formatTaskHistoryTime(item.finishedAt)), statusCell(item.status), errorCell(item.error));
      body.appendChild(row);
    }
    total = data.total;
    const pages = Math.max(1, Math.ceil(total / 50));
    pageLabel.textContent = `Page ${page} of ${pages}`;
    status.textContent = total ? `${total.toLocaleString('en-US')} ${total === 1 ? 'execution' : 'executions'} found.`
      : 'No executions in this date range.';
  }

  function renderCoverage(data) {
    if (!Array.isArray(data?.days) || data.days.some(day => typeof day?.taskName !== 'string'
        || !dateValue(day.date) || !coverageStatuses.includes(day.status)
        || !Number.isSafeInteger(day.attempts) || day.attempts < 0)) throw new Error('Invalid coverage response');
    coverageBody.replaceChildren();
    const days = [...data.days].sort((a, b) => b.date.localeCompare(a.date)
      || String(a.vendorName || a.vendorId || '').localeCompare(String(b.vendorName || b.vendorId || ''))
      || a.taskName.localeCompare(b.taskName));
    for (const day of days) {
      const row = element('tr');
      row.append(element('td', day.taskName), element('td', day.vendorName || day.vendorId || '—'),
        element('td', day.date), statusCell(day.status), element('td', formatTaskHistoryTime(day.fetchedAt)),
        element('td', day.attempts), errorCell(day.lastError));
      coverageBody.appendChild(row);
    }
    coverageStatus.textContent = days.length ? `${days.length.toLocaleString('en-US')} vendor ${days.length === 1 ? 'day' : 'days'}.`
      : 'No daily offers coverage for this task and date range.';
  }

  async function loadNames() {
    if (namesLoaded) return;
    try {
      const data = await request('/app/tasks');
      if (disposed) return;
      if (!Array.isArray(data)) throw new Error('Invalid tasks response');
      const names = [...new Set(data.map(item => item?.name).filter(name => typeof name === 'string' && name))].sort();
      const selected = task.value;
      task.replaceChildren(element('option', 'All tasks'));
      task.children[0].value = '';
      for (const name of names) {
        const option = element('option', name);
        option.value = name;
        task.appendChild(option);
      }
      if (names.includes(selected)) task.value = selected;
      namesStatus.textContent = '';
      namesLoaded = true;
    } catch {
      if (!disposed) namesStatus.textContent = 'Task names could not be loaded. All tasks remains available; apply filters to retry.';
    }
  }

  async function load(requestedPage = 1, includeCoverage = true) {
    if (disposed || busy) return;
    const filters = includeCoverage ? readFilters()
      : appliedFilters && new URLSearchParams(appliedFilters);
    if (!filters) {
      status.textContent = 'Choose a valid date range of up to 366 days, ending no later than today in Romania.';
      return;
    }
    const current = ++version;
    if (includeCoverage) {
      appliedFilters = filters.toString();
      appliedStatus = statusFilter.value;
    }
    page = requestedPage;
    body.replaceChildren();
    pageLabel.textContent = '';
    status.textContent = 'Loading execution history…';
    if (includeCoverage) {
      coverageBody.replaceChildren();
      coverageStatus.textContent = 'Loading daily coverage…';
    }
    setBusy(true);
    const historyParams = new URLSearchParams(filters);
    if (appliedStatus) historyParams.set('status', appliedStatus);
    historyParams.set('page', String(page));
    historyParams.set('pageSize', '50');
    const work = [request(`/app/tasks/history?${historyParams}`).then(data => {
      if (current === version && !disposed) renderHistory(data);
    }).catch(() => {
      if (current !== version || disposed) return;
      total = 0;
      status.textContent = 'Unable to load execution history. Apply filters to try again.';
    })];
    if (includeCoverage) work.push(request(`/app/tasks/coverage?${filters}`).then(data => {
      if (current === version && !disposed) renderCoverage(data);
    }).catch(() => {
      if (current === version && !disposed) coverageStatus.textContent = 'Unable to load daily coverage. Apply filters to try again.';
    }));
    if (!namesLoaded) work.push(loadNames());
    await Promise.all(work);
    if (current === version && !disposed) setBusy(false);
  }

  function onToggle() {
    if (panel.open && !initialized) {
      initialized = true;
      load();
    }
  }
  function onSubmit(event) { event.preventDefault(); load(); }
  function onPrevious() { if (!previous.disabled) load(page - 1, false); }
  function onNext() { if (!next.disabled) load(page + 1, false); }
  panel.addEventListener('toggle', onToggle);
  form.addEventListener('submit', onSubmit);
  previous.addEventListener('click', onPrevious);
  next.addEventListener('click', onNext);
  if (panel.open) onToggle();
  return {
    load,
    destroy() {
      disposed = true;
      ++version;
      panel.removeEventListener('toggle', onToggle);
      form.removeEventListener('submit', onSubmit);
      previous.removeEventListener('click', onPrevious);
      next.removeEventListener('click', onNext);
    }
  };
}

if (typeof document !== 'undefined' && document.getElementById('taskHistoryPanel')) {
  initTaskHistory();
}
