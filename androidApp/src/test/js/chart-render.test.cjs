const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

function harness() {
  const html = readFileSync(join(__dirname, '../../main/assets/chart/index.html'), 'utf8');
  const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { clientWidth: 0, clientHeight: 0, style: {}, value: 'VOL',
      textContent: '', replaceChildren() {}, appendChild() {}, classList: { toggle() {} } });
    return nodes.get(id);
  };
  let next = 0;
  const timers = new Map();
  let applied = [];
  const chart = new Proxy({}, { get: (_, key) => key === 'applyNewData' ? data => { applied = data; }
    : key === 'createIndicator' ? () => 'volume' : () => {} });
  const context = { console, document: { getElementById: node, querySelector: node,
    createElement: node, body: node('body') }, klinecharts: { init: () => chart },
    ResizeObserver: class { observe() {} },
    setTimeout: fn => { timers.set(++next, fn); return next; }, clearTimeout: id => timers.delete(id),
    requestAnimationFrame: fn => { timers.set(++next, fn); return next; } };
  context.window = context;
  vm.runInNewContext(script, context);
  return { context, node, applied: () => applied, flush() {
    for (let i = 0; timers.size && i < 20; i++) {
      const batch = [...timers.values()]; timers.clear(); batch.forEach(fn => fn());
    }
  } };
}

const payload = period => ({ code: '000001', period, dailyCount: 250, focus: -1, highlight: 0,
  bars: [{ date: period === 'D' ? '2026-09-14' : '2026-09-07', open: 10, close: 11, high: 12, low: 9, volume: 100 }] });

test('an old layout retry must not overwrite the latest selected period', () => {
  const h = harness();
  h.context.renderChart(payload('D'));
  Object.assign(h.node('chart'), { clientWidth: 320, clientHeight: 480 });
  h.context.renderChart(payload('W'));
  h.flush();
  assert.match(h.node('periodInfo').textContent, /^周K/);
  assert.equal(h.applied()[0].date, '2026-09-07');
});

test('only the latest payload is applied when a hidden chart becomes visible', () => {
  const h = harness();
  ['D', 'W', 'M'].forEach(p => h.context.renderChart(payload(p)));
  Object.assign(h.node('chart'), { clientWidth: 320, clientHeight: 480 });
  h.flush();
  assert.match(h.node('periodInfo').textContent, /^月K/);
});
