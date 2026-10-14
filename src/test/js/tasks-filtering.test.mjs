// Run with: node --test src/test/js/tasks-filtering.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/table-common.js', import.meta.url), 'utf8');
const { initTaskTable } = await import(moduleUrl(source.replace('"./common.js"', JSON.stringify(common))));

class Element {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.dataset = {};
    this.value = '';
    this.classes = new Set();
    this.classList = {
      add: (...classes) => classes.forEach(value => this.classes.add(value)),
      toggle: (value, enabled) => enabled ? this.classes.add(value) : this.classes.delete(value)
    };
  }
  get rows() { return this.children; }
  get cells() { return this.children; }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return (this.text ?? '') + this.children.map(child => child.textContent).join(''); }
  set innerHTML(value) { this.children = []; this.text = ''; this.html = String(value); }
  get innerHTML() { return this.html ?? ''; }
  appendChild(child) {
    if (child.tag === 'fragment') this.children.push(...child.children);
    else this.children.push(child);
    return child;
  }
  replaceChildren(...children) { this.children = children; this.text = ''; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  fire(name) { return this.listeners.get(name)?.({ target: this }); }
  querySelectorAll(selector) {
    const className = selector.startsWith('.') ? selector.slice(1) : null;
    return this.children.flatMap(child => [
      ...(className != null && child.classes.has(className) ? [child] : []),
      ...child.querySelectorAll(selector)
    ]);
  }
}

const started = [2026, 10, 14, 10, 0, 0];
const terminated = [2026, 10, 14, 10, 5, 0];
const failedTask = name => ({ name, started, terminated, error: 'Previous failure' });
const lane = (name, taskNames, activeTaskName = null) => ({ lane: name, taskNames, activeTaskName });
const jsonResponse = payload => ({ ok: true, status: 200, json: async () => payload });

async function withTable(initialState, callback) {
  const original = {
    document: globalThis.document,
    window: globalThis.window,
    fetch: globalThis.fetch
  };
  const elements = {
    tasksHead: new Element('thead'),
    tasksBody: new Element('tbody'),
    tasksTable: new Element('table'),
    refreshBtn: new Element('button'),
    taskNameFilter: new Element('input'),
    taskStatusFilter: new Element('select'),
    taskLaneFilter: new Element('select'),
    taskActionStatus: new Element('p'),
    taskSchedulerStatus: new Element('p')
  };
  const state = { rows: [], paused: [], lanes: [], ...initialState };
  const requests = [];
  let autoRefresh;
  globalThis.document = {
    getElementById: id => elements[id] ?? null,
    createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('fragment')
  };
  globalThis.window = {
    addEventListener() {},
    setInterval(callback) { autoRefresh = callback; }
  };
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, method: options.method ?? 'GET' });
    if (options.method === 'POST') {
      assert.equal(typeof state.onPost, 'function', 'Unexpected task action');
      return state.onPost(url);
    }
    if (url === '/app/tasks') {
      return state.failTasks ? { ok: false, status: 503 } : jsonResponse(state.rows);
    }
    if (url === '/app/tasks/paused') return jsonResponse(state.paused);
    if (url === '/app/tasks/active') return jsonResponse({ lanes: state.lanes });
    throw new Error(`Unexpected URL: ${url}`);
  };

  const names = () => elements.tasksBody.rows.map(row => row.cells[1].textContent);
  const setFilter = (elementId, value) => {
    const element = elements[elementId];
    element.value = value;
    element.fire(element.tag === 'input' ? 'input' : 'change');
  };
  const buttonFor = (taskName, buttonClass) => {
    const row = elements.tasksBody.rows.find(candidate => candidate.cells[1].textContent === taskName);
    assert.ok(row, `Missing task row: ${taskName}`);
    return row.querySelectorAll(`.${buttonClass}`)[0];
  };

  try {
    initTaskTable({
      tableId: 'tasksTable', theadId: 'tasksHead', tbodyId: 'tasksBody',
      dataUrl: '/app/tasks', pausedDataUrl: '/app/tasks/paused', activeDataUrl: '/app/tasks/active',
      actionStatusId: 'taskActionStatus', schedulerStatusId: 'taskSchedulerStatus',
      nameFilterInputId: 'taskNameFilter',
      statusFilterSelectId: 'taskStatusFilter', laneFilterSelectId: 'taskLaneFilter',
      canRunTasks: true,
      runUrlBuilder: taskName => `/admin/tasks/${encodeURIComponent(taskName)}/run`,
      pauseUrlBuilder: taskName => `/admin/tasks/${encodeURIComponent(taskName)}/pause`,
      resumeUrlBuilder: taskName => `/admin/tasks/${encodeURIComponent(taskName)}/resume`,
      refreshIntervalMs: 10_000
    });
    await setImmediate();
    await callback({
      elements, state, requests, names, setFilter, buttonFor,
      refresh: () => elements.refreshBtn.fire('click'),
      autoRefresh: () => autoRefresh()
    });
  } finally {
    Object.assign(globalThis, original);
  }
}

test('combines local name, status, and lane filters and clears only the name', async () => {
  const rows = [
    failedTask('Fetch reviews'),
    { name: 'REfetch Orders', started },
    failedTask('Fetch ads alpha'),
    failedTask('Fetch ads beta'),
    failedTask('Fetch Google data'),
    failedTask('Historical task'),
    failedTask('Fetch public reviews')
  ];
  await withTable({
    rows,
    paused: ['Fetch ads beta'],
    lanes: [
      lane('emagApiLane', ['Fetch reviews', 'REfetch Orders']),
      lane('emagDashboardLane:alpha', ['Fetch ads alpha']),
      lane('emagDashboardLane:beta', ['Fetch ads beta']),
      lane('googleApiLane', ['Fetch Google data']),
      lane('emagReviewsLane', ['Fetch public reviews'])
    ]
  }, async ({ elements, requests, names, setFilter }) => {
    assert.deepEqual(names(), rows.map(row => row.name));
    const loadedRequestCount = requests.length;

    setFilter('taskNameFilter', 'fE');
    assert.deepEqual(names(), rows.filter(row => row.name.toLowerCase().includes('fe')).map(row => row.name));
    setFilter('taskStatusFilter', 'ERROR');
    setFilter('taskLaneFilter', 'emagApiLane');
    assert.deepEqual(names(), ['Fetch reviews']);

    setFilter('taskNameFilter', 'CH ReV');
    assert.deepEqual(names(), ['Fetch reviews'], 'Matches a substring in the middle of the name');
    setFilter('taskNameFilter', 'no matching name');
    assert.deepEqual(names(), []);
    setFilter('taskNameFilter', '');
    assert.equal(elements.taskNameFilter.value, '');
    assert.equal(elements.taskStatusFilter.value, 'ERROR');
    assert.equal(elements.taskLaneFilter.value, 'emagApiLane');
    assert.deepEqual(names(), ['Fetch reviews']);

    setFilter('taskStatusFilter', '');
    setFilter('taskLaneFilter', 'emagDashboardLane');
    assert.deepEqual(names(), ['Fetch ads alpha', 'Fetch ads beta']);
    setFilter('taskStatusFilter', 'PAUSED');
    assert.deepEqual(names(), ['Fetch ads beta']);
    setFilter('taskStatusFilter', '');
    setFilter('taskLaneFilter', 'googleApiLane');
    assert.deepEqual(names(), ['Fetch Google data']);
    setFilter('taskLaneFilter', 'emagReviewsLane');
    assert.deepEqual(names(), ['Fetch public reviews']);
    setFilter('taskLaneFilter', '');
    assert.deepEqual(names(), rows.map(row => row.name), 'All includes tasks without configured lane metadata');
    assert.equal(requests.length, loadedRequestCount, 'Changing or clearing filters sends no requests');
  });
});

test('status filters follow displayed precedence for running, starting, paused, and error', async () => {
  await withTable({
    rows: [
      { name: 'Running with prior error', started, error: 'Previous failure' },
      failedTask('Starting with prior error'),
      failedTask('Paused with prior error'),
      failedTask('Failed'),
      { name: 'Successful', started, terminated, error: '   ' },
      { name: 'Never run' }
    ],
    paused: ['Running with prior error', 'Starting with prior error', 'Paused with prior error'],
    lanes: [lane('emagApiLane', ['Starting with prior error'], 'Starting with prior error')]
  }, async ({ elements, names, setFilter }) => {
    setFilter('taskStatusFilter', 'RUNNING');
    assert.deepEqual(names(), ['Running with prior error']);
    assert.match(elements.tasksBody.rows[0].cells[2].textContent, /^RUNNING /);
    setFilter('taskStatusFilter', 'PAUSED');
    assert.deepEqual(names(), ['Paused with prior error']);
    setFilter('taskStatusFilter', 'ERROR');
    assert.deepEqual(names(), ['Failed']);
    setFilter('taskStatusFilter', '');
    assert.equal(names().length, 6);
    assert.match(elements.tasksBody.rows[1].cells[2].textContent, /^STARTING /);
  });
});

test('retains filters and sorting across manual reload and automatic refresh', async () => {
  const taskNames = ['Task 2', 'Task 10', 'Task 1'];
  await withTable({
    rows: taskNames.map(failedTask),
    lanes: [lane('emagApiLane', ['Task 2', 'Task 10', 'Task 3', 'Task 4']), lane('googleApiLane', ['Task 1'])]
  }, async ({ elements, state, names, setFilter, refresh, autoRefresh }) => {
    const sortName = elements.tasksHead.rows[0].cells[1].children[0];
    sortName.fire('click');
    sortName.fire('click');
    setFilter('taskNameFilter', 'tAsK');
    setFilter('taskStatusFilter', 'ERROR');
    setFilter('taskLaneFilter', 'emagApiLane');
    assert.deepEqual(names(), ['Task 10', 'Task 2']);

    state.rows = [failedTask('Task 3'), { name: 'Task 2', started }, failedTask('Task 10'), failedTask('Task 1')];
    await refresh();
    assert.deepEqual(names(), ['Task 10', 'Task 3']);
    state.rows = [failedTask('Task 4'), failedTask('Task 10'), failedTask('Task 1')];
    await autoRefresh();
    assert.deepEqual(names(), ['Task 10', 'Task 4']);
    assert.equal(elements.tasksHead.rows[0].cells[1].attributes.get('aria-sort'), 'descending');
    assert.equal(elements.taskNameFilter.value, 'tAsK');
    assert.equal(elements.taskStatusFilter.value, 'ERROR');
    assert.equal(elements.taskLaneFilter.value, 'emagApiLane');

    setFilter('taskNameFilter', 'no match');
    sortName.fire('click');
    assert.deepEqual(names(), []);
    setFilter('taskNameFilter', '');
    assert.deepEqual(names(), ['Task 4', 'Task 10']);
  });
});

test('a filtered-out database-running task still blocks sibling tasks in its lane', async () => {
  await withTable({
    rows: [{ name: 'Hidden running task', started }, failedTask('Visible sibling')],
    lanes: [lane('emagApiLane', ['Hidden running task', 'Visible sibling'])]
  }, async ({ state, names, setFilter, buttonFor, autoRefresh }) => {
    setFilter('taskNameFilter', 'visible');
    assert.deepEqual(names(), ['Visible sibling']);
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, true);
    setFilter('taskStatusFilter', 'ERROR');
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, true);

    state.rows[0] = { name: 'Hidden running task', started, terminated };
    await autoRefresh();
    assert.deepEqual(names(), ['Visible sibling']);
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, false);
  });
});

test('pending and tracked runs retain lane blocking and completion feedback while hidden', async () => {
  let acceptRun;
  const acceptance = new Promise(resolve => { acceptRun = resolve; });
  await withTable({
    rows: [failedTask('Manual task'), failedTask('Visible sibling')],
    paused: ['Manual task'],
    lanes: [lane('emagApiLane', ['Manual task', 'Visible sibling'])],
    onPost: async () => acceptance
  }, async ({ elements, state, names, setFilter, buttonFor, autoRefresh }) => {
    const runningRequest = buttonFor('Manual task', 'task-run-button').fire('click');
    setFilter('taskStatusFilter', 'PAUSED');
    assert.deepEqual(names(), [], 'A pending manual run takes precedence over Paused');
    setFilter('taskStatusFilter', 'ERROR');
    assert.deepEqual(names(), ['Visible sibling']);
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, true);

    acceptRun({
      ok: true, status: 202, redirected: false,
      text: async () => JSON.stringify({ code: 'ACCEPTED', message: 'Manual task accepted.' })
    });
    await runningRequest;
    assert.deepEqual(names(), ['Visible sibling'], 'Checking a run result takes precedence over its prior error');
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, true);
    setFilter('taskStatusFilter', '');
    assert.equal(elements.tasksBody.rows[0].cells[2].textContent, 'CHECKING RESULT');
    setFilter('taskNameFilter', 'visible');
    state.rows[0] = {
      name: 'Manual task', started: [2026, 10, 14, 11, 0, 0],
      terminated: [2026, 10, 14, 11, 5, 0], error: 'New failure'
    };
    await autoRefresh();
    assert.deepEqual(names(), ['Visible sibling']);
    assert.match(elements.taskActionStatus.textContent, /Manual task.*failed/);
    assert.equal(buttonFor('Visible sibling', 'task-run-button').disabled, false);
  });
});

test('pause and resume actions reapply the selected status filter after reload', async () => {
  await withTable({
    rows: [failedTask('Paused error'), { name: 'Paused success', started, terminated }, failedTask('Failed')],
    paused: ['Paused error', 'Paused success'],
    lanes: [lane('emagApiLane', ['Paused error', 'Paused success', 'Failed'])],
    onPost(url) {
      const taskName = decodeURIComponent(url.split('/')[3]);
      if (url.endsWith('/resume')) this.paused = this.paused.filter(name => name !== taskName);
      else this.paused = [...this.paused, taskName];
      return { ok: true, status: 200 };
    }
  }, async ({ elements, requests, names, setFilter, buttonFor }) => {
    setFilter('taskStatusFilter', 'PAUSED');
    assert.deepEqual(names(), ['Paused error', 'Paused success']);
    await buttonFor('Paused error', 'task-pause-button').fire('click');
    assert.deepEqual(names(), ['Paused success']);
    assert.equal(elements.taskStatusFilter.value, 'PAUSED');

    setFilter('taskStatusFilter', 'ERROR');
    assert.deepEqual(names(), ['Paused error', 'Failed']);
    await buttonFor('Paused error', 'task-pause-button').fire('click');
    assert.deepEqual(names(), ['Failed']);
    assert.equal(elements.taskStatusFilter.value, 'ERROR');
    assert.deepEqual(requests.filter(request => request.method === 'POST').map(request => request.url), [
      '/admin/tasks/Paused%20error/resume', '/admin/tasks/Paused%20error/pause'
    ]);
  });
});

test('filter changes preserve a load failure and a successful retry reapplies current filters', async () => {
  await withTable({
    rows: [failedTask('Fetch alpha'), failedTask('Fetch beta')],
    lanes: [lane('emagApiLane', ['Fetch alpha']), lane('googleApiLane', ['Fetch beta'])]
  }, async ({ elements, state, requests, names, setFilter, refresh }) => {
    setFilter('taskNameFilter', 'fetch');
    setFilter('taskStatusFilter', 'ERROR');
    setFilter('taskLaneFilter', 'emagApiLane');
    assert.deepEqual(names(), ['Fetch alpha']);

    const originalConsoleError = console.error;
    const loadErrors = [];
    console.error = error => loadErrors.push(error);
    try {
      state.failTasks = true;
      await refresh();
      assert.match(elements.tasksBody.innerHTML, /Failed to load data/);
      assert.match(elements.taskActionStatus.textContent, /HTTP 503/);
      const failedRequestCount = requests.length;
      setFilter('taskNameFilter', 'beta');
      setFilter('taskLaneFilter', 'googleApiLane');
      setFilter('taskNameFilter', '');
      assert.match(elements.tasksBody.innerHTML, /Failed to load data/);
      assert.match(elements.taskActionStatus.textContent, /HTTP 503/);
      assert.equal(requests.length, failedRequestCount);

      state.failTasks = false;
      await refresh();
      assert.deepEqual(names(), ['Fetch beta']);
      assert.equal(elements.taskActionStatus.textContent, '');
      assert.equal(elements.taskNameFilter.value, '');
      assert.equal(elements.taskStatusFilter.value, 'ERROR');
      assert.equal(elements.taskLaneFilter.value, 'googleApiLane');
      assert.equal(loadErrors.length, 1);
    } finally {
      console.error = originalConsoleError;
    }
  });
});
