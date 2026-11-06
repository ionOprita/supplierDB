import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/task-history.js', import.meta.url), 'utf8');
const {initTaskHistory, taskHistoryToday, formatTaskHistoryTime} = await import(moduleUrl(
  source.replace("'./common.js'", JSON.stringify(common))));
const template = await readFile(new URL('../../main/jte/tasks.jte', import.meta.url), 'utf8');

class Element {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this.value = '';
    this.disabled = false;
    this.open = false;
    this.text = '';
  }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  replaceChildren(...children) { this.children = []; this.text = ''; this.append(...children); }
  addEventListener(type, handler) { this.listeners.set(type, handler); }
  removeEventListener(type, handler) { if (this.listeners.get(type) === handler) this.listeners.delete(type); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name); }
  fire(type) { return this.listeners.get(type)?.({preventDefault() {}}); }
  click() { if (!this.disabled) return this.fire('click'); }
}

function fixture(t) {
  const elements = new Map([...template.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)]
    .map(([, tag, id]) => [id, new Element(tag)]));
  const documentRoot = {
    getElementById: id => elements.get(id) || null,
    createElement: tag => new Element(tag)
  };
  const requests = [];
  const request = url => new Promise((resolve, reject) => requests.push({
    url: new URL(url, 'https://server.sellfusion.ro'), resolve, reject
  }));
  const controller = initTaskHistory({documentRoot, request, now: () => new Date('2026-11-05T12:00:00Z')});
  t.after(() => controller.destroy());
  const byId = suffix => elements.get(`taskHistory${suffix}`);
  const coverage = suffix => elements.get(`taskCoverage${suffix}`);
  const matching = (path, n = 0) => requests.filter(item => item.url.pathname === `/app/tasks${path}`)[n];
  return {
    controller, requests, byId, coverage, matching,
    open() { byId('Panel').open = true; byId('Panel').fire('toggle'); },
    async respond(path, data, n = 0) { matching(path, n).resolve(data); await setImmediate(); },
    async reject(path, n = 0) { matching(path, n).reject(new Error('<script>private error</script>')); await setImmediate(); }
  };
}

const execution = (overrides = {}) => ({id: 1, taskName: 'Update offers',
  startedAt: '2026-11-05T12:00:00Z', finishedAt: '2026-11-05T12:00:10Z',
  status: 'SUCCESS', error: null, ...overrides});
const history = (items = [execution()], overrides = {}) => ({items, total: items.length, page: 1, pageSize: 50, ...overrides});
const day = (overrides = {}) => ({taskName: 'Update offers', vendorId: 'vendor-a', vendorName: 'Vendor A',
  date: '2026-11-05', status: 'COLLECTED', fetchedAt: '2026-11-05T12:00:10Z', attempts: 1,
  lastError: null, ...overrides});

async function complete(page, historyData = history(), coverageData = {days: [day()]}) {
  await page.respond('/history', historyData);
  await page.respond('/coverage', coverageData);
  await page.respond('', [{name: 'Update offers'}, {name: 'Another task'}, {name: 'Update offers'}]);
}

test('Romanian dates use Bucharest even when the UTC day differs and times require an offset', () => {
  assert.equal(taskHistoryToday(new Date('2026-11-05T22:30:00Z')), '2026-11-06');
  assert.equal(formatTaskHistoryTime('2026-11-05T22:30:00Z'), '06 Nov 2026, 00:30:00');
  assert.equal(formatTaskHistoryTime('2026-07-01T22:30:00Z'), '02 Jul 2026, 01:30:00');
  assert.equal(formatTaskHistoryTime(null), '—');
  assert.equal(formatTaskHistoryTime('2026-11-05T12:00:00'), '—');
});

test('history loads lazily with 30 inclusive Romanian dates and disables controls while pending', async t => {
  const page = fixture(t);
  assert.equal(page.requests.length, 0);
  assert.equal(page.byId('From').value, '2026-10-07');
  assert.equal(page.byId('To').value, '2026-11-05');
  page.open();
  assert.equal(page.requests.length, 3);
  assert.equal(page.byId('Apply').disabled, true);
  assert.equal(page.byId('Task').disabled, true);
  assert.equal(page.byId('Wrap').getAttribute('aria-busy'), 'true');
  assert.equal(page.matching('/history').url.search, '?from=2026-10-07&to=2026-11-05&page=1&pageSize=50');
  assert.equal(page.matching('/coverage').url.search, '?from=2026-10-07&to=2026-11-05');
  await complete(page);
  assert.equal(page.byId('Apply').disabled, false);
  assert.deepEqual(page.byId('Task').children.map(item => item.textContent), ['All tasks', 'Another task', 'Update offers']);
  assert.equal(page.byId('Body').children[0].children[1].textContent, '05 Nov 2026, 14:00:00');
  assert.equal(page.byId('Body').children[0].children[3].textContent, 'Success');
  assert.equal(page.byId('Page').textContent, 'Page 1 of 1');
  assert.equal(page.coverage('Body').children[0].children[3].textContent, 'Collected');
  page.byId('Panel').open = false;
  page.byId('Panel').fire('toggle');
  page.open();
  assert.equal(page.requests.length, 3, 'Opening again retains the report instead of adding listeners or requests');
});

test('exact task and status filters apply to history while coverage ignores execution status', async t => {
  const page = fixture(t);
  page.open();
  await complete(page);
  page.byId('Task').value = 'Update offers / A&B';
  page.byId('StatusFilter').value = 'FAILED';
  page.byId('From').value = '2026-10-01';
  page.byId('Filters').fire('submit');
  const query = page.matching('/history', 1).url.searchParams;
  assert.equal(query.get('taskName'), 'Update offers / A&B');
  assert.equal(query.get('status'), 'FAILED');
  assert.equal(query.get('from'), '2026-10-01');
  const coverageQuery = page.matching('/coverage', 1).url.searchParams;
  assert.equal(coverageQuery.get('taskName'), 'Update offers / A&B');
  assert.equal(coverageQuery.has('status'), false);
  assert.equal(page.byId('Body').textContent, '');
  assert.equal(page.coverage('Body').textContent, '');
  await page.respond('/history', history([], {total: 0}), 1);
  await page.respond('/coverage', {days: []}, 1);
  assert.match(page.byId('Status').textContent, /No executions/);
  assert.match(page.coverage('Status').textContent, /No daily offers coverage/);
});

test('pagination stays at 50 and retains applied filters without reloading coverage', async t => {
  const page = fixture(t);
  page.open();
  await complete(page, history([execution()], {total: 51}));
  assert.equal(page.byId('Next').disabled, false);
  assert.equal(page.byId('Previous').disabled, true);
  page.byId('Task').value = 'Unapplied task';
  page.byId('Next').click();
  assert.equal(page.matching('/history', 1).url.searchParams.get('page'), '2');
  assert.equal(page.matching('/history', 1).url.searchParams.has('taskName'), false);
  assert.equal(page.requests.filter(item => item.url.pathname.endsWith('/coverage')).length, 1);
  await page.respond('/history', history([execution({id: 51})], {page: 2, total: 51}), 1);
  assert.equal(page.byId('Page').textContent, 'Page 2 of 2');
  assert.equal(page.byId('Next').disabled, true);
  assert.equal(page.byId('Previous').disabled, false);
  page.byId('Previous').click();
  assert.equal(page.matching('/history', 2).url.searchParams.get('page'), '1');
});

test('invalid dates, future dates and ranges over 366 days do not send requests', async t => {
  const page = fixture(t);
  for (const [from, to] of [['2026-02-30', '2026-11-05'], ['2026-11-05', '2026-11-04'],
    ['2026-11-01', '2026-11-06'], ['2025-11-04', '2026-11-05']]) {
    page.byId('From').value = from;
    page.byId('To').value = to;
    await page.controller.load();
    assert.equal(page.requests.length, 0);
    assert.match(page.byId('Status').textContent, /valid date range/);
  }
});

test('errors expand as literal safe text and missing or retrying vendor days stay visible', async t => {
  const page = fixture(t);
  page.open();
  const unsafe = '<script>alert("error")</script>\n\tException details';
  await complete(page, history([execution({taskName: '<img src=x>', status: 'FAILED', error: unsafe})]),
    {days: [day({date: '2026-11-04', status: 'MISSING', fetchedAt: null, attempts: 0}),
      day({vendorName: 'Vendor B', status: 'RETRYING', lastError: unsafe, attempts: 2})]});
  const executionRow = page.byId('Body').children[0];
  assert.equal(executionRow.children[0].textContent, '<img src=x>');
  const details = executionRow.children[4].children[0];
  assert.equal(details.tag, 'details');
  assert.equal(details.children[0].textContent, 'View error');
  assert.equal(details.children[1].textContent, unsafe);
  assert.equal(details.children[1].children.length, 0);
  const rows = page.coverage('Body').children;
  assert.equal(rows[0].children[3].textContent, 'Retrying');
  assert.equal(rows[0].children[6].children[0].children[1].textContent, unsafe);
  assert.equal(rows[1].children[3].textContent, 'Missing');
  assert.equal(rows[1].children[4].textContent, '—');
  assert.equal(rows[1].children[5].textContent, '0');
  assert.match(template, /missed past days cannot be recovered/);
});

test('one failed report keeps the other result usable and Apply retries without exposing error bodies', async t => {
  const page = fixture(t);
  page.open();
  await page.reject('/history');
  await page.respond('/coverage', {days: [day()]});
  await page.reject('');
  assert.match(page.byId('Status').textContent, /Unable to load execution history/);
  assert.doesNotMatch(page.byId('Status').textContent, /script|private error/);
  assert.equal(page.coverage('Body').children.length, 1);
  assert.match(page.byId('NamesStatus').textContent, /Task names could not be loaded/);
  assert.equal(page.byId('Apply').disabled, false);
  page.byId('Filters').fire('submit');
  await page.respond('/history', history(), 1);
  await page.reject('/coverage', 1);
  await page.respond('', [{name: 'Update offers'}], 1);
  assert.equal(page.byId('Body').children.length, 1);
  assert.match(page.coverage('Status').textContent, /Unable to load daily coverage/);
  assert.equal(page.byId('NamesStatus').textContent, '');
});

test('destroy removes handlers and ignores responses still in flight', async t => {
  const page = fixture(t);
  page.open();
  page.controller.destroy();
  await complete(page);
  assert.equal(page.byId('Body').children.length, 0);
  assert.equal(page.coverage('Body').children.length, 0);
  assert.equal(page.byId('Panel').listeners.size, 0);
  assert.equal(page.byId('Filters').listeners.size, 0);
});
