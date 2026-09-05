const DAY_MODE = 'day';
const LAST_7_DAYS_MODE = 'last7';
const LAST_30_DAYS_MODE = 'last30';
const CUSTOM_MODE = 'custom';

const RANGE_MODES = new Map([
  [LAST_7_DAYS_MODE, 7],
  [LAST_30_DAYS_MODE, 30]
]);

function isoDateTime(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value ?? ''))) {
    return null;
  }

  const [year, month, day] = value.split('-').map(Number);
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  if (date.getUTCFullYear() !== year
    || date.getUTCMonth() !== month - 1
    || date.getUTCDate() !== day) {
    return null;
  }
  return time;
}

export function isIsoDate(value) {
  return isoDateTime(value) !== null;
}

function shiftDate(value, dayOffset) {
  const time = isoDateTime(value);
  if (time === null) {
    return '';
  }
  const shifted = new Date(time + dayOffset * 24 * 60 * 60 * 1000);
  return shifted.toISOString().slice(0, 10);
}

function dayPeriod(date) {
  return {
    kind: 'day',
    mode: DAY_MODE,
    date,
    dateFrom: date,
    dateTo: date
  };
}

function rangePeriod(dateFrom, dateTo, mode = CUSTOM_MODE) {
  return {
    kind: 'range',
    mode,
    dateFrom,
    dateTo
  };
}

function presetPeriod(mode, anchorDate) {
  const dayCount = RANGE_MODES.get(mode);
  if (!dayCount || !isIsoDate(anchorDate)) {
    return null;
  }
  return rangePeriod(shiftDate(anchorDate, -(dayCount - 1)), anchorDate, mode);
}

function toSearchParams(value) {
  return value instanceof URLSearchParams ? value : new URLSearchParams(value ?? '');
}

export function parseAdsPeriod(value) {
  const params = toSearchParams(value);
  const dateFrom = params.get('dateFrom') || '';
  const dateTo = params.get('dateTo') || '';
  if (isIsoDate(dateFrom) && isIsoDate(dateTo) && dateFrom <= dateTo) {
    return rangePeriod(dateFrom, dateTo);
  }

  const date = params.get('date') || '';
  return isIsoDate(date) ? dayPeriod(date) : null;
}

function cleanReportDates(reportDates) {
  return [...new Set((Array.isArray(reportDates) ? reportDates : []).filter(isIsoDate))];
}

function matchingPresetMode(period, reportDates) {
  if (period?.kind !== 'range' || !reportDates.includes(period.dateTo)) {
    return CUSTOM_MODE;
  }
  for (const [mode, dayCount] of RANGE_MODES) {
    if (period.dateFrom === shiftDate(period.dateTo, -(dayCount - 1))) {
      return mode;
    }
  }
  return CUSTOM_MODE;
}

export function resolveAdsPeriod(value, reportDates) {
  const dates = cleanReportDates(reportDates);
  const requested = parseAdsPeriod(value);
  if (requested?.kind === 'range') {
    return rangePeriod(
      requested.dateFrom,
      requested.dateTo,
      matchingPresetMode(requested, dates)
    );
  }
  if (requested?.kind === 'day' && dates.includes(requested.date)) {
    return requested;
  }
  return dates.length ? dayPeriod(dates[0]) : null;
}

export function isValidAdsPeriod(period) {
  if (period?.kind === 'day') {
    return isIsoDate(period.date);
  }
  return period?.kind === 'range'
    && isIsoDate(period.dateFrom)
    && isIsoDate(period.dateTo)
    && period.dateFrom <= period.dateTo;
}

export function addAdsPeriodParams(params, period) {
  params.delete('date');
  params.delete('dateFrom');
  params.delete('dateTo');
  if (!isValidAdsPeriod(period)) {
    return params;
  }
  if (period.kind === 'day') {
    params.set('date', period.date);
  } else {
    params.set('dateFrom', period.dateFrom);
    params.set('dateTo', period.dateTo);
  }
  return params;
}

export function adsPeriodSearchParams(period, baseParams = {}) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(baseParams)) {
    if (value !== null && value !== undefined && value !== '') {
      params.set(key, String(value));
    }
  }
  return addAdsPeriodParams(params, period);
}

export function replaceAdsPeriodInUrl(period, fixedParams = {}) {
  const url = new URL(window.location.href);
  for (const [key, value] of Object.entries(fixedParams)) {
    if (value === null || value === undefined || value === '') {
      url.searchParams.delete(key);
    } else {
      url.searchParams.set(key, String(value));
    }
  }
  addAdsPeriodParams(url.searchParams, period);
  window.history.replaceState(null, '', url);
}

export function adsPeriodLabel(period) {
  if (!isValidAdsPeriod(period)) {
    return '';
  }
  return period.kind === 'day'
    ? period.date
    : `${period.dateFrom} to ${period.dateTo}`;
}

export function adsPeriodPhrase(period) {
  if (!isValidAdsPeriod(period)) {
    return '';
  }
  return period.kind === 'day'
    ? `on ${period.date}`
    : `from ${period.dateFrom} to ${period.dateTo}`;
}

export function adsPeriodFilePart(period, fallback = '') {
  if (!isValidAdsPeriod(period)) {
    return fallback;
  }
  return period.kind === 'day'
    ? period.date
    : `${period.dateFrom}-to-${period.dateTo}`;
}

function requiredElement(id) {
  const element = document.getElementById(id);
  if (!element) {
    throw new Error(`Missing ads period control #${id}`);
  }
  return element;
}

export function bindAdsPeriodControls({
  periodSelectId,
  dateSelectId,
  dateControlsId,
  dateLabelId,
  customControlsId,
  dateFromInputId,
  dateToInputId,
  applyButtonId,
  reportDates,
  initialPeriod,
  onApply
}) {
  const periodSelect = requiredElement(periodSelectId);
  const dateSelect = requiredElement(dateSelectId);
  const dateControls = requiredElement(dateControlsId);
  const dateLabel = requiredElement(dateLabelId);
  const customControls = requiredElement(customControlsId);
  const dateFromInput = requiredElement(dateFromInputId);
  const dateToInput = requiredElement(dateToInputId);
  const applyButton = requiredElement(applyButtonId);
  const dates = cleanReportDates(reportDates);
  let activePeriod = isValidAdsPeriod(initialPeriod) ? initialPeriod : null;

  dateSelect.innerHTML = '';
  for (const reportDate of dates) {
    const option = document.createElement('option');
    option.value = reportDate;
    option.textContent = reportDate;
    dateSelect.appendChild(option);
  }
  dateSelect.disabled = dates.length === 0;

  function preferredAnchor() {
    const activeEnd = activePeriod?.kind === 'day' ? activePeriod.date : activePeriod?.dateTo;
    return dates.includes(activeEnd) ? activeEnd : (dates[0] || '');
  }

  function showMode(mode) {
    const custom = mode === CUSTOM_MODE;
    periodSelect.value = mode;
    dateControls.hidden = custom;
    customControls.hidden = !custom;
    dateLabel.textContent = mode === DAY_MODE ? 'Report date:' : 'Ending date:';
  }

  function showPeriod(period) {
    const mode = period?.mode || DAY_MODE;
    showMode(mode);
    if (mode === CUSTOM_MODE) {
      dateFromInput.value = period?.dateFrom || '';
      dateToInput.value = period?.dateTo || '';
      return;
    }
    const anchor = period?.kind === 'day' ? period.date : period?.dateTo;
    dateSelect.value = dates.includes(anchor) ? anchor : preferredAnchor();
  }

  function emit(period) {
    if (!isValidAdsPeriod(period)) {
      return;
    }
    activePeriod = period;
    showPeriod(period);
    onApply(period);
  }

  function periodForMode(mode) {
    const anchorDate = dateSelect.value || preferredAnchor();
    if (!anchorDate) {
      return null;
    }
    return mode === DAY_MODE ? dayPeriod(anchorDate) : presetPeriod(mode, anchorDate);
  }

  function clearCustomValidity() {
    dateFromInput.setCustomValidity('');
    dateToInput.setCustomValidity('');
  }

  periodSelect.addEventListener('change', () => {
    const mode = periodSelect.value;
    if (mode === CUSTOM_MODE) {
      const fallbackDate = activePeriod?.dateTo || preferredAnchor();
      showMode(CUSTOM_MODE);
      dateFromInput.value = activePeriod?.dateFrom || fallbackDate || '';
      dateToInput.value = activePeriod?.dateTo || fallbackDate || '';
      clearCustomValidity();
      return;
    }

    const anchor = preferredAnchor();
    if (anchor) {
      dateSelect.value = anchor;
    }
    const period = periodForMode(mode);
    if (period) {
      emit(period);
    } else {
      showMode(mode);
    }
  });

  dateSelect.addEventListener('change', () => {
    const period = periodForMode(periodSelect.value);
    if (period) {
      emit(period);
    }
  });

  dateFromInput.addEventListener('input', clearCustomValidity);
  dateToInput.addEventListener('input', clearCustomValidity);
  applyButton.addEventListener('click', () => {
    clearCustomValidity();
    const dateFrom = dateFromInput.value;
    const dateTo = dateToInput.value;
    if (!isIsoDate(dateFrom)) {
      dateFromInput.setCustomValidity('Choose a valid From date.');
      dateFromInput.reportValidity();
      return;
    }
    if (!isIsoDate(dateTo)) {
      dateToInput.setCustomValidity('Choose a valid To date.');
      dateToInput.reportValidity();
      return;
    }
    if (dateFrom > dateTo) {
      dateToInput.setCustomValidity('To date must be on or after From date.');
      dateToInput.reportValidity();
      return;
    }
    emit(rangePeriod(dateFrom, dateTo));
  });

  showPeriod(activePeriod);
  return {
    getPeriod: () => activePeriod
  };
}
