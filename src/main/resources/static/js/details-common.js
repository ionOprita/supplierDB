/**
 * details-common.js
 * Shared logic for the per-cell details page.
 */
import {fetchJSON, formatLocalDateTime, td, ymdHMSArrayToDate} from "./common.js";
import { bindTableCsvDownload, sanitizeFileNamePart } from './table-common.js';

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
    const timeTxt = formatLocalDateTime(dt);
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
