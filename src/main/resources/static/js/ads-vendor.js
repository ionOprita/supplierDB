import {fetchJSON} from './common.js';
import {adsPeriodSearchParams} from './ads-period.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class AdsVendorSelectionError extends Error {}

function isVendorId(value) {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

function vendorLabel(vendor) {
  const name = vendor.name || vendor.account || vendor.vendorId;
  return vendor.account && vendor.account !== name ? `${name} (${vendor.account})` : name;
}

export async function loadAdsVendor({allowDefault = false, selectId, getPeriod} = {}) {
  const requested = new URLSearchParams(window.location.search).get('vendorId');
  if (!allowDefault && !isVendorId(requested)) {
    throw new AdsVendorSelectionError('Missing or invalid vendor. Open Ads Campaigns and select a vendor.');
  }

  const data = await fetchJSON('/app/adsVendors');
  const vendors = Array.isArray(data.vendors) ? data.vendors : [];
  const selectedId = requested === null && allowDefault ? data.defaultVendorId : requested;
  const vendor = isVendorId(selectedId)
    ? vendors.find(item => item.vendorId?.toLowerCase() === selectedId.toLowerCase())
    : null;
  const select = selectId ? document.getElementById(selectId) : null;
  if (select) {
    select.innerHTML = '';
    for (const item of vendors) {
      const option = document.createElement('option');
      option.value = item.vendorId;
      option.textContent = vendorLabel(item);
      select.appendChild(option);
    }
    select.value = vendor?.vendorId || '';
    select.disabled = vendors.length === 0;
    window.addEventListener('pageshow', () => {
      select.value = vendor?.vendorId || '';
    });
    select.addEventListener('change', () => {
      if (!vendors.some(item => item.vendorId === select.value)) return;
      const params = adsPeriodSearchParams(getPeriod?.(), {vendorId: select.value});
      window.location.assign(`/private/ads-campaigns?${params.toString()}`);
    });
  }

  if (!vendors.length && allowDefault && requested === null) return null;
  if (!vendor) {
    throw new AdsVendorSelectionError('Unknown or invalid vendor. Open Ads Campaigns and select a vendor.');
  }
  const label = document.getElementById('adsVendorLabel');
  if (label) label.textContent = `Vendor: ${vendorLabel(vendor)}`;
  return vendor;
}

export function adsVendorErrorMessage(error, fallback) {
  return error instanceof AdsVendorSelectionError ? error.message : fallback;
}
