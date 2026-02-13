import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'ordersTable',
  theadId: 'ordersHead',
  tbodyId: 'ordersBody',
  dataUrl: '/orderTable',
  detailsUrlBuilder: (pnk, month) => `/order-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'orderDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'orders-table',
  vendorFilterSelectId: 'vendorFilterSelect'
});
