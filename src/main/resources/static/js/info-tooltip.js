// Shared, text-only help for mouse, keyboard and touch. One dismissal listener per document.
const documents = new WeakMap();

function register(document, control) {
  let group = documents.get(document);
  if (!group) {
    const controls = new Set();
    const escape = event => {
      if (event.key === 'Escape') controls.forEach(item => { if (!item.tooltip.hidden) item.dismiss(); });
    };
    const outside = event => controls.forEach(item => {
      if (!item.wrap.contains(event.target)) item.dismiss();
    });
    group = {controls, escape, outside};
    documents.set(document, group);
    document.addEventListener('keydown', escape);
    document.addEventListener('pointerdown', outside);
  }
  group.controls.add(control);
  return () => {
    group.controls.delete(control);
    if (!group.controls.size) {
      document.removeEventListener('keydown', group.escape);
      document.removeEventListener('pointerdown', group.outside);
      documents.delete(document);
    }
  };
}

export function createInfoTooltip({document = globalThis.document,
  window: view = document.defaultView ?? globalThis.window, prefix, label, text, sections = []}) {
  const element = (tag, className, value) => {
    const node = document.createElement(tag);
    node.className = className;
    if (value !== undefined) node.textContent = value;
    return node;
  };
  const wrap = element('span', 'info-tooltip');
  wrap.id = `${prefix}InfoWrap`;
  const button = element('button', 'info-tooltip-button');
  button.id = `${prefix}Info`;
  button.type = 'button';
  button.setAttribute('aria-label', label);
  button.setAttribute('aria-describedby', `${prefix}InfoText`);
  button.setAttribute('aria-controls', `${prefix}InfoText`);
  const icon = element('span', 'info-tooltip-icon', 'i');
  icon.setAttribute('aria-hidden', 'true');
  button.appendChild(icon);
  const tooltip = element('span', 'info-tooltip-text');
  tooltip.id = `${prefix}InfoText`;
  tooltip.setAttribute('role', 'tooltip');
  if (text !== undefined) tooltip.textContent = text;
  for (const section of sections) {
    const paragraph = element('span', 'info-tooltip-section');
    paragraph.appendChild(element('strong', 'info-tooltip-heading', section.title));
    paragraph.appendChild(element('span', '', section.text));
    tooltip.appendChild(paragraph);
  }
  wrap.appendChild(button);
  wrap.appendChild(tooltip);
  let hovered = false;
  let focused = false;
  let pinned = false;
  let dismissed = false;
  let destroyed = false;

  function position() {
    if (tooltip.hidden || !button.getBoundingClientRect || !tooltip.getBoundingClientRect) return;
    const viewport = view?.visualViewport;
    const width = viewport?.width ?? view?.innerWidth ?? document.documentElement?.clientWidth;
    const height = viewport?.height ?? view?.innerHeight ?? document.documentElement?.clientHeight;
    const anchor = button.getBoundingClientRect();
    if (![width, height, anchor.left, anchor.top, anchor.bottom].every(Number.isFinite)) return;
    const margin = 12;
    const x = viewport?.offsetLeft ?? 0;
    const y = viewport?.offsetTop ?? 0;
    tooltip.style.maxWidth = `${Math.max(0, width - margin * 2)}px`;
    tooltip.style.maxHeight = `${Math.max(0, height - margin * 2)}px`;
    const bounds = tooltip.getBoundingClientRect();
    const left = Math.max(x + margin, Math.min(anchor.left, x + width - margin - bounds.width));
    const below = anchor.bottom;
    const preferredTop = below + bounds.height <= y + height - margin ? below : anchor.top - bounds.height;
    const top = Math.max(y + margin, Math.min(preferredTop, y + height - margin - bounds.height));
    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${top}px`;
  }

  function update() {
    tooltip.hidden = destroyed || dismissed || !(hovered || focused || pinned);
    button.setAttribute('aria-expanded', String(!tooltip.hidden));
    position();
  }
  const reset = () => { if (!hovered && !focused) dismissed = false; };
  const enter = event => {
    if (event.pointerType === 'touch') return;
    reset(); hovered = true; update();
  };
  const leave = event => {
    if (wrap.contains(event.relatedTarget)) return;
    hovered = false; reset(); update();
  };
  const focus = () => { reset(); focused = true; update(); };
  const blur = event => {
    if (wrap.contains(event.relatedTarget)) return;
    focused = false; pinned = false; reset(); update();
  };
  const click = () => { pinned = !pinned; dismissed = !pinned; update(); };
  const listeners = [[wrap, 'pointerenter', enter], [wrap, 'pointerleave', leave],
    [button, 'focus', focus], [button, 'blur', blur], [button, 'click', click]];
  listeners.forEach(([target, type, listener]) => target.addEventListener(type, listener));
  view?.addEventListener?.('resize', position);
  view?.addEventListener?.('scroll', position, true);
  view?.visualViewport?.addEventListener('resize', position);
  view?.visualViewport?.addEventListener('scroll', position);
  const control = {wrap, button, tooltip,
    dismiss() { pinned = false; dismissed = true; update(); },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      update();
      listeners.forEach(([target, type, listener]) => target.removeEventListener(type, listener));
      view?.removeEventListener?.('resize', position);
      view?.removeEventListener?.('scroll', position, true);
      view?.visualViewport?.removeEventListener('resize', position);
      view?.visualViewport?.removeEventListener('scroll', position);
      unregister();
    }};
  const unregister = register(document, control);
  update();
  return control;
}
