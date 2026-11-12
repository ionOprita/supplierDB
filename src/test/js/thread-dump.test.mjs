// Run with: node --test src/test/js/thread-dump.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/thread-dump.js', import.meta.url), 'utf8');
const { initThreadDump, formatThreadDump, downloadThreadDump } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const SNAPSHOT = {
  capturedAt: '2026-11-12T10:11:12.000Z', header: 'JVM snapshot\nNo deadlocks detected.', deadlockedThreadIds: [],
  threads: [
    { id: 1, name: 'BackgroundJob-Worker-1', state: 'WAITING', text: '"BackgroundJob-Worker-1" WAITING\n\tat <some.class>.method(File.java:42)' },
    { id: 2, name: 'HTTP-dispatcher', state: 'RUNNABLE', text: '"HTTP-dispatcher" RUNNABLE\n\tat BackgroundJob.receive(File.java:1)' }
  ]
};

class Element {
  constructor() {
    this.value = '';
    this.textContent = '';
    this.disabled = false;
    this.attributes = new Map();
    this.listeners = new Map();
    this.classes = new Set();
    this.classList = { toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name) };
  }
  set innerHTML(_) { throw new Error('Dump text must never be rendered as HTML'); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  addEventListener(event, callback) { this.listeners.set(event, callback); }
  fire(event = 'click') { return this.listeners.get(event)?.(); }
}

function page(options = {}) {
  const elements = Object.fromEntries(['Capture', 'Filter', 'Copy', 'Download', 'Status', 'Summary', 'Content']
    .map(name => [name, new Element()]));
  const documentRoot = { getElementById: id => elements[id.replace('threadDump', '')] };
  return { ...elements, controller: initThreadDump({ documentRoot, ...options }) };
}

test('captures once, prevents overlapping requests, filters by name, and exports the same complete snapshot', async () => {
  let resolveRequest;
  let requests = 0;
  const copied = [];
  const downloaded = [];
  const screen = page({
    request: () => { requests++; return new Promise(resolve => { resolveRequest = resolve; }); },
    clipboard: { writeText: async text => copied.push(text) },
    save: (text, capturedAt) => downloaded.push({ text, capturedAt })
  });
  assert.equal(requests, 1);
  assert.equal(screen.Capture.disabled, true);
  assert.equal(screen.Copy.disabled, true);
  assert.equal(screen.Download.disabled, true);
  assert.equal(screen.Content.attributes.get('aria-busy'), 'true');
  await screen.controller.capture();
  assert.equal(requests, 1);

  resolveRequest(SNAPSHOT);
  await screen.controller.ready;
  assert.equal(screen.Capture.disabled, false);
  assert.equal(screen.Copy.disabled, false);
  assert.equal(screen.Content.attributes.get('aria-busy'), 'false');
  assert.equal(screen.Content.textContent, formatThreadDump(SNAPSHOT));
  assert.match(screen.Content.textContent, /<some\.class>/);
  screen.Filter.value = '  BACKGROUNDJOB-WORKER  ';
  screen.Filter.fire('input');
  assert.match(screen.Summary.textContent, /1 of 2 platform threads/);
  assert.match(screen.Content.textContent, /BackgroundJob-Worker-1/);
  assert.doesNotMatch(screen.Content.textContent, /HTTP-dispatcher/);

  await screen.Copy.fire();
  screen.Download.fire();
  assert.deepEqual(copied, [formatThreadDump(SNAPSHOT)]);
  assert.deepEqual(downloaded, [{ text: formatThreadDump(SNAPSHOT), capturedAt: SNAPSHOT.capturedAt }]);
  assert.equal(requests, 1);
  screen.Filter.value = 'absent';
  screen.Filter.fire('input');
  assert.match(screen.Content.textContent, /No threads match/);
});

test('shows initial failure, retries successfully, and retains the previous snapshot after failed refresh', async () => {
  const responses = [new Error('Offline'), SNAPSHOT, new Error('Offline again'), { ...SNAPSHOT, capturedAt: '2026-11-12T11:00:00Z' }];
  const screen = page({ request: async () => {
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return response;
  } });
  await screen.controller.ready;
  assert.match(screen.Status.textContent, /Capture failed.*Offline/);
  assert.equal(screen.Status.classes.has('error-message'), true);
  assert.equal(screen.Copy.disabled, true);
  assert.equal(screen.Download.disabled, true);

  await screen.Capture.fire();
  const previousContent = screen.Content.textContent;
  assert.equal(screen.Status.classes.has('error-message'), false);
  assert.equal(screen.Copy.disabled, false);
  await screen.Capture.fire();
  assert.equal(screen.Content.textContent, previousContent);
  assert.match(screen.Status.textContent, /Refresh failed.*previous snapshot.*2026-11-12T10:11:12/);
  assert.equal(screen.Download.disabled, false);

  await screen.Capture.fire();
  assert.match(screen.Summary.textContent, /2026-11-12T11:00:00Z/);
  assert.equal(screen.Status.classes.has('error-message'), false);
});

test('reports unavailable and rejected clipboard access while preserving the snapshot', async () => {
  const screen = page({ request: async () => SNAPSHOT, clipboard: null });
  await screen.controller.ready;
  await screen.Copy.fire();
  assert.match(screen.Status.textContent, /Clipboard access is unavailable/);
  assert.equal(screen.Content.textContent, formatThreadDump(SNAPSHOT));

  const rejected = page({ request: async () => SNAPSHOT, clipboard: { writeText: async () => { throw new Error('Denied'); } } });
  await rejected.controller.ready;
  await rejected.Copy.fire();
  assert.match(rejected.Status.textContent, /Could not copy/);
  assert.equal(rejected.Download.disabled, false);
});

test('rejects malformed snapshots instead of replacing a valid capture', async () => {
  let value = SNAPSHOT;
  const screen = page({ request: async () => value });
  await screen.controller.ready;
  value = { capturedAt: 'today', header: '', threads: [{}] };
  await screen.controller.capture();
  assert.match(screen.Status.textContent, /invalid thread dump/);
  assert.equal(screen.Content.textContent, formatThreadDump(SNAPSHOT));
});

test('downloads UTF-8 plain text with the capture timestamp and revokes the object URL', async () => {
  let blob;
  const revoked = [];
  const link = { click() { this.clicked = true; }, remove() { this.removed = true; } };
  const documentRoot = { createElement: () => link, body: { appendChild() {} } };
  const urlApi = { createObjectURL: value => { blob = value; return 'blob:capture'; }, revokeObjectURL: url => revoked.push(url) };
  const text = formatThreadDump(SNAPSHOT);
  downloadThreadDump(text, SNAPSHOT.capturedAt, documentRoot, urlApi);
  assert.equal(await blob.text(), text);
  assert.equal(blob.type, 'text/plain;charset=utf-8');
  assert.equal(link.download, 'thread-dump-2026-11-12T10-11-12-000Z.txt');
  assert.equal(link.href, 'blob:capture');
  assert.equal(link.clicked, true);
  assert.equal(link.removed, true);
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.deepEqual(revoked, ['blob:capture']);
});
