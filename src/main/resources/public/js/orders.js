import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'ordersTable',
  theadId: 'ordersHead',
  tbodyId: 'ordersBody',
  dataUrl: '/app/orderTable',
  detailsUrlBuilder: (pnk, month) => `/order-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'orderDetails'
});
