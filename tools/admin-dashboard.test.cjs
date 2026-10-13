const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../server/public/admin.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const response = (data, status = 200) => ({ ok: status < 400, status, json: async () => ({ ok: status < 400, ...data }) });

function fixture({ size = 250, fetch: intercept } = {}) {
  const nodes = new Map(), requests = [], blobs = [];
  let formatters = 0;
  function node(selector) {
    if (!nodes.has(selector)) {
      const classes = new Set(['hidden']), handlers = new Map();
      let html = '';
      nodes.set(selector, {
        value: '', content: '', checked: false, disabled: false, dataset: {}, textContent: '', writes: 0, scrolls: 0,
        classList: { add: (...names) => names.forEach(name => classes.add(name)), remove: (...names) => names.forEach(name => classes.delete(name)), contains: name => classes.has(name), toggle(name, on) { if (on ?? !classes.has(name)) classes.add(name); else classes.delete(name); } },
        get innerHTML() { return html; }, set innerHTML(value) { html = value; this.writes++; },
        addEventListener(name, fn) { if (!handlers.has(name)) handlers.set(name, []); handlers.get(name).push(fn); },
        async emit(name, event = {}) { for (const fn of handlers.get(name) || []) await fn({ preventDefault() {}, ...event }); },
        querySelectorAll: () => [], insertAdjacentHTML(_where, value) { this.innerHTML += value; },
        setAttribute() {}, reset() {}, showModal() {}, close() {}, scrollIntoView() { this.scrolls++; }, click() {},
      });
    }
    return nodes.get(selector);
  }
  node('meta[name="sadad-api-base-url"]').content = 'https://example.test/sadad-api';
  node('#subscriptionMode').value = 'permanent';
  const stores = Array.from({ length: size }, (_, i) => ({ id: i + 1, name: `Store ${i + 1}`, username: `store${i + 1}`, status: 'active', subscriptionMode: 'permanent', permissions: {}, createdAt: 0, ledgerMode: 'current', maxDevices: 1, contacts: size, debts: size * 3, debtTotal: size * 30 }));
  const contacts = Array.from({ length: size }, (_, i) => ({ id: String(i), name: `Contact ${i}`, phone: `phone-${i}`, archived: i === 249, receivable: 30, payable: 0 }));
  const debts = Array.from({ length: size * 3 }, (_, i) => ({ id: String(i), contactId: String(i % size), direction: 'receivable', amount: 10, paid: 0, remaining: 10 }));
  const snapshot = { contacts, debts, payments: [], transactions: debts.map(row => ({ ...row, kind: 'debt', contactName: contacts[Number(row.contactId)].name, createdAt: 1728000000000, note: `row-${row.id}` })), totals: { receivable: size * 30, payable: 0, net: size * 30 }, requestId: 'read-1' };
  const detail = id => ({ store: stores[id - 1], snapshot, devices: [], installations: [], auditEvents: [], controlEvents: [] });
  function fallback(url, init) {
    const route = url.pathname.replace('/sadad-api/api', '');
    if (route === '/admin/session') return response({ admin: { username: 'test-admin' } });
    if (route === '/admin/dashboard') return response({ stores, overview: { stores: size, active: size, trusted: 0, debts: size * 30, pendingDevices: 0 }, meta: { username: 'test-admin' } });
    if (route === '/admin/logout') return response({});
    const match = route.match(/^\/admin\/stores\/(\d+)(?:\/(.*))?$/);
    if (match) {
      const id = Number(match[1]), action = match[2];
      if (action === 'ledger') return response({ snapshot, auditEvents: [] });
      if (init.method === 'POST') return response({ store: { maxDevices: 1 } });
      const data = detail(id);
      if (url.searchParams.get('includeLedger') === 'false') delete data.snapshot;
      return response(data);
    }
    throw new Error(`Unexpected fixture request: ${route}`);
  }
  const sandbox = {
    document: { querySelector: node, querySelectorAll: () => [], createElement: () => node('download') },
    sessionStorage: { getItem: () => 'test-session', setItem() {}, removeItem() {} },
    Intl: { NumberFormat: function(...args) { formatters++; return new Intl.NumberFormat(...args); }, DateTimeFormat: Intl.DateTimeFormat },
    AbortController, TextEncoder, Blob, URL: { createObjectURL(blob) { blobs.push(blob); return 'blob:test'; }, revokeObjectURL() {} },
    console, setTimeout: () => 0, clearTimeout() {}, confirm: () => true,
    fetch: async (raw, init) => { const url = new URL(raw); requests.push({ url, init }); return intercept ? intercept(url, init, () => fallback(url, init)) : fallback(url, init); },
  };
  vm.runInNewContext(source, sandbox);
  return { node, requests, blobs, snapshot, detail, get formatters() { return formatters; }, open: id => node('#storesBody').emit('click', { target: { closest: () => ({ dataset: { open: String(id) } }) } }) };
}

test('large Store and Ledger tables page, search the full Snapshot, and export every Transaction', async () => {
  const f = fixture(); await tick();
  assert.equal((f.node('#storesBody').innerHTML.match(/<tr>/g) || []).length, 100);
  await f.node('#storesNext').emit('click'); assert.match(f.node('#storesBody').innerHTML, /Store 101/);
  f.node('#searchStores').value = 'store250'; await f.node('#searchStores').emit('input');
  assert.match(f.node('#storesBody').innerHTML, /Store 250/); assert.equal(f.node('#storesNext').disabled, true);
  await f.open(1);
  assert.equal((f.node('#ledgerContacts').innerHTML.match(/<tr>/g) || []).length, 100);
  assert.equal((f.node('#ledgerDebts').innerHTML.match(/<tr>/g) || []).length, 100);
  await f.node('#ledgerContactsNext').emit('click'); assert.match(f.node('#ledgerContacts').innerHTML, /Contact 100/);
  await f.node('#ledgerDebtsNext').emit('click'); assert.match(f.node('#ledgerDebts').innerHTML, /Contact 100/);
  f.node('#ledgerSearch').value = 'phone-249'; await f.node('#ledgerSearch').emit('input');
  assert.match(f.node('#ledgerContacts').innerHTML, /Contact 249/); assert.match(f.node('#ledgerContacts').innerHTML, /مؤرشف/);
  assert.equal((f.node('#ledgerDebts').innerHTML.match(/<tr>/g) || []).length, 3); assert.match(f.node('#ledgerDebts').innerHTML, /Contact 249/);
  await f.node('#downloadReport').emit('click');
  const csv = await f.blobs[0].text(); assert.equal(csv.split('\r\n').length, 751); assert.match(csv, /row-749/);
  assert.equal(f.formatters, 1, 'one formatter for all rows and all renders');
});

test('account edits preserve Ledger, search and scroll; explicit refresh fetches it again', async () => {
  const f = fixture(); await tick(); await f.open(1);
  f.node('#ledgerSearch').value = 'Contact 20'; await f.node('#ledgerSearch').emit('input');
  const writes = f.node('#ledgerDebts').writes, scrolls = f.node('#storePanel').scrolls;
  const ledgerReads = () => f.requests.filter(r => r.url.pathname.endsWith('/ledger')).length;
  const dashboardReads = () => f.requests.filter(r => r.url.pathname.endsWith('/dashboard')).length;
  assert.equal(ledgerReads(), 1);
  await f.node('#saveSubscription').emit('click');
  assert.equal(dashboardReads(), 1, 'account edits do not recompute financial summaries for all Stores');
  assert.equal(ledgerReads(), 1); assert.equal(f.node('#ledgerDebts').writes, writes);
  assert.equal(f.node('#ledgerSearch').value, 'Contact 20'); assert.equal(f.node('#storePanel').scrolls, scrolls);
  await f.node('#refreshDashboard').emit('click'); assert.equal(ledgerReads(), 2);
  assert.equal(f.node('#ledgerSearch').value, 'Contact 20');
});

test('account controls appear before a delayed Ledger read; stale selection and close abort requests', async () => {
  const slow = deferred();
  const f = fixture({ fetch: (url, init, next) => url.pathname.endsWith('/stores/1/ledger') ? slow.promise : next() });
  await tick(); const opening = f.open(1); await tick();
  assert.equal(f.node('#storePanel').classList.contains('hidden'), false);
  assert.equal(f.node('#storeHeading').textContent, 'Store 1');
  await f.open(2);
  const oldRequest = f.requests.find(r => r.url.pathname.endsWith('/stores/1/ledger'));
  assert.equal(oldRequest.init.signal.aborted, true);
  slow.resolve(response({ snapshot: { ...f.snapshot, requestId: 'stale' }, auditEvents: [] })); await opening;
  assert.equal(f.node('#storeHeading').textContent, 'Store 2'); assert.doesNotMatch(f.node('#ledgerReadInfo').textContent, /stale/);
  await f.node('#closeStore').emit('click'); assert.equal(f.node('#storePanel').classList.contains('hidden'), true);
});

test('late dashboard results cannot overwrite a newer archived filter', async () => {
  const slow = deferred(); let calls = 0;
  const f = fixture({ fetch: (url, init, next) => url.pathname.endsWith('/dashboard') && ++calls === 1 ? slow.promise : next() });
  await tick(); f.node('#showArchived').checked = true; await f.node('#showArchived').emit('change');
  const latest = f.node('#storesBody').innerHTML;
  slow.resolve(response({ stores: [], overview: {}, meta: { username: 'old' } })); await tick();
  assert.equal(f.node('#storesBody').innerHTML, latest); assert.equal(f.node('#adminName').textContent, 'test-admin');
});

test('failed Ledger reads leave account controls usable, and logout invalidates pending reads', async () => {
  const f = fixture({ fetch: (url, init, next) => url.pathname.endsWith('/ledger') ? response({ message: 'Ledger unavailable' }, 503) : next() });
  await tick(); await f.open(1);
  assert.equal(f.node('#storePanel').classList.contains('hidden'), false);
  assert.match(f.node('#ledgerReadInfo').textContent, /تعذر تحديث السجل/);
  assert.equal(f.node('#ledgerPanel').classList.contains('hidden'), true);
  const slow = deferred();
  const g = fixture({ fetch: (url, init, next) => url.pathname.endsWith('/ledger') ? slow.promise : next() });
  await tick(); const opening = g.open(1); await tick(); await g.node('#logoutBtn').emit('click');
  assert.equal(g.requests.find(r => r.url.pathname.endsWith('/ledger')).init.signal.aborted, true);
  slow.resolve(response({ snapshot: g.snapshot })); await opening;
  assert.equal(g.node('#storePanel').classList.contains('hidden'), true);
  assert.equal(g.node('#ledgerPanel').classList.contains('hidden'), true);
});

test('saving an old Store cannot reopen it after the user selects another one', async () => {
  const slow = deferred();
  const f = fixture({ fetch: (url, init, next) => init.method === 'POST' && url.pathname.endsWith('/subscription') ? slow.promise : next() });
  await tick(); await f.open(1);
  const saving = f.node('#saveSubscription').emit('click'); await tick(); await f.open(2);
  slow.resolve(response({})); await saving;
  assert.equal(f.node('#storeHeading').textContent, 'Store 2');
  assert.equal(f.requests.filter(r => r.url.pathname.endsWith('/stores/1/ledger')).length, 1);
});
