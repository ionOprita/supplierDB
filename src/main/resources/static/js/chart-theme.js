/** Keep Chart.js canvas text and surfaces in sync with the resolved page theme. */
export function themeChartOptions(options, element = globalThis.document?.body,
  getComputedStyle = globalThis.getComputedStyle) {
  const styles = element && getComputedStyle?.(element);
  const color = (name, fallback) => styles?.getPropertyValue(name).trim() || fallback;
  const text = color('--text', '#111827');
  const muted = color('--muted', '#6b7280');
  const border = color('--border', '#d1d5db');
  const surface = color('--surface', '#ffffff');

  options.color = text;
  for (const axis of Object.values(options.scales ?? {})) {
    if (!axis || typeof axis !== 'object') continue;
    for (const [key, value] of [['ticks', muted], ['title', muted], ['grid', border], ['border', border]]) {
      if (axis[key] === false) continue;
      axis[key] ??= {};
      axis[key].color = value;
    }
    if (axis.grid) axis.grid.tickColor = border;
  }
  if (options.plugins === false) return options;
  options.plugins ??= {};
  const plugins = options.plugins;
  if (plugins.legend !== false) {
    plugins.legend ??= {};
    plugins.legend.labels ??= {};
    plugins.legend.labels.color = muted;
    if (plugins.legend.title) plugins.legend.title.color = text;
  }
  for (const key of ['title', 'subtitle']) {
    if (plugins[key]) plugins[key].color = text;
  }
  if (plugins.tooltip !== false) {
    plugins.tooltip ??= {};
    Object.assign(plugins.tooltip, {
      backgroundColor: surface, titleColor: text, bodyColor: text, footerColor: muted,
      borderColor: border, borderWidth: 1, multiKeyBackground: surface
    });
  }
  return options;
}

export function createThemedChart(Chart, canvas, config, {element, getComputedStyle} = {}) {
  config.options = themeChartOptions(config.options ?? {}, element, getComputedStyle);
  return new Chart(canvas, config);
}

/** Read active instances at event time; never rebuild them or reload their data. */
export function observeChartTheme(getCharts, {window = globalThis.window, element, getComputedStyle} = {}) {
  const update = () => {
    for (const chart of getCharts()) {
      if (!chart) continue;
      const options = chart.options ?? chart.config?.options;
      if (!options) continue;
      themeChartOptions(options, element, getComputedStyle);
      chart.update('none');
    }
  };
  window?.addEventListener('app:themechange', update);
  return () => window?.removeEventListener('app:themechange', update);
}
