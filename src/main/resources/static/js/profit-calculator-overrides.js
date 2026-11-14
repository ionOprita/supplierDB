export const overrideFields = [
  {row: 40, key: 'salePriceRon', label: 'Pret vanzare prod. cu TVA [RON]'},
  {row: 41, key: 'commissionRate', label: 'Comision eMAG [%]', percent: true},
  {row: 42, key: 'purchasePriceUsd', label: 'Pret achizitie prod. / unit. [USD]'},
  {row: 43, key: 'transportPriceUsd', label: 'Pret transport prod. / unit. [USD]'},
  {row: 44, key: 'usdRonRate', label: 'Curs USDRON', positive: true},
  {row: 45, key: 'fulfillmentSource', label: 'De unde expediem', choices: ['eMAG', 'Minulescu']}
];

const fieldsByKey = new Map(overrideFields.map(field => [field.key, field]));
const dependencies = {
  salePriceRon: [26, 75, 86], commissionRate: [76, 86],
  purchasePriceUsd: [49, 77], transportPriceUsd: [50, 78],
  usdRonRate: [49, 50, 77, 78], fulfillmentSource: []
};

function normalizeDecimal(value) {
  const [integer, fraction = ''] = value.split('.');
  const whole = integer.replace(/^0+(?=\d)/, '') || '0';
  const tail = fraction.replace(/0+$/, '');
  return tail ? `${whole}.${tail}` : whole;
}

function shiftDecimal(value, places) {
  const [integer, fraction = ''] = value.split('.');
  const digits = integer + fraction;
  const position = integer.length + places;
  return normalizeDecimal(position <= 0 ? `0.${'0'.repeat(-position)}${digits}`
    : position >= digits.length ? digits + '0'.repeat(position - digits.length)
      : `${digits.slice(0, position)}.${digits.slice(position)}`);
}

function savedDecimal(value) {
  const text = String(value);
  const exponential = /^(\d+(?:\.\d+)?)[eE]([+-]?\d+)$/.exec(text);
  return exponential ? shiftDecimal(exponential[1], Number(exponential[2])) : normalizeDecimal(text);
}

export function overrideInputValue(field, value) {
  if (value == null) return '';
  if (field.choices) return String(value);
  const decimal = savedDecimal(value);
  return field.percent ? shiftDecimal(decimal, 2) : decimal;
}

export function parseOverrideInput(field, raw) {
  const text = String(raw ?? '').trim();
  if (!text) return {value: null, error: ''};
  if (field.choices) return field.choices.includes(text)
    ? {value: text, error: ''} : {error: 'Choose a listed fulfillment source.'};
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) {
    return {error: 'Enter a nonnegative decimal number using a decimal point.'};
  }
  const decimal = normalizeDecimal(text);
  if (!Number.isFinite(Number(decimal))) return {error: 'Enter a finite number.'};
  if (field.positive && Number(decimal) <= 0) return {error: 'The exchange rate must be greater than zero.'};
  const [whole, fraction] = decimal.split('.');
  if (field.percent && (whole.length > 3 || (whole.length === 3
      && (whole > '100' || (whole === '100' && fraction))))) {
    return {error: 'Commission must be between 0 and 100 percent.'};
  }
  const value = field.percent ? shiftDecimal(decimal, -2) : decimal;
  if (value.length > 64) return {error: 'Use at most 64 decimal characters.'};
  return {value, error: ''};
}

/** Original revisions are retained until Save or Revert, including across category reloads. */
export class OverrideDrafts {
  constructor() { this.entries = new Map(); }

  remember(products) {
    for (const product of products) {
      const previous = this.entries.get(product.productCode);
      if (previous && this.changedFields(previous).length) continue;
      const inputs = Object.fromEntries(overrideFields.map(field =>
        [field.key, overrideInputValue(field, product.overrides?.[field.key])]));
      this.entries.set(product.productCode, {product, inputs});
    }
  }

  edit(productCode, key, value) {
    if (!fieldsByKey.has(key)) return;
    const entry = this.entries.get(productCode);
    if (entry) entry.inputs[key] = value;
  }

  input(product, field) {
    return this.entries.get(product.productCode)?.inputs[field.key]
      ?? overrideInputValue(field, product.overrides?.[field.key]);
  }

  fieldState(productCode, field) {
    const entry = this.entries.get(productCode);
    if (!entry) return {dirty: false, error: ''};
    const parsed = parseOverrideInput(field, entry.inputs[field.key]);
    const original = parseOverrideInput(field, overrideInputValue(field, entry.product.overrides?.[field.key]));
    return {...parsed, dirty: Boolean(parsed.error) || parsed.value !== original.value};
  }

  changedFields(entry) {
    return overrideFields.filter(field => this.fieldState(entry.product.productCode, field).dirty);
  }

  hasChanges() { return [...this.entries.values()].some(entry => this.changedFields(entry).length); }
  hasErrors(codes) {
    return [...this.entries].some(([code]) => (!codes || codes.has(code))
      && overrideFields.some(field => this.fieldState(code, field).error));
  }

  changes(codes) {
    return [...this.entries].filter(([code]) => !codes || codes.has(code)).flatMap(([code, entry]) => {
      const fields = this.changedFields(entry);
      if (!fields.length || fields.some(field => this.fieldState(code, field).error)) return [];
      return [{productCode: code, expectedRevision: entry.product.overrideRevision ?? 0,
        values: Object.fromEntries(fields.map(field => [field.key, this.fieldState(code, field).value]))}];
    });
  }

  pendingRows(products) {
    return new Map(products.map(product => {
      const entry = this.entries.get(product.productCode);
      const fields = entry ? this.changedFields(entry) : [];
      return [product.productCode, new Set(fields.flatMap(field => dependencies[field.key]))];
    }));
  }

  reviewRows() {
    return [...this.entries.values()].flatMap(entry => this.changedFields(entry).map(field => ({
      product: entry.product.name || entry.product.pnk || entry.product.productCode,
      field: field.label,
      before: overrideInputValue(field, entry.product.overrides?.[field.key]) || 'Use default',
      after: entry.inputs[field.key].trim() || 'Use default'
    })));
  }

  clear() { this.entries.clear(); }
}
