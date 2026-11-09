import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/info-tooltip.js', import.meta.url), 'utf8');
const {createInfoTooltip} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

class Target {
  constructor(tag = '') {
    Object.assign(this, {tag, children: [], listeners: new Map(), attributes: new Map(), style: {}, text: '',
      bounds: {left: 20, top: 20, right: 52, bottom: 52, width: 32, height: 32}});
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  contains(node) { return this === node || this.children.some(child => child.contains(node)); }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  getAttribute(key) { return this.attributes.get(key); }
  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? new Set(); listeners.add(listener); this.listeners.set(type, listeners);
  }
  removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
  dispatch(type, event = {}) {
    event.target ??= this;
    for (const listener of this.listeners.get(type) ?? []) listener(event);
    return event;
  }
  getBoundingClientRect() {
    if (this.className !== 'info-tooltip-text') return this.bounds;
    return {...this.bounds, width: Math.min(this.bounds.width, parseFloat(this.style.maxWidth) || Infinity),
      height: Math.min(this.bounds.height, parseFloat(this.style.maxHeight) || Infinity)};
  }
}

function fixture(overrides = {}) {
  const document = new Target('document');
  document.createElement = tag => new Target(tag);
  const window = Object.assign(new Target('window'), {innerWidth: 800, innerHeight: 600});
  const info = createInfoTooltip({document, window, prefix: 'report', label: 'About this report',
    text: 'Source and reporting period.', ...overrides});
  info.tooltip.bounds = {left: 0, top: 0, right: 360, bottom: 180, width: 360, height: 180};
  return {document, window, info};
}

test('hover and keyboard focus reveal accessible help, including pointer movement onto its text', () => {
  const {info, document} = fixture();
  assert.equal(info.button.type, 'button');
  assert.equal(info.button.getAttribute('aria-label'), 'About this report');
  assert.equal(info.button.getAttribute('aria-describedby'), info.tooltip.id);
  assert.equal(info.button.getAttribute('aria-controls'), info.tooltip.id);
  assert.equal(info.tooltip.getAttribute('role'), 'tooltip');
  assert.equal(info.button.getAttribute('aria-expanded'), 'false');
  assert.equal(info.tooltip.hidden, true);
  info.wrap.dispatch('pointerenter', {pointerType: 'mouse'});
  assert.equal(info.tooltip.hidden, false);
  assert.equal(info.button.getAttribute('aria-expanded'), 'true');
  info.wrap.dispatch('pointerleave', {relatedTarget: info.tooltip});
  document.dispatch('pointerdown', {target: info.tooltip});
  assert.equal(info.tooltip.hidden, false);
  info.wrap.dispatch('pointerleave');
  assert.equal(info.tooltip.hidden, true);
  info.button.dispatch('focus');
  assert.equal(info.tooltip.hidden, false);
  info.button.dispatch('blur', {relatedTarget: document});
  assert.equal(info.tooltip.hidden, true);
  info.destroy();
});

test('touch toggles help and Escape or outside clicks dismiss without trapping focus or consuming Escape', () => {
  const {info, document} = fixture();
  info.wrap.dispatch('pointerenter', {pointerType: 'touch'});
  assert.equal(info.tooltip.hidden, true);
  info.button.dispatch('focus');
  info.button.dispatch('click');
  assert.equal(info.tooltip.hidden, false, 'Focus from the first tap does not toggle the help closed');
  info.button.dispatch('click');
  assert.equal(info.tooltip.hidden, true);
  info.button.dispatch('click');
  assert.equal(info.tooltip.hidden, false);
  const escape = document.dispatch('keydown', {key: 'Escape', defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; }});
  assert.equal(escape.defaultPrevented, false);
  assert.equal(info.tooltip.hidden, true);
  info.button.dispatch('focus');
  assert.equal(info.tooltip.hidden, true, 'A still-focused trigger cannot undo dismissal');
  info.button.dispatch('blur');
  info.button.dispatch('focus');
  assert.equal(info.tooltip.hidden, false);
  document.dispatch('pointerdown');
  assert.equal(info.tooltip.hidden, true);
  info.destroy();
});

test('help is clamped to mobile viewport edges, flips above lower anchors and follows scroll or resize', () => {
  const {info, window} = fixture();
  window.innerWidth = 320; window.innerHeight = 400;
  info.button.bounds = {left: 280, top: 350, bottom: 394, width: 44, height: 44};
  info.button.dispatch('focus');
  assert.equal(info.tooltip.style.maxWidth, '296px');
  assert.equal(info.tooltip.style.left, '12px');
  assert.equal(info.tooltip.style.top, '170px');
  info.button.bounds = {left: 8, top: 8, bottom: 52, width: 44, height: 44};
  window.dispatch('scroll');
  assert.equal(info.tooltip.style.left, '12px');
  assert.equal(info.tooltip.style.top, '52px');
  window.innerHeight = 100;
  window.dispatch('resize');
  assert.equal(info.tooltip.style.maxHeight, '76px');
  assert.equal(info.tooltip.style.top, '12px');
  info.destroy();
});

test('zoomed visual viewport offsets are honored and all listeners are removed on disposal', () => {
  const document = new Target(); document.createElement = tag => new Target(tag);
  const viewport = Object.assign(new Target(), {width: 320, height: 400, offsetLeft: 100, offsetTop: 200});
  const window = Object.assign(new Target(), {innerWidth: 800, innerHeight: 600, visualViewport: viewport});
  const first = createInfoTooltip({document, window, prefix: 'first', label: 'First', text: 'First help'});
  const second = createInfoTooltip({document, window, prefix: 'second', label: 'Second', text: 'Second help'});
  first.button.bounds = {left: 390, top: 550, bottom: 594};
  first.tooltip.bounds = {width: 360, height: 180};
  first.button.dispatch('focus');
  assert.equal(first.tooltip.style.left, '112px');
  assert.equal(first.tooltip.style.top, '370px');
  viewport.offsetTop = 500;
  viewport.dispatch('scroll');
  assert.equal(first.tooltip.style.top, '594px');
  assert.equal(document.listeners.get('keydown').size, 1);
  assert.equal(document.listeners.get('pointerdown').size, 1);
  first.destroy();
  first.destroy();
  assert.equal(first.tooltip.hidden, true);
  assert.equal(document.listeners.get('keydown').size, 1);
  second.destroy();
  for (const target of [document, window, viewport, first.wrap, first.button, second.wrap, second.button]) {
    assert.equal([...target.listeners.values()].reduce((n, entries) => n + entries.size, 0), 0);
  }
});

test('section titles and source-like text are inert text, never HTML', () => {
  const unsafe = '<img src=x onerror=alert(1)>';
  const {info} = fixture({text: undefined, sections: [{title: unsafe, text: unsafe}]});
  assert.equal(info.tooltip.textContent, unsafe + unsafe);
  assert.equal(info.tooltip.children[0].children[0].tag, 'strong');
  assert.equal(info.tooltip.children[0].children[0].children.length, 0);
  assert.equal(info.tooltip.children[0].children[1].children.length, 0);
  info.destroy();
});

test('shared styles use the active theme, preserve hidden state, and provide keyboard focus and touch targets', async () => {
  const css = await readFile(new URL('../../main/resources/static/info-tooltip.css', import.meta.url), 'utf8');
  for (const variable of ['--surface-alt', '--text', '--muted', '--border', '--hover', '--primary']) {
    assert.ok(css.includes(`var(${variable})`), variable);
  }
  assert.match(css, /\.info-tooltip-text\[hidden\]\s*\{\s*display: none;/);
  assert.match(css, /\.info-tooltip \.info-tooltip-button\s*\{[^}]*width: 32px;[^}]*min-height: 32px;[^}]*border: 0;/,
    'The two-class selector overrides app-shell generic button padding, border and minimum height');
  assert.match(css, /\.info-tooltip \.info-tooltip-button:focus-visible\s*\{[^}]*outline:/);
  assert.match(css, /\(pointer: coarse\)[\s\S]*width: 44px;[\s\S]*height: 44px;/);
  assert.match(css, /\.info-tooltip-text\s*\{[^}]*position: fixed;[^}]*max-width:[^}]*overflow-y: auto;/);
});
