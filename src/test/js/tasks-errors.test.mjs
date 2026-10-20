// Run with: node --test src/test/js/tasks-errors.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const tableSource = await readFile(new URL('../../main/resources/static/js/table-common.js', import.meta.url), 'utf8');
const tableUrl = moduleUrl(tableSource.replace('"./common.js"', JSON.stringify(common)));
const {initTaskTable, renderTasksBody, sortTaskTableBody, toTaskRows} = await import(tableUrl);
const tasksSource = await readFile(new URL('../../main/resources/static/js/tasks.js', import.meta.url), 'utf8');
const {initTaskErrorDialog} = await import(moduleUrl(
  tasksSource.replace(/(['"])\.\/table-common\.js\1/, JSON.stringify(tableUrl))));

class Element {
  constructor(tag, ownerDocument) {
    this.tag = tag;
    this.ownerDocument = ownerDocument;
    this.children = [];
    this.dataset = {};
    this.attributes = new Map();
    this.listeners = new Map();
    this.classes = new Set();
    this.classList = {
      add: (...names) => names.forEach(name => this.classes.add(name)),
      toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
    };
  }
  get rows() { return this.children; }
  get cells() { return this.children; }
  get isConnected() { return this === this.ownerDocument.body || Boolean(this.parentNode?.isConnected); }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get textContent() { return (this.text ?? '') + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { this.replaceChildren(); }
  appendChild(child) {
    if (child.tag === 'fragment') {
      for (const nested of [...child.children]) this.appendChild(nested);
    } else {
      child.parentNode = this;
      this.children.push(child);
    }
    return child;
  }
  replaceChildren(...children) {
    for (const child of this.children) child.parentNode = null;
    this.children = [];
    this.text = '';
    for (const child of children) this.appendChild(child);
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
  }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  fire(name) { return this.listeners.get(name)?.({target: this, preventDefault() {}}); }
  click() {
    if (this.tag === 'a') this.ownerDocument.downloads.push({href: this.href, name: this.download});
    return this.fire('click');
  }
  focus() { this.ownerDocument.activeElement = this; }
  showModal() { this.open = true; }
  close() { this.open = false; this.fire('close'); }
  querySelectorAll(selector) {
    return this.children.flatMap(child => [
      ...((selector.startsWith('.') ? child.classes.has(selector.slice(1)) : child.tag === selector) ? [child] : []),
      ...child.querySelectorAll(selector)
    ]);
  }
}

function fixture() {
  const elements = {};
  const document = {
    downloads: [],
    getElementById: id => elements[id] ?? null,
    createElement: tag => new Element(tag, document),
    createDocumentFragment: () => new Element('fragment', document),
    querySelectorAll: selector => document.body.querySelectorAll(selector)
  };
  document.body = new Element('body', document);
  for (const [id, tag] of Object.entries({
    tasksTable: 'table', tasksHead: 'thead', tasksBody: 'tbody', refreshBtn: 'button',
    taskErrorDialog: 'dialog', taskErrorTitle: 'h2', taskErrorContent: 'pre',
    taskErrorDownload: 'button', taskErrorClose: 'button'
  })) {
    elements[id] = new Element(tag, document);
    document.body.appendChild(elements[id]);
  }
  return {document, elements};
}

async function withDocument(callback) {
  const original = {document: globalThis.document, window: globalThis.window, fetch: globalThis.fetch};
  const state = fixture();
  globalThis.document = state.document;
  globalThis.window = {addEventListener() {}};
  try {
    await callback(state);
  } finally {
    Object.assign(globalThis, original);
  }
}

test('only long errors become accessible previews, including Unicode and literal markup', async () => {
  await withDocument(({elements}) => {
    const errors = ['', 'x'.repeat(59), 'x'.repeat(60), 'x'.repeat(61), '😀'.repeat(61),
      '<script>alert("error")</script>\n\t' + 'details '.repeat(15)];
    const calls = [];
    renderTasksBody(elements.tasksBody, toTaskRows(errors.map((error, i) => ({name: `task ${i}`, error}))), {
      onViewError: (...args) => calls.push(args)
    });
    const cells = elements.tasksBody.rows.map(row => row.cells[8]);
    for (let i = 0; i < 3; i++) {
      assert.equal(cells[i].textContent, errors[i]);
      assert.equal(cells[i].children.length, 0);
    }
    for (let i = 3; i < errors.length; i++) {
      const button = cells[i].children[0];
      assert.equal(button.tag, 'button');
      assert.equal(button.type, 'button');
      assert.equal(button.textContent, [...errors[i]].slice(0, 59).join('') + '…');
      assert.equal([...button.textContent].length, 60);
      assert.equal(button.children.length, 0);
      assert.match(button.attributes.get('aria-label'), new RegExp(`task ${i}`));
      button.click();
      assert.deepEqual(calls.at(-1), [`task ${i}`, errors[i], button]);
    }
  });
});

test('error sorting uses text beyond the preview and keeps empty errors last', async () => {
  await withDocument(({elements}) => {
    const prefix = 'same prefix '.repeat(6);
    renderTasksBody(elements.tasksBody, toTaskRows([
      {name: 'bravo', error: prefix + 'bravo'},
      {name: 'empty', error: ''},
      {name: 'alpha', error: prefix + 'alpha'}
    ]));
    const names = () => elements.tasksBody.rows.map(row => row.cells[1].textContent);
    sortTaskTableBody(elements.tasksBody, 8, 'ascending');
    assert.deepEqual(names(), ['alpha', 'bravo', 'empty']);
    sortTaskTableBody(elements.tasksBody, 8, 'descending');
    assert.deepEqual(names(), ['bravo', 'alpha', 'empty']);
  });
});

test('full error dialog and download retain their snapshot across table refresh', async () => {
  await withDocument(async ({document, elements}) => {
    const taskName = 'Reviews / eMAG';
    const fullError = 'Exception: <failed> 😀\r\n\t' + 'Suppressed: HTTP 501\n'.repeat(6_000);
    let rows = [{name: taskName, error: fullError}];
    let refresh;
    globalThis.window.setInterval = callback => { refresh = callback; };
    globalThis.fetch = async () => ({ok: true, json: async () => rows});
    const dialog = initTaskErrorDialog(document);
    initTaskTable({
      tableId: 'tasksTable', theadId: 'tasksHead', tbodyId: 'tasksBody',
      dataUrl: '/app/tasks', refreshIntervalMs: 10_000, onViewError: dialog.open
    });
    await setImmediate();

    const trigger = elements.tasksBody.rows[0].cells[8].children[0];
    trigger.click();
    assert.equal(elements.taskErrorDialog.open, true);
    assert.ok(elements.taskErrorTitle.textContent.includes(taskName));
    assert.equal(elements.taskErrorContent.textContent, fullError);
    assert.equal(elements.taskErrorContent.children.length, 0);

    rows = [{name: taskName, error: 'Different failure '.repeat(10)}];
    await refresh();
    assert.equal(trigger.isConnected, false);
    assert.equal(elements.taskErrorDialog.open, true);
    assert.equal(elements.taskErrorContent.textContent, fullError);

    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    const blobs = [];
    const revoked = [];
    URL.createObjectURL = blob => { blobs.push(blob); return 'blob:task-error'; };
    URL.revokeObjectURL = url => revoked.push(url);
    try {
      elements.taskErrorDownload.click();
      assert.equal(blobs.length, 1);
      assert.match(blobs[0].type, /^text\/plain;charset=utf-8;?$/);
      assert.equal(await blobs[0].text(), fullError);
      assert.deepEqual(document.downloads, [{href: 'blob:task-error', name: 'task-error-Reviews-_-eMAG.txt'}]);
      elements.taskErrorClose.click();
      assert.equal(elements.taskErrorDialog.open, false);
      assert.equal(document.activeElement, elements.tasksBody.rows[0].cells[8].children[0]);
      assert.deepEqual(revoked, ['blob:task-error']);
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });
});

test('closing restores the original trigger or Reload when the task disappears', async () => {
  await withDocument(({document, elements}) => {
    const dialog = initTaskErrorDialog(document);
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    dialog.open('task', 'Original error', trigger);
    elements.taskErrorDialog.close(); // Native Escape also dispatches the close event.
    assert.equal(document.activeElement, trigger);

    dialog.open('task', 'Original error', trigger);
    trigger.remove();
    elements.taskErrorDialog.close();
    assert.equal(document.activeElement, elements.refreshBtn);
  });
});
