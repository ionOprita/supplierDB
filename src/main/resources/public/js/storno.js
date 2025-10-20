import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'stornoTable',
  theadId: 'stornoHead',
  tbodyId: 'stornoBody',
  dataUrl: '/app/stornoTable',
  detailsUrlBuilder: (pnk, month) => `/app/storno-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'stornoDetails'
});
