/* Runs before stylesheets so a saved appearance is applied before the first paint. */
(function () {
  'use strict';
  const storageKey = 'sellfusion.theme.v1';
  const normalize = value => ['system', 'light', 'dark'].includes(value) ? value : 'system';
  const media = typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  const controls = new Set();
  function storedPreference() {
    try { return normalize(window.localStorage.getItem(storageKey)); }
    catch { return null; }
  }
  let preference = storedPreference() || 'system';
  let unsavedPreference = false;
  let theme;
  let previousPreference;

  function apply() {
    const resolved = preference === 'system' ? (media?.matches ? 'dark' : 'light') : preference;
    const changed = resolved !== theme || preference !== previousPreference;
    theme = resolved;
    previousPreference = preference;
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.style.colorScheme = theme;
    for (const control of controls) control.value = preference;
    if (changed) window.dispatchEvent(new CustomEvent('app:themechange', {
      detail: {preference, theme}
    }));
  }

  function setPreference(value) {
    preference = normalize(value);
    try {
      window.localStorage.setItem(storageKey, preference);
      unsavedPreference = false;
    } catch { unsavedPreference = true; }
    apply();
  }

  window.AppTheme = Object.freeze({
    getPreference: () => preference,
    getResolvedTheme: () => theme,
    setPreference
  });
  apply();

  function initializeControls() {
    for (const control of document.querySelectorAll('[data-theme-select]')) {
      if (controls.has(control)) continue;
      controls.add(control);
      control.value = preference;
      control.addEventListener('change', () => setPreference(control.value));
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeControls, {once: true});
  } else initializeControls();

  const systemChanged = () => { if (preference === 'system') apply(); };
  if (media?.addEventListener) media.addEventListener('change', systemChanged);
  else media?.addListener?.(systemChanged);
  window.addEventListener('storage', event => {
    if (event.key !== storageKey && event.key !== null) return;
    unsavedPreference = false;
    preference = normalize(event.newValue);
    apply();
  });
  window.addEventListener('pageshow', () => {
    if (!unsavedPreference) preference = storedPreference() ?? preference;
    initializeControls();
    apply();
  });
})();
