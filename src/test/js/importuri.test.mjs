import assert from 'node:assert/strict';
import test from 'node:test';
import {applyPacking, pageLink, searchRegister, trackingUrl} from '../../main/resources/static/js/importuri.js';

test('company and record remain distinct URL parameters', () => {
  for (const [group, id] of [['S', 'new'], ['Z', null], ['K', 'a&b']]) {
    const url = new URL(pageLink(group, id), 'https://example.test');
    assert.equal(url.pathname, '/private/importuri');
    assert.equal(url.searchParams.get('group'), group);
    assert.equal(url.searchParams.get('import'), id);
  }
});

test('register search finds alphanumeric references, Romanian labels and zero-quantity imports', () => {
  const sample = [{reference: '49A', units: 0}, {reference: '12 + 13', title: 'Mașini de găurit'}];
  assert.deepEqual(searchRegister(sample, '49a'), [sample[0]]);
  assert.deepEqual(searchRegister(sample, 'masini de gaurit'), [sample[1]]);
  assert.deepEqual(searchRegister(sample, '0'), [sample[0]]);
  assert.deepEqual(searchRegister(sample, '  '), sample);
});

test('switching mode copies the new packing snapshot and clears unsupported measurements', () => {
  const line = {unitUsd: '123.456789123456789', quantity: '0', kgPerCarton: '12', m3PerCarton: '5'};
  const product = {packing: {AIR: {piecesPerCarton: '40', kgPerCarton: '10.1234567890123456', m3PerCarton: '0'}}};
  applyPacking(line, product, 'AIR');
  assert.equal(line.kgPerCarton, '10.1234567890123456');
  assert.equal(line.m3PerCarton, '0');
  applyPacking(line, product, 'SEA');
  for (const key of ['piecesPerCarton', 'kgPerCarton', 'm3PerCarton']) assert.equal(line[key], null);
  assert.equal(line.unitUsd, '123.456789123456789');
  assert.equal(line.quantity, '0');
});

test('tracking uses an explicit web URL or the current carrier and tracking number', () => {
  assert.equal(trackingUrl({carrier: 'DHL', tracking: '1&2'}), 'https://www.dhl.com/ro-ro/home/tracking/tracking-express.html?submit=1&tracking-id=1%262');
  assert.equal(trackingUrl({carrier: 'FedEx', tracking: '42'}), 'https://www.fedex.com/fedextrack/?trknbr=42');
  assert.equal(trackingUrl({carrier: 'GLS'}), 'https://gls-group.com/GROUP/en/parcel-tracking');
  assert.equal(trackingUrl({carrier: 'FedEx'}), null);
  assert.equal(trackingUrl({carrier: 'GLS', trackingLink: 'https://transport.example/1'}), 'https://transport.example/1');
  assert.equal(trackingUrl({trackingLink: 'javascript:void(0)'}), null);
});
