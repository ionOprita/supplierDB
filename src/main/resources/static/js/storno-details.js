import { initDetailsPage } from '/js/details-common.js';
window.addEventListener('DOMContentLoaded', () => {
  initDetailsPage({
    titleText: "Storno Details",
    endpointBuilder: (pnk, month) => `/app/stornoDetails?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`
  });
});