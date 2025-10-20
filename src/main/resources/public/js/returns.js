import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'returnsTable',
  theadId: 'returnsHead',
  tbodyId: 'returnsBody',
  dataUrl: '/app/returnTable',
  detailsUrlBuilder: (pnk, month) => `/app/return-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'returnDetails'
});
