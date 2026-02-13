import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'stornoTable',
  theadId: 'stornoHead',
  tbodyId: 'stornoBody',
  dataUrl: '/stornoTable',
  detailsUrlBuilder: (pnk, month) => `/storno-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'stornoDetails'
});

const downloadCsvBtn = document.getElementById('downloadCsvBtn');

downloadCsvBtn?.addEventListener('click', () => {
  const table = document.getElementById('stornoTable');
  if (!table) return;

  const csv = tableToCsv(table);
  if (!csv) return;

  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  const datePart = new Date().toISOString().slice(0, 10);

  anchor.href = url;
  anchor.download = `storno-table-${datePart}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
});

function tableToCsv(table) {
  const rows = Array.from(table.querySelectorAll('tr'));
  if (!rows.length) return '';

  return rows
    .map((row) => {
      const columns = Array.from(row.cells).map((cell) => csvEscape(cell.textContent ?? ''));
      return columns.join(',');
    })
    .join('\r\n');
}

function csvEscape(value) {
  const normalized = value.replace(/\r?\n|\r/g, ' ').trim();
  if (/[",\n]/.test(normalized)) {
    return `"${normalized.replace(/"/g, '""')}"`;
  }
  return normalized;
}
