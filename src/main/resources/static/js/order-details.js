import {initDetailsPage} from '/js/details-common.js';
initDetailsPage({
  titleText: "Order Details",
  endpointBuilder: (pnk, month) => `/app/orderDetails?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`
});
