import {fetchJSON} from './common.js';

const sections = [
  {id: 'general', target: 'dateCalculGeneralFields', fields: [
    {key: 'geniusEasyboxRate', label: 'Comenzi Genius la easybox (%)', percent: true, bounded: true},
    {key: 'monthlyCapitalRate', label: 'Costul banilor pe lună (%)', percent: true, signed: true},
    {key: 'monthlyInflationRate', label: 'Inflație pe lună (%)', percent: true, signed: true},
    {key: 'averageDaysPerMonth', label: 'Zile într-o lună în medie', positive: true},
    {key: 'previousMonthUnits', label: 'Total vânzări luna anterioară', computed: true},
    {key: 'vatRate', label: 'Valoare TVA (%)', percent: true, bounded: true},
    {key: 'incomeTaxRate', label: 'Impozit pe venit (%)', percent: true, bounded: true},
    {key: 'profitTaxRate', label: 'Impozit pe profit (%)', percent: true, bounded: true},
    {key: 'dividendTaxRate', label: 'Impozit pe dividende (%)', percent: true, bounded: true},
    {key: 'unaccountedStornoRate', label: 'Stornări neluate în calcul (%)', percent: true}
  ]},
  {id: 'generalCosts', target: 'dateCalculGeneralCostsFields', fields: [
    {key: 'packingCostRon', label: 'Împachetare produs (RON)'},
    {key: 'courierCostRon', label: 'Cost livrare colet curier (RON)'},
    {key: 'geniusCostRon', label: 'Cost Genius (RON)'},
    {key: 'monthlyAccountingCostRon', label: 'Cost contabilitate / lună (RON)'},
    {key: 'accountingCostPerUnitRon', label: 'Cost contabilitate / unitate (RON)', computed: true},
    {key: 'employeeCostPerUnitRon', label: 'Cost angajați / unitate (RON)', computed: true},
    {key: 'miscellaneousCostPerUnitRon', label: 'Costuri diverse / unitate (RON)', computed: true},
    {key: 'warehousePreparationCostRon', label: 'Cost pregătire colet depozit propriu (RON)'},
    {key: 'interestCostPerUnitRon', label: 'Dobândă / lună per produs (RON)', computed: true}
  ]},
  {id: 'employees', target: 'dateCalculEmployeesFields', rowLabel: 'Angajat', total: 'employeeTotalRon'},
  {id: 'miscellaneous', target: 'dateCalculMiscellaneousFields', rowLabel: 'Cheltuială', total: 'miscellaneousTotalRon'}
];

const endpoint = '/app/profitCalculatorSettings';
const amountFormat = new Intl.NumberFormat('en-GB', {minimumFractionDigits: 2, maximumFractionDigits: 2});
const unitsFormat = new Intl.NumberFormat('en-GB');
const decimal = /^-?(?:\d+(?:\.\d*)?|\.\d+)$/;
const editableFields = section => section.fields.filter(field => !field.computed);

function shiftDecimal(value, places) {
  const negative = value.startsWith('-');
  const [integer, fraction = ''] = (negative ? value.slice(1) : value).split('.');
  const digits = integer + fraction;
  const position = integer.length + places;
  const whole = (position <= 0 ? '0' : digits.slice(0, position).padEnd(position, '0'))
    .replace(/^0+(?=\d)/, '');
  const remainder = (position < 0 ? '0'.repeat(-position) + digits : digits.slice(position)).replace(/0+$/, '');
  const normalized = remainder ? `${whole}.${remainder}` : whole;
  return negative && normalized !== '0' ? `-${normalized}` : normalized;
}

function parseInput(field, raw) {
  const text = raw.trim();
  if (!text) return {value: null};
  if (!decimal.test(text) || !field.signed && text.startsWith('-')) {
    return {error: field.signed ? 'Enter a decimal number using a decimal point.'
      : 'Enter a nonnegative decimal number using a decimal point.'};
  }
  const normalized = shiftDecimal(text, 0);
  const [whole] = normalized.replace(/^-/, '').split('.');
  const atLeast100 = whole.length > 3 || whole.length === 3 && whole >= '100';
  if (field.bounded && atLeast100 && normalized !== '100') return {error: 'Enter a percentage from 0 to 100.'};
  if (field.signed && normalized.startsWith('-') && atLeast100) return {error: 'Enter a percentage greater than −100.'};
  if (field.positive && normalized === '0') return {error: 'Enter a value greater than zero.'};
  const value = field.percent ? shiftDecimal(normalized, -2) : normalized;
  return value.length <= 64 ? {value} : {error: 'Use at most 64 decimal characters after conversion.'};
}

function inputValues(section, saved) {
  if (section.fields) return Object.fromEntries(editableFields(section).map(field => [
    field.key, saved.values[field.key] == null ? '' : shiftDecimal(saved.values[field.key], field.percent ? 2 : 0)
  ]));
  return saved.items.map(item => ({label: item.label, amountRon: item.amountRon ?? ''}));
}

function payloadFor(section, inputs) {
  const errors = new Map();
  function read(field, raw, key) {
    const result = parseInput(field, raw);
    if (result.error) errors.set(key, result.error);
    return result.value;
  }
  if (section.fields) {
    const values = Object.fromEntries(editableFields(section)
      .map(field => [field.key, read(field, inputs[field.key], field.key)]));
    return {payload: {values}, errors};
  }
  const items = inputs.map((item, index) => {
    const label = item.label.trim();
    if (!label) errors.set(`label-${index}`, 'Enter a name.');
    return {label, amountRon: read({}, item.amountRon, `amountRon-${index}`)};
  });
  return {payload: {items}, errors};
}

function validateSettings(group, data) {
  const isDecimal = value => value === null || typeof value === 'string' && decimal.test(value);
  if (data?.vendorGroup !== group || !data.calculated) throw new Error('Invalid settings response.');
  for (const section of sections) {
    const saved = data[section.id];
    if (!Number.isSafeInteger(saved?.revision) || saved.revision < 0) throw new Error('Invalid settings revision.');
    const valid = section.fields
      ? editableFields(section).every(field => isDecimal(saved.values?.[field.key]))
      : Array.isArray(saved.items) && saved.items.every(item => typeof item?.label === 'string' && isDecimal(item.amountRon));
    if (!valid) throw new Error('Invalid settings values.');
  }
  return data;
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function button(label, action, section, handler) {
  const node = element('button', label, action === 'save' ? 'primary-action' : '');
  node.type = 'button';
  node.dataset.section = section;
  node.dataset.action = action;
  node.addEventListener('click', handler);
  return node;
}

export function initDateCalcul() {
  if (!document.getElementById('profitDateCalculPage')) return;
  const selector = document.getElementById('dateCalculGroup');
  const status = document.getElementById('dateCalculStatus');
  const retry = document.getElementById('dateCalculRetry');
  const editors = document.getElementById('dateCalculEditors');
  const models = new Map();
  let loading = false;
  let saving = false;
  let requestVersion = 0;
  let retryAction = loadGroups;

  function report(message, action) {
    status.textContent = message;
    status.hidden = !message;
    retry.hidden = !action;
    if (action) retryAction = action;
  }

  function render() {
    const model = models.get(selector.value);
    editors.hidden = !model;
    editors.setAttribute('aria-busy', String(loading || saving));
    for (const section of sections) {
      const target = document.getElementById(section.target);
      target.replaceChildren();
      if (model) renderSection(target, section, model);
    }
  }

  function renderSection(target, section, model) {
    const draft = model.drafts.get(section.id) ?? {
      saved: model.data[section.id], inputs: inputValues(section, model.data[section.id])
    };
    const baseline = JSON.stringify(payloadFor(section, inputValues(section, draft.saved)).payload);
    const controls = [];
    const busy = loading || saving || model.needsReadback;
    const computed = model.needsReadback ? {} : model.data.calculated;
    let add;
    const save = button('Save', 'save', section.id, () => saveSection(section, model, draft));
    const revert = button('Revert', 'revert', section.id, () => {
      if (revert.disabled) return;
      model.drafts.delete(section.id);
      loadSettings(model.data.vendorGroup);
    });

    function refresh() {
      const {payload, errors} = payloadFor(section, draft.inputs);
      const dirty = errors.size > 0 || JSON.stringify(payload) !== baseline;
      if (dirty) model.drafts.set(section.id, draft);
      else model.drafts.delete(section.id);
      save.disabled = busy || !dirty || errors.size > 0;
      revert.disabled = busy || !dirty;
      if (add) add.disabled = busy;
      for (const {input, error, key} of controls) {
        const message = errors.get(key);
        input.setAttribute('aria-invalid', String(Boolean(message)));
        error.textContent = message ?? '';
        error.hidden = !message;
      }
    }

    function inputControl(container, key, label, raw, update, numeric = true) {
      const input = element('input');
      input.type = 'text';
      input.id = `dateCalcul-${section.id}-${key}`;
      input.dataset.field = key;
      input.autocomplete = 'off';
      input.disabled = busy;
      if (numeric) input.inputMode = 'decimal';
      input.setAttribute('aria-label', label);
      input.value = raw;
      const error = element('span', null, 'date-calcul-error');
      error.id = `${input.id}-error`;
      input.setAttribute('aria-describedby', error.id);
      input.addEventListener('input', () => {
        update(input.value);
        refresh();
      });
      controls.push({input, error, key});
      container.appendChild(input);
      container.appendChild(error);
      return input;
    }

    if (section.fields) {
      const fields = element('div', null, 'date-calcul-fields');
      for (const field of section.fields) {
        if (field.computed) {
          const units = field.key === 'previousMonthUnits';
          const label = units && computed.previousMonth ? `${field.label} (${computed.previousMonth})` : field.label;
          const value = computed[field.key];
          const output = element('output', value == null ? '—' : (units ? unitsFormat : amountFormat).format(value));
          output.dataset.computed = field.key;
          output.setAttribute('aria-label', label);
          fields.appendChild(element('span', label));
          fields.appendChild(output);
        } else {
          const control = element('div');
          const input = inputControl(control, field.key, field.label, draft.inputs[field.key],
            value => { draft.inputs[field.key] = value; });
          const label = element('label', field.label);
          label.htmlFor = input.id;
          fields.appendChild(label);
          fields.appendChild(control);
        }
      }
      target.appendChild(fields);
    } else {
      const table = element('table', null, 'date-calcul-list');
      table.setAttribute('aria-label', section.rowLabel === 'Angajat' ? 'Costuri angajați' : 'Costuri diverse');
      const head = element('thead');
      const headings = element('tr');
      for (const label of [section.rowLabel, 'RON / lună', '']) {
        const heading = element('th', label);
        heading.scope = 'col';
        headings.appendChild(heading);
      }
      head.appendChild(headings);
      table.appendChild(head);
      const list = element('tbody');
      draft.inputs.forEach((item, index) => {
        const row = element('tr');
        const name = element('td');
        inputControl(name, `label-${index}`, `${section.rowLabel} ${index + 1}`, item.label,
          value => { item.label = value; }, false);
        const amount = element('td');
        inputControl(amount, `amountRon-${index}`, `Cost lunar în RON, rândul ${index + 1}`, item.amountRon,
          value => { item.amountRon = value; });
        const actions = element('td');
        const remove = button('Remove', 'remove', section.id, () => {
          draft.inputs.splice(index, 1);
          refresh();
          render();
        });
        remove.setAttribute('aria-label', `Remove row ${index + 1}`);
        remove.disabled = busy;
        actions.appendChild(remove);
        row.appendChild(name);
        row.appendChild(amount);
        row.appendChild(actions);
        list.appendChild(row);
      });
      table.appendChild(list);
      const foot = element('tfoot');
      const total = element('tr');
      const label = element('th', 'Total / lună');
      label.scope = 'row';
      const value = element('td');
      value.colSpan = 2;
      const output = element('output', computed[section.total] == null ? '—' : amountFormat.format(computed[section.total]));
      output.dataset.computed = section.total;
      value.appendChild(output);
      total.appendChild(label);
      total.appendChild(value);
      foot.appendChild(total);
      table.appendChild(foot);
      target.appendChild(table);
      add = button('Add row', 'add', section.id, () => {
        const index = draft.inputs.length;
        draft.inputs.push({label: '', amountRon: ''});
        refresh();
        render();
        document.getElementById(`dateCalcul-${section.id}-label-${index}`)?.focus();
      });
    }

    const actions = element('div', null, 'page-actions date-calcul-actions');
    if (add) actions.appendChild(add);
    actions.appendChild(save);
    actions.appendChild(revert);
    target.appendChild(actions);
    refresh();
  }

  async function loadSettings(group) {
    const version = ++requestVersion;
    loading = true;
    const model = models.get(group);
    report(model?.needsReadback ? 'Settings saved. Reloading Date Calcul…' : 'Loading Date Calcul…');
    render();
    try {
      const settings = validateSettings(group, await fetchJSON(`${endpoint}?vendorGroup=${encodeURIComponent(group)}`));
      if (version !== requestVersion) return;
      models.set(group, {data: settings, drafts: model?.drafts ?? new Map()});
      report(model?.needsReadback ? 'Settings saved.' : '');
    } catch {
      if (version !== requestVersion) return;
      report(model?.needsReadback
        ? 'Settings were saved. Retry to reload the calculated values.'
        : 'Could not load Date Calcul. Retry to load the saved settings.', () => loadSettings(group));
    } finally {
      if (version === requestVersion) {
        loading = false;
        render();
      }
    }
  }

  async function saveSection(section, model, draft) {
    if (loading || saving || model.needsReadback) return;
    const {payload, errors} = payloadFor(section, draft.inputs);
    if (errors.size || !model.drafts.has(section.id)) return;
    const vendorGroup = model.data.vendorGroup;
    saving = true;
    selector.disabled = true;
    report('Saving settings…');
    render();
    try {
      const response = await fetch(endpoint, {method: 'POST', headers: {'Content-Type': 'application/json',
        Accept: 'application/json'}, body: JSON.stringify({vendorGroup, section: section.id,
        expectedRevision: draft.saved.revision, ...payload})});
      const result = await response.json().catch(() => ({}));
      if (response.status === 409) {
        report('Saved settings changed. Your edits are still here. Revert to load the current version.');
        return;
      }
      if (!response.ok || result.saved !== true) throw new Error(result.error || 'Save was not confirmed.');
      model.data = {...model.data, [section.id]: {...draft.saved, ...payload}};
      model.drafts.delete(section.id);
      model.needsReadback = true;
      await loadSettings(vendorGroup);
    } catch (error) {
      report(`Could not save settings. Your edits are still here. ${error.message}`);
    } finally {
      saving = false;
      selector.disabled = false;
      render();
    }
  }

  async function loadGroups() {
    report('Loading vendor groups…');
    try {
      const options = await fetchJSON('/app/profitCalculatorOptions');
      const groups = options.vendorGroups.map(option => option.vendorGroup);
      selector.replaceChildren(...(groups.length ? groups : ['']).map(group => {
        const option = element('option', group || 'No vendor groups');
        option.value = group;
        return option;
      }));
      selector.disabled = !groups.length;
      if (!groups.length) {
        report('No vendor groups are available.');
        return;
      }
      selector.value = groups.includes(options.defaultVendorGroup) ? options.defaultVendorGroup : groups[0];
      await loadSettings(selector.value);
    } catch {
      report('Could not load vendor groups.', loadGroups);
    }
  }

  selector.addEventListener('change', () => { if (selector.value && !saving) loadSettings(selector.value); });
  retry.addEventListener('click', () => { if (!retry.hidden && !loading && !saving) retryAction(); });
  window.addEventListener('beforeunload', event => {
    if (![...models.values()].some(model => model.drafts.size)) return;
    event.preventDefault();
    event.returnValue = '';
  });
  loadGroups();
}

if (typeof document !== 'undefined') initDateCalcul();
