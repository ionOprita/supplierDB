/**
 * details-common.js
 * Shared logic for the per-cell details page.
 */
import { bindTableCsvDownload, sanitizeFileNamePart } from './table-common.js';

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

export function renderRows(tbody, items) {
  const statusEl = document.getElementById('status');
  tbody.innerHTML = '';
  if (!Array.isArray(items) || items.length === 0) {
    statusEl.textContent = 'No items for this cell.';
    return;
  }
  const frag = document.createDocumentFragment();
  for (const it of items) {
    const tr = document.createElement('tr');
    const dt = ymdHMSArrayToDate(it.time);
    const timeTxt = dt ? dtFmt.format(dt) : '';
    tr.appendChild(td(timeTxt));
    tr.appendChild(td(it.orderId, true));
    tr.appendChild(td(it.vendor));
    tr.appendChild(td(it.pnk, true));
    tr.appendChild(td(it.name));
    tr.appendChild(td(String(it.quantity), false, 'qty'));
    frag.appendChild(tr);
  }
  tbody.appendChild(frag);
  statusEl.textContent = `Loaded ${items.length} item(s).`;
}

export function initDetailsPage({
  titleText,
  endpointBuilder,
  tableId = 'detailsTable',
  csvButtonId = 'downloadCsvBtn',
  csvFilenamePrefix = 'details-table',
  includePnkInCsvFilename = false
}) {
  const params = new URLSearchParams(location.search);
  const pnk = params.get('pnk') ?? '';
  const month = params.get('month') ?? '';

  document.getElementById('title').textContent = titleText;
  document.getElementById('subtitle').textContent = `PNK: ${pnk} • Month: ${month}`;

  const tbody = document.getElementById('tbody');
  const statusEl = document.getElementById('status');
  const safePnk = sanitizeFileNamePart(pnk);

  bindTableCsvDownload({
    buttonId: csvButtonId,
    tableId,
    filePrefix: csvFilenamePrefix,
    fileNameBuilder: ({ datePart, filePrefix }) => {
      if (includePnkInCsvFilename && safePnk) {
        return `${filePrefix}-${safePnk}-${datePart}.csv`;
      }
      return `${filePrefix}-${datePart}.csv`;
    }
  });

  async function loadDetails() {
    try {
      statusEl.textContent = 'Loading…';
      const url = endpointBuilder(pnk, month);
      const data = await fetchJSON(url);
      renderRows(tbody, data);
    } catch (e) {
      tbody.innerHTML = '';
      statusEl.textContent = 'Failed to load details.';
      console.error(e);
    }
  }

  document.getElementById('refreshBtn')?.addEventListener('click', loadDetails);
  document.getElementById('copyBtn')?.addEventListener('click', async () => {
    const rows = [...document.querySelectorAll('#tbody tr')].map(tr =>
      [...tr.cells].map(td => td.innerText).join('\t')
    ).join('\n');
    try {
      await navigator.clipboard.writeText(rows);
      statusEl.textContent = 'Copied table to clipboard.';
    } catch {
      statusEl.textContent = 'Copy failed.';
    }
  });

  loadDetails();
}
