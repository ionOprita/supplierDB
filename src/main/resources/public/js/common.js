/**
 * common.js
 * Shared logic for all scripts.
 */
export function ymdHMSArrayToDate(arr) {
  if (!Array.isArray(arr) || arr.length < 3) return null;
  const [Y, M, D, h = 0, m = 0, s = 0] = arr;
  const d = new Date(Y, (M ?? 1) - 1, D ?? 1, h, m, s);
  return isNaN(d.getTime()) ? null : d;
}

export const dtFmt = new Intl.DateTimeFormat(undefined, {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit'
});

export async function fetchJSON(url) {
  const res = await fetch(url, { headers: { 'Accept': 'application/json' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export function td(text, mono = false, extraClass = '') {
  const c = document.createElement('td');
  if (mono) {
    const codeEl = document.createElement('code');
    codeEl.textContent = text ?? '';
    c.appendChild(codeEl);
  } else {
    c.textContent = text ?? '';
  }
  if (extraClass) c.classList.add(extraClass);
  return c;
}
