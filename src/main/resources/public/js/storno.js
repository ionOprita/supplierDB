import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'stornoTable',
  theadId: 'stornoHead',
  tbodyId: 'stornoBody',
  dataUrl: '/stornoTable',
  detailsUrlBuilder: (pnk, month) => `/storno-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'stornoDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'storno-table'
});
