const API = '/app/stocuri';
const GROUP_NAMES = {J: 'Judios', K: 'Koppel', S: 'Sellfusion', Z: 'Zoopie'};
const METHODS = ['AIR', 'RAIL', 'SEA', 'TRUCK'];
const STATUS = ['Comandat', 'Expediat', 'In vama', 'Livrat'];
const PAYMENTS = {purchase: 'Achiziție', freight: 'Transport', duty: 'Taxă vamală', vat: 'TVA'};
const CHECKS = [
  ['defects', 'Defecte raportate și soluționate'], ['manual', 'Manual sau artwork pregătit'],
  ['pallet', 'Marfă paletizată'], ['ean', 'EAN verificat'], ['freightQuote', 'Ofertă transport solicitată'],
  ['receptionDocs', 'Documente de recepție trimise'], ['eTransport', 'e-Transport'],
  ['customsDeclarations', 'Declarații vamale trimise']
];
const numberFormat = new Intl.NumberFormat('ro-RO', {maximumFractionDigits: 4});
const moneyFormat = new Intl.NumberFormat('ro-RO', {minimumFractionDigits: 2, maximumFractionDigits: 2});

export function pageLink(group, id) {
  const params = new URLSearchParams({group});
  if (id) params.set('import', id);
  return '/private/importuri?' + params;
}

export function searchRegister(rows, query) {
  const normalize = value => String(value ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('ro');
  const term = normalize(query.trim());
  return rows.filter(row => [row.reference, row.title, row.sellingCompany, row.status, row.method,
    row.orderDate, row.eta, row.arrivalDate, row.units].some(value => normalize(value).includes(term)));
}

export function applyPacking(line, product, method) {
  const packing = product.packing?.[method];
  for (const key of ['piecesPerCarton', 'kgPerCarton', 'm3PerCarton']) {
    line[key] = packing?.[key] ?? null;
  }
}

export function trackingUrl(fields) {
  const explicit = fields.trackingLink?.trim();
  if (explicit) {
    try {
      const url = new URL(explicit);
      return ['https:', 'http:'].includes(url.protocol) ? url.href : null;
    } catch { return null; }
  }
  const carrier = fields.carrier?.trim().toLowerCase();
  if (carrier === 'gls') return 'https://gls-group.com/GROUP/en/parcel-tracking';
  const tracking = fields.tracking?.trim();
  if (!tracking) return null;
  if (carrier === 'dhl') return 'https://www.dhl.com/ro-ro/home/tracking/tracking-express.html?submit=1&tracking-id=' + encodeURIComponent(tracking);
  if (carrier === 'fedex') return 'https://www.fedex.com/fedextrack/?trknbr=' + encodeURIComponent(tracking);
  return null;
}

function newDocument() {
  return {id: null, revision: 0, fields: {}, lines: [], transfers: [], invoices: [], taric: []};
}

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text != null) el.textContent = text;
  if (className) el.className = className;
  return el;
}

function button(label, listener, className) {
  const el = node('button', label, className);
  el.type = 'button';
  el.addEventListener('click', listener);
  return el;
}

function metric(value, money = false) {
  if (value == null || value === '') return '—';
  return (money ? moneyFormat : numberFormat).format(Number(value));
}

function calendarDay(value) {
  if (!value) return '—';
  const match = /^(\d{4})-(\d\d)-(\d\d)$/.exec(value);
  return match ? match[3] + '.' + match[2] + '.' + match[1] : value;
}

function statusBadge(value) {
  const badge = node('span', value === 'In vama' ? 'În vamă' : value, 'imports-status');
  badge.dataset.state = {Comandat: 'ordered', Expediat: 'shipped', 'In vama': 'customs', Livrat: 'delivered'}[value] || '';
  return badge;
}

async function request(url, method = 'GET', payload) {
  const headers = {Accept: 'application/json'};
  if (payload) headers['Content-Type'] = 'application/json';
  const res = await fetch(url, {method, headers, body: payload ? JSON.stringify(payload) : undefined});
  if (!res.ok) {
    const failure = await res.json().catch(() => ({}));
    const error = new Error(failure.error || 'Cererea nu a reușit (HTTP ' + res.status + ').');
    error.status = res.status;
    throw error;
  }
  return res.json();
}

function table(headers, className) {
  const wrap = node('div', null, 'imports-table-wrap');
  const element = node('table', null, className);
  const head = element.createTHead().insertRow();
  for (const title of headers) {
    const th = node('th', title);
    th.scope = 'col';
    head.append(th);
  }
  const body = element.createTBody();
  wrap.append(element);
  return {wrap, body};
}

function main() {
  const byId = id => document.getElementById(id);
  const groupSelect = byId('importGroup');
  const notice = byId('importNotice');
  const retry = byId('importsRetry');
  const editor = byId('importEditor');
  const saveButton = byId('saveImport');
  const saveStatus = byId('importSaveStatus');
  const sections = byId('importSections');
  const costs = byId('importCosts');
  const search = byId('importSearch');
  const params = new URLSearchParams(location.search);
  const requestedGroup = params.get('group');
  let group, draft, catalog = [], catalogLoaded = false, register = [];
  let dirty = false, saving = false, conflict = false, calcVersion = 0, timer;
  const endpoint = () => API + '/' + encodeURIComponent(group.key);
  const setSavedState = text => {
    saveStatus.textContent = text;
    delete saveStatus.dataset.error;
  };

  function signal(message, action) {
    notice.textContent = message;
    notice.toggleAttribute('data-error', Boolean(action));
    retry.hidden = !action;
    retry.onclick = action || null;
  }

  function updateHeading() {
    byId('importHeading').textContent = draft.fields.reference ? 'Import ' + draft.fields.reference :
      (draft.id ? 'Import fără referință' : 'Import nou');
    const state = byId('importState');
    state.textContent = GROUP_NAMES[group.key] || group.key;
    if (draft.fields.status) state.append(document.createTextNode(' · '), statusBadge(draft.fields.status));
  }

  function changed() {
    dirty = true;
    conflict = false;
    updateHeading();
    setSavedState('Modificări nesalvate');
    saveButton.disabled = saving;
    scheduleCalculation();
  }

  function scheduleCalculation() {
    clearTimeout(timer);
    const version = ++calcVersion;
    costs.textContent = 'Se recalculează…';
    timer = setTimeout(async () => {
      try {
        const calculation = await request(endpoint() + '/calculate', 'POST', draft);
        if (version === calcVersion) showCalculation(calculation);
      } catch (error) {
        if (version !== calcVersion) return;
        costs.replaceChildren(node('p', error.message), button('Reîncearcă calculul', scheduleCalculation));
      }
    }, 300);
  }

  function showRegister() {
    const holder = byId('importsList');
    const visible = searchRegister(register, search.value);
    if (!visible.length) {
      holder.replaceChildren(node('p', search.value.trim() ? 'Niciun import pentru această căutare.' :
        'Această companie nu are încă importuri.', 'imports-empty'));
      return;
    }
    const headers = ['Import', 'Firma vânzătoare', 'Status', 'Comandă', 'Livrare / ETA', 'Bucăți'];
    const {wrap, body} = table(headers, 'imports-register-table');
    for (const entry of visible) {
      const row = body.insertRow();
      const link = node('a', entry.reference ? 'Import ' + entry.reference : 'Fără referință', 'imports-reference');
      link.href = pageLink(group.key, entry.id);
      const first = row.insertCell();
      first.append(link);
      if (entry.title) first.append(node('span', entry.title, 'imports-subtext'));
      const delivery = entry.arrivalDate ? calendarDay(entry.arrivalDate) : entry.eta ? 'ETA ' + calendarDay(entry.eta) : '—';
      [entry.sellingCompany, entry.status, calendarDay(entry.orderDate), delivery, metric(entry.units)].forEach((value, index) => {
        const cell = row.insertCell();
        if (index === 1 && value) cell.append(statusBadge(value));
        else cell.textContent = value || '—';
        cell.dataset.label = headers[index + 1];
      });
    }
    holder.replaceChildren(wrap);
  }

  async function openRegister() {
    byId('newImport').href = pageLink(group.key, 'new');
    byId('newImport').hidden = false;
    signal('Se încarcă registrul…');
    try {
      register = await request(endpoint() + '/imports');
      showRegister();
      byId('importsRegister').hidden = false;
      signal('');
    } catch (error) {
      signal(error.message, openRegister);
    }
  }

  // Empty document fields are omitted. Child decimal inputs keep their text precision through JSON.
  function field(owner, key, labelText, type = 'text', options, after) {
    const label = node('label', null, type === 'textarea' ? 'imports-wide' : '');
    label.append(node('span', labelText));
    const input = node(type === 'select' ? 'select' : type === 'textarea' ? 'textarea' : 'input');
    if (type === 'select') {
      input.add(new Option('—', ''));
      const entries = Array.isArray(options) ? options.map(value => [value, value]) : Object.entries(options);
      if (owner[key] && !entries.some(([val]) => val === owner[key])) entries.push([owner[key], owner[key]]);
      for (const [value, name] of entries) input.add(new Option(name, value));
    } else if (type === 'textarea') {
      input.rows = 3;
    } else {
      input.type = type;
      if (type === 'number') {
        if (key !== 'quantity' || !draft.lines.includes(owner)) input.min = '0';
        input.step = key === 'transitDays' ? '1' : 'any';
      }
    }
    input.value = owner[key] ?? '';
    input.name = key;
    input.required = key === 'reference' && (owner === draft.fields || draft.invoices.includes(owner));
    label.append(input);
    input.addEventListener(type === 'select' ? 'change' : 'input', () => {
      if (input.value === '') delete owner[key];
      else owner[key] = input.value;
      after?.();
      changed();
    });
    return label;
  }

  function grid(owner, descriptors) {
    const el = node('div', null, 'imports-grid');
    for (const spec of descriptors) el.append(field(owner, ...spec));
    return el;
  }

  function section(key, title, previous) {
    const el = node('details', null, 'imports-section');
    el.dataset.key = key;
    el.open = previous ? previous.has(key) : key === 'details';
    el.append(node('summary', title));
    const body = node('div', null, 'imports-body');
    el.append(body);
    sections.append(el);
    return body;
  }

  function checklist() {
    const checks = node('div', null, 'imports-checklist');
    for (const [key, title] of CHECKS) {
      const label = node('label', null, 'imports-check');
      const input = node('input');
      input.type = 'checkbox';
      input.name = key;
      input.checked = /^(true|da|1)$/i.test(draft.fields[key] || '');
      input.addEventListener('change', () => {
        draft.fields[key] = String(input.checked);
        changed();
      });
      label.append(input, document.createTextNode(title));
      checks.append(label);
    }
    return checks;
  }

  function addRows(body, array, name, emptyRow, rowFields, addLabel, after) {
    const container = node('div');
    const add = button(addLabel, () => {
      array.push({...emptyRow});
      redraw();
      changed();
      container.lastElementChild?.querySelector('input,select')?.focus();
    }, 'imports-add-row');
    function redraw() {
      container.replaceChildren();
      array.forEach((row, index) => {
        const card = node('div', null, 'imports-record');
        const heading = node('div', null, 'imports-card-heading');
        const title = name + ' ' + (index + 1);
        const remove = button('Elimină', () => {
          array.splice(index, 1);
          redraw();
          changed();
          add.focus();
        });
        remove.setAttribute('aria-label', 'Elimină ' + title.toLocaleLowerCase('ro'));
        heading.append(node('h4', title), remove);
        card.append(heading, rowFields(row, index));
        after?.(card, row);
        container.append(card);
      });
    }
    redraw();
    body.append(container, add);
    return redraw;
  }

  async function loadCatalog() {
    try {
      catalog = await request(endpoint() + '/products');
      catalogLoaded = true;
      if (!editor.hidden) renderSections();
      signal('');
    } catch (error) {
      signal('Catalogul de produse nu este disponibil. Poți introduce produsele manual. ' + error.message, loadCatalog);
    }
  }

  function productPicker(products, redraw) {
    const wrapper = node('div', null, 'imports-picker');
    const label = node('label', 'Adaugă din catalog');
    const input = node('input');
    input.type = 'search';
    input.placeholder = 'Caută model, PNK sau cod produs';
    input.disabled = !catalogLoaded;
    const results = node('div', null, 'imports-results');
    const status = node('p', '', 'imports-muted');
    status.setAttribute('role', 'status');
    input.addEventListener('input', () => {
      const term = input.value.trim().toLocaleLowerCase('ro');
      results.replaceChildren();
      status.textContent = '';
      if (!term) return;
      const matches = catalog.filter(product => [product.model, product.name, product.productCode, product.pnk]
        .some(value => String(value || '').toLocaleLowerCase('ro').includes(term)));
      if (!matches.length) status.textContent = 'Niciun produs găsit.';
      else if (matches.length > 15) status.textContent = 'Primele 15 rezultate. Restrânge căutarea pentru alte produse.';
      for (const product of matches.slice(0, 15)) {
        const title = [product.model || product.name, product.productCode, product.pnk].filter(Boolean).join(' · ');
        results.append(button(title, () => {
          const line = {productCode: product.productCode, model: product.model || product.name,
            pnk: product.pnk, dutyRate: product.dutyRate};
          applyPacking(line, product, draft.fields.method);
          products.push(line);
          input.value = '';
          results.replaceChildren();
          redraw();
          changed();
          status.textContent = 'Produs adăugat.';
          input.focus();
        }));
      }
    });
    label.append(input);
    wrapper.append(label, status, results);
    return wrapper;
  }

  function productRows(body) {
    const holder = node('div');
    const add = button('Adaugă produs manual', () => {
      draft.lines.push({});
      redraw();
      changed();
      const models = holder.querySelectorAll('input[name="model"]');
      models[models.length - 1]?.focus();
    });
    function redraw() {
      holder.replaceChildren();
      if (!draft.lines.length) return;
      const {wrap, body: rows} = table(['Model / denumire', 'Bucăți', 'Preț / buc. (USD)', 'Detalii poziție'], 'imports-product-table');
      draft.lines.forEach((line, index) => {
        const tr = rows.insertRow();
        const spec = [['model', 'Model / denumire'], ['quantity', 'Cantitate (buc.)', 'number'], ['unitUsd', 'Preț / buc. (USD)', 'number']];
        for (const descriptor of spec) tr.insertCell().append(field(line, ...descriptor));
        const actions = tr.insertCell();
        const detailRow = rows.insertRow();
        const detailCell = detailRow.insertCell();
        detailCell.colSpan = 4;
        detailRow.id = 'importPacking-' + index;
        detailRow.hidden = true;
        detailCell.append(grid(line, [
          ['productCode', 'Cod produs'], ['pnk', 'PNK'], ['piecesPerCarton', 'Bucăți / carton', 'number'],
          ['kgPerCarton', 'Greutate / carton (kg)', 'number'], ['m3PerCarton', 'Volum / carton (m³)', 'number'],
          ['dutyRate', 'Cotă vamală (ex. 0,06 = 6%)', 'number']
        ]));
        const toggle = button('Ambalare și coduri', () => {
          detailRow.hidden = !detailRow.hidden;
          toggle.setAttribute('aria-expanded', String(!detailRow.hidden));
        });
        toggle.setAttribute('aria-expanded', 'false');
        toggle.setAttribute('aria-controls', detailRow.id);
        const remove = button('Elimină', () => {
          draft.lines.splice(index, 1);
          redraw();
          changed();
          add.focus();
        });
        remove.setAttribute('aria-label', 'Elimină poziția ' + (index + 1));
        actions.append(toggle, remove);
      });
      holder.append(wrap);
    }
    redraw();
    body.append(productPicker(draft.lines, redraw), holder, add);
    return redraw;
  }

  function renderSections() {
    const open = sections.children.length ? new Set([...sections.children].filter(s => s.open).map(s => s.dataset.key)) : null;
    sections.replaceChildren();
    const f = draft.fields;
    const basic = section('details', 'Detalii și transport', open);
    const tracking = node('a', 'Urmărește transportul');
    tracking.target = '_blank';
    tracking.rel = 'noopener noreferrer';
    function refreshTracking() {
      const url = trackingUrl(f);
      tracking.hidden = !url;
      if (url) tracking.href = url;
      else tracking.removeAttribute('href');
    }
    refreshTracking();
    function switchMethod() {
      for (const key of ['densityThreshold', 'transitDays']) {
        delete f[key];
        basic.querySelector('[name="' + key + '"]').value = '';
      }
      for (const line of draft.lines) {
        const product = catalog.find(p => line.productCode && p.productCode === line.productCode);
        applyPacking(line, product || {}, f.method);
      }
      drawProducts();
    }
    basic.append(grid(f, [
      ['reference', 'Referință import'], ['title', 'Denumire'], ['type', 'Tip import', 'select', ['Aprovizionare', 'Mostre']],
      ['sellingCompany', 'Firma vânzătoare', 'select', group.companies], ['status', 'Status transport', 'select', STATUS],
      ['orderDate', 'Data plasării comenzii', 'date'], ['shipDate', 'Data expedierii', 'date'], ['arrivalDate', 'Data livrării', 'date'],
      ['method', 'Metodă de transport', 'select', METHODS, switchMethod], ['carrier', 'Transportator', 'text', null, refreshTracking], ['address', 'Adresa de livrare'],
      ['tracking', 'Număr de urmărire', 'text', null, refreshTracking], ['trackingLink', 'Link de urmărire', 'text', null, refreshTracking], ['lot', 'Număr lot'],
      ['transitDays', 'Durată estimată de transport (zile)', 'number'],
      ['densityThreshold', 'Prag de densitate (kg/m³)', 'number'], ['notes', 'Observații', 'textarea']
    ]), tracking);
    const products = section('products', 'Produse', open);
    const drawProducts = productRows(products);
    const finance = section('finance', 'Plăți și facturi', open);
    finance.append(grid(f, [
      ['invoicedBy', 'Facturat de'], ['purchaseRate', 'Curs achiziție RON/USD', 'number'],
      ['freightUsd', 'Transport facturat (USD)', 'number'], ['freightRon', 'Transport facturat (RON)', 'number'],
      ['freightRate', 'Curs transport RON/USD', 'number']
    ]));
    finance.append(node('h4', 'Transferuri'), node('p',
      'Introdu ambele sume pentru a calcula cursul efectiv RON/USD. Dacă ai o singură sumă, introdu cursul pentru a calcula echivalentul în cealaltă monedă.',
      'imports-muted'));
    addRows(finance, draft.transfers, 'Transferul', {kind: 'purchase'}, t => grid(t, [
      ['kind', 'Tip transfer', 'select', PAYMENTS], ['date', 'Data transferului', 'date'], ['reference', 'Referință / bancă'],
      ['ron', 'Valoare (RON)', 'number'], ['usd', 'Valoare (USD)', 'number'], ['rate', 'Curs RON/USD', 'number']
    ]), 'Adaugă transfer');
    finance.append(node('h4', 'Facturi'));
    addRows(finance, draft.invoices, 'Factura', {kind: 'purchase'}, invoice => grid(invoice, [
      ['kind', 'Tip factură', 'select', {purchase: 'Achiziție', freight: 'Transport'}], ['reference', 'Număr factură']
    ]), 'Adaugă factură');
    const customs = section('customs', 'Vamă și recepție', open);
    customs.append(grid(f, [
      ['dviNumber', 'Număr DVI'], ['dviDate', 'Data DVI', 'date'], ['nir', 'Număr NIR'], ['receiptRequest', 'Cerere de recepție'],
      ['vatRate', 'Cotă TVA (ex. 0,21 = 21%)', 'number'],
      ['twoStop', 'Livrare în două etape', 'select', ['Da', 'Nu']], ['packaging', 'Ambalaje comandate', 'select', ['Da', 'Nu']],
      ['deposit', 'Plată din depozit bănesc', 'select', ['Da', 'Nu']]
    ]), checklist(), node('h4', 'Poziții TARIC'));
    addRows(customs, draft.taric, 'Poziția TARIC', {}, t => grid(t, [
      ['code', 'Cod TARIC'], ['netKg', 'Masă netă (kg)', 'number'], ['grossKg', 'Masă brută (kg)', 'number'], ['quantity', 'Cantitate', 'number']
    ]), 'Adaugă poziție TARIC');
  }

  function showCalculation(c) {
    costs.replaceChildren();
    const totals = node('dl', null, 'imports-totals');
    const entries = [
      ['Cantitate', metric(c.totals.units) + ' buc.'], ['Total cu TVA', metric(c.totals.totalRon, true) + ' RON'],
      ['Curs achiziție', metric(c.totals.weightedPurchaseRate) + ' RON/USD'], ['Curs transport', metric(c.totals.weightedFreightRate) + ' RON/USD'],
      ['Greutate', metric(c.totals.kg) + ' kg'], ['Volum', metric(c.totals.m3) + ' m³'],
      ['ETA livrare', calendarDay(c.eta)], ['Durată producție', metric(c.totals.productionDays) + ' zile'],
      ['Durată transport efectivă', metric(c.totals.actualTransitDays) + ' zile']
    ];
    for (const [label, value] of entries) {
      const block = node('div');
      block.append(node('dt', label), node('dd', value));
      totals.append(block);
    }
    costs.append(totals);
    const payments = table(['Cost', 'Datorat (RON)', 'Plătit (RON)', 'Sold (RON)', 'Datorat (USD)', 'Plătit (USD)', 'Sold (USD)']);
    for (const [kind, title] of Object.entries(PAYMENTS)) {
      const row = payments.body.insertRow();
      const label = row.insertCell();
      label.textContent = title;
      if (kind !== 'purchase' && c.totals[kind + 'Ron'] != null) {
        label.append(node('span', c.totals[kind + 'PaidRon'] == null ? 'Estimat' : 'Pe baza plății', 'imports-subtext'));
      }
      for (const suffix of ['Ron', 'PaidRon', 'BalanceRon', 'Usd', 'PaidUsd', 'BalanceUsd']) {
        const key = kind === 'freight' && suffix === 'Ron' ? 'freightBilledRon' : kind + suffix;
        row.insertCell().textContent = metric(c.totals[key], true);
      }
    }
    costs.append(node('h4', 'Situația plăților'), payments.wrap);
    if (c.freightBasis) costs.append(node('p', 'Transport repartizat după ' + (c.freightBasis === 'kg' ? 'greutate (kg).' : 'volum (m³).'), 'imports-muted'));
    if (c.lines.length) {
      const {wrap, body} = table(['Produs', 'Achiziție / buc. (RON)', 'Transport / buc. (RON)', 'Vamă / buc. (RON)', 'TVA / buc. (RON)', 'Total / buc. (RON)']);
      c.lines.forEach((line, i) => {
        const tr = body.insertRow();
        tr.insertCell().textContent = draft.lines[i]?.model || 'Poziția ' + (i + 1);
        for (const key of ['purchaseUnitRon', 'freightUnitRon', 'dutyUnitRon', 'vatUnitRon', 'landedUnitRon']) tr.insertCell().textContent = metric(line[key]);
      });
      costs.append(wrap);
    }
    if (c.warnings.length) {
      const warnings = node('ul', null, 'imports-muted');
      for (const warning of c.warnings) warnings.append(node('li', warning));
      costs.append(warnings);
    }
  }

  async function openDocument(id) {
    const back = byId('backToImports');
    back.href = pageLink(group.key);
    back.hidden = false;
    signal('Se încarcă importul…');
    try {
      if (id === 'new') {
        draft = newDocument();
        if (group.key === 'S') draft.fields.vatRate = '0.21';
        if (group.companies.length === 1) draft.fields.sellingCompany = group.companies[0];
      } else {
        const saved = await request(endpoint() + '/imports/' + encodeURIComponent(id));
        draft = saved.document;
        showCalculation(saved.calculation);
      }
      await loadCatalog();
      renderSections();
      editor.hidden = false;
      updateHeading();
      if (!draft.id) scheduleCalculation();
      saveButton.disabled = true;
    } catch (error) {
      signal(error.message, () => openDocument(id));
    }
  }

  async function save(event) {
    event.preventDefault();
    if (!dirty || saving || conflict) return;
    saving = true;
    clearTimeout(timer);
    ++calcVersion;
    editor.inert = true;
    groupSelect.disabled = true;
    setSavedState('Se salvează…');
    try {
      const update = Boolean(draft.id);
      const url = endpoint() + '/imports' + (update ? '/' + encodeURIComponent(draft.id) : '');
      const stored = await request(url, update ? 'PUT' : 'POST', draft);
      draft = stored.document;
      dirty = false;
      history.replaceState(null, '', pageLink(group.key, draft.id));
      updateHeading();
      renderSections();
      showCalculation(stored.calculation);
      setSavedState('Salvat');
    } catch (error) {
      conflict = error.status === 409;
      saveStatus.textContent = error.message;
      if (conflict && draft.id) {
        saveStatus.append(document.createTextNode(' '));
        const reload = node('a', 'Deschide versiunea salvată într-o filă nouă');
        reload.href = pageLink(group.key, draft.id);
        reload.target = '_blank';
        reload.rel = 'noopener';
        saveStatus.append(reload);
      }
      saveStatus.dataset.error = '';
    } finally {
      saving = false;
      editor.inert = false;
      groupSelect.disabled = false;
      saveButton.disabled = !dirty || conflict;
    }
  }

  async function start() {
    try {
      signal('Se încarcă firmele…');
      const groups = await request(API + '/groups');
      groupSelect.replaceChildren(...groups.map(item => new Option(GROUP_NAMES[item.key] || item.key, item.key)));
      if (!groups.length) {
        signal('Nu sunt disponibile companii.');
        return;
      }
      group = requestedGroup ? groups.find(item => item.key === requestedGroup) : groups.find(item => item.key === 'S') || groups[0];
      groupSelect.disabled = false;
      if (!group) {
        groupSelect.prepend(new Option('Alege o companie', '', true, true));
        signal('Compania din adresă nu este disponibilă.');
        return;
      }
      groupSelect.value = group.key;
      const id = params.get('import');
      if (!requestedGroup) history.replaceState(null, '', pageLink(group.key, id));
      if (id) await openDocument(id);
      else await openRegister();
    } catch (error) {
      signal(error.message, start);
    }
  }

  groupSelect.addEventListener('change', () => {
    const next = groupSelect.value;
    if (!next) return;
    groupSelect.value = group?.key || '';
    location.assign(pageLink(next));
  });
  search.addEventListener('input', showRegister);
  editor.addEventListener('submit', save);
  editor.addEventListener('invalid', e => {
    const section = e.target.closest('details');
    if (section) section.open = true;
    const packing = e.target.closest('tr[hidden]');
    if (packing) editor.querySelector('[aria-controls="' + packing.id + '"]')?.click();
  }, true);
  window.addEventListener('beforeunload', e => {
    if (dirty || saving) { e.preventDefault(); e.returnValue = ''; }
  });
  start();
}

if (typeof document !== 'undefined' && document.getElementById('stockImports')) main();
