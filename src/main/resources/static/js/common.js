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

export function formatLocalDateTime(date) {
  if (!date) return ''; // handles null, undefined, 0, etc.
  const pad = n => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function formatDuration(seconds) {
  if (seconds == null || isNaN(seconds) || seconds == 0) return ''; // handle null, undefined, NaN
  seconds = Math.floor(seconds); // just in case it’s not an integer

  if (seconds < 60) {
    return `${seconds} sec`;
  } else {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return secs > 0 ? `${mins} min ${secs} sec` : `${mins} min`;
  }
}

