import { initDetailsPage } from '/js/details-common.js';
window.addEventListener('DOMContentLoaded', () => {
  initDetailsPage({
    titleText: "Return Details",
    endpointBuilder: (pnk, month) => `/app/returnDetails?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`
  });
});