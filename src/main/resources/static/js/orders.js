import { initMatrixTable } from '/js/table-common.js';

initMatrixTable({
  tableId: 'ordersTable',
  theadId: 'ordersHead',
  tbodyId: 'ordersBody',
  dataUrl: '/app/orderTable',
  detailsUrlBuilder: (pnk, month) => `/private/order-details?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'orderDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'orders-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
