import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/theme.js', import.meta.url), 'utf8');
const storageKey = 'sellfusion.theme.v1';
function fixture({saved = null, dark = false, blocked = false} = {}) {
  const windowEvents = new Map(), documentEvents = new Map();
  const controls = Array.from({length: 2}, () => ({value: '', listeners: {},
    addEventListener(name, callback) { this.listeners[name] = callback; }}));
  const attributes = {}, style = {}, changes = [];
  const media = {matches: dark, addEventListener(_, callback) { this.change = callback; }};
  const store = {value: saved, blocked};
  const document = {readyState: 'loading', documentElement: {
    style, setAttribute(key, value) { attributes[key] = value; }},
    querySelectorAll() { return controls; },
    addEventListener(name, callback) { documentEvents.set(name, callback); }};
  const window = {matchMedia: () => media,
    localStorage: {
      getItem(key) { assert.equal(key, storageKey); if (store.blocked) throw Error('blocked'); return store.value; },
      setItem(key, value) { assert.equal(key, storageKey); if (store.blocked || store.failWrite) throw Error('blocked'); store.value = value; }
    },
    addEventListener(name, callback) { windowEvents.set(name, callback); },
    dispatchEvent(event) { changes.push({...event.detail}); }
  };
  vm.runInNewContext(source, {window, document, CustomEvent: class {
    constructor(type, options) { this.type = type; this.detail = options.detail; }
  }});
  return {window, document, store, media, attributes, style, controls, changes,
    ready: () => documentEvents.get('DOMContentLoaded')(),
    event: (name, value) => windowEvents.get(name)(value)};
}

test('applies the saved explicit theme before DOM readiness, overriding the operating system', () => {
  const f = fixture({saved: 'light', dark: true});
  assert.equal(f.attributes['data-theme'], 'light');
  assert.equal(f.style.colorScheme, 'light');
  f.ready();
  assert.deepEqual(f.controls.map(c => c.value), ['light', 'light']);
  f.media.change();
  assert.equal(f.attributes['data-theme'], 'light');
});

test('System responds to live operating system changes and manual themes do not', () => {
  const f = fixture(); f.ready();
  assert.equal(f.window.AppTheme.getPreference(), 'system');
  f.media.matches = true; f.media.change();
  assert.equal(f.window.AppTheme.getResolvedTheme(), 'dark');
  f.window.AppTheme.setPreference('dark');
  f.media.matches = false; f.media.change();
  assert.equal(f.attributes['data-theme'], 'dark');
  f.window.AppTheme.setPreference('system');
  assert.equal(f.attributes['data-theme'], 'light');
});

test('changing a selector saves its preference and synchronizes all controls', () => {
  const f = fixture(); f.ready();
  f.controls[0].value = 'dark'; f.controls[0].listeners.change();
  assert.equal(f.store.value, 'dark');
  assert.deepEqual(f.controls.map(c => c.value), ['dark', 'dark']);
  assert.deepEqual(f.changes.at(-1), {preference: 'dark', theme: 'dark'});
});

test('blocked storage still permits changing themes and preserves the choice on pageshow', () => {
  const f = fixture({blocked: true}); f.ready();
  f.window.AppTheme.setPreference('dark');
  f.event('pageshow');
  assert.equal(f.attributes['data-theme'], 'dark');
  assert.equal(f.window.AppTheme.getPreference(), 'dark');
});

test('invalid preferences safely fall back to System', () => {
  const f = fixture({saved: 'invalid', dark: true});
  assert.equal(f.attributes['data-theme'], 'dark');
  assert.equal(f.window.AppTheme.getPreference(), 'system');
  f.window.AppTheme.setPreference('invalid');
  assert.equal(f.store.value, 'system');
});

test('a failed write does not undo the current preference when restoring a cached page', () => {
  const f = fixture({saved: 'light'}); f.ready();
  f.store.failWrite = true;
  f.window.AppTheme.setPreference('dark');
  f.event('pageshow');
  assert.equal(f.store.value, 'light');
  assert.equal(f.window.AppTheme.getResolvedTheme(), 'dark');
});

test('storage events synchronize open tabs and clearing storage restores System', () => {
  const f = fixture(); f.ready();
  f.event('storage', {key: storageKey, newValue: 'dark'});
  assert.equal(f.attributes['data-theme'], 'dark');
  f.event('storage', {key: 'unrelated', newValue: 'light'});
  assert.equal(f.attributes['data-theme'], 'dark');
  f.event('storage', {key: null, newValue: null});
  assert.equal(f.attributes['data-theme'], 'light');
  assert.deepEqual(f.controls.map(c => c.value), ['system', 'system']);
});

test('restoring a cached page refreshes its theme without duplicate notifications', () => {
  const f = fixture(); f.ready();
  f.store.value = 'dark'; f.event('pageshow');
  const count = f.changes.length;
  f.event('pageshow');
  assert.equal(f.changes.length, count);
  assert.equal(f.attributes['data-theme'], 'dark');
});
