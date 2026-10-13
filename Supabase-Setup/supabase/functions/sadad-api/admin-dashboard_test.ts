import { makeApi } from './handler.ts';

type Row = Record<string, any>;
const assert = (actual: unknown, expected: unknown) => { if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(`${JSON.stringify(actual)} != ${JSON.stringify(expected)}`); };
const CLOCK = 1_700_000_000_000;

function fixture({ session = true, failingRpc = '', concurrent = false } = {}) {
  const calls: string[] = [], summaryStarts: string[] = [];
  const pending: Array<() => void> = [];
  const stores: Row[] = [
    { id: 1, name: 'New store', username: 'new-store', status: 'active', subscription_mode: 'permanent', permissions: {}, created_at: 0 },
    { id: 2, name: 'Signed', username: 'signed', status: 'active', subscription_mode: 'permanent', permissions: {}, created_at: 0 },
    { id: 3, name: 'Archived', username: 'archived', status: 'deleted', subscription_mode: 'permanent', permissions: {}, created_at: 0 },
  ];
  const raw = { contacts: [{ id: 'c', name: 'Customer' }], debts: [{ id: 'd', contact_id: 'c', direction: 'receivable', amount_cents: 1000, created_at: 0 }], requestId: 'audited-read' };
  const tables: Record<string, Row[]> = {
    stores, sessions: session ? [{ kind: 'admin', principal_id: 1 }] : [], admins: [{ id: 1, username: 'admin', force_password_change: false }],

  };
  const db = {
    from(table: string) {
      if (['contacts','debts','payments','audit_events','store_backups','contact_archives','sync_receipts'].includes(table)) throw new Error('Retired table accessed: '+table);
      const filters: Record<string, unknown> = {};
      let update: Row | null = null, head = false;
      const result = () => {
        calls.push(table);
        let rows = tables[table] || [];
        if (filters.id !== undefined) rows = rows.filter(row => row.id === filters.id);
        if (update) rows.forEach(row => Object.assign(row, update));
        return { data: rows, error: null, count: head ? rows.length : null };
      };
      const q: any = {
        select(_fields: unknown, options?: { head?: boolean }) { head = !!options?.head; return q; },
        eq(key: string, value: unknown) { filters[key] = value; return q; },
        update(values: Row) { update = values; return q; },
        single() { const r = result(); return Promise.resolve({ ...r, data: r.data[0] || null }); },
        maybeSingle() { return q.single(); },
        then(resolve: (result: Row) => void, reject: (error: unknown) => void) { return Promise.resolve(result()).then(resolve, reject); },
      };
      for (const method of ['order', 'limit', 'gt', 'is', 'delete']) q[method] = () => q;
      return q;
    },
    rpc(name: string, args: Row = {}) {
      calls.push(name);
      if (name === failingRpc) return Promise.resolve({ data: null, error: new Error('query failed') });
      if (name === 'sadid_is_v3_store') return Promise.resolve({ data: Number(args.p_store) === 2, error: null });
      if (name === 'sadid_support_snapshot') return Promise.resolve({ data: raw, error: null });
      if (name === 'sadid_admin_controls') return Promise.resolve({ data: { hasDeviceHistory: Number(args.p_store) === 2, installations: [], controlEvents: [], accessEvents: [] }, error: null });
      const results: Record<string, Row[]> = {
        sadid_admin_summaries: [{ storeId: 1, initialized: false }, { storeId: 2, initialized: true, contacts: 1, debts: 1, debtTotal: 10 }],
        sadid_pending_installations: [{ storeId: 2, status: 'active' }, { storeId: 2, status: 'pending' }, { storeId: 2, status: 'revoked' }],
      };
      if (results[name]) {
        summaryStarts.push(name);
        if (!concurrent) return Promise.resolve({ data: results[name], error: null });
        return new Promise(resolve => {
          pending.push(() => resolve({ data: results[name], error: null }));
          if (pending.length === 2) pending.forEach(done => done());
        });
      }
      throw new Error(`Unexpected RPC ${name}`);
    },
  };
  const api = makeApi({ db, now: () => CLOCK });
  const get = (path: string, authenticated = true) => api.route(new Request(`https://project.test/functions/v1/sadad-api/admin/${path}`, { headers: authenticated ? { Authorization: 'Bearer test-token' } : {} }));
  return { calls, summaryStarts, stores, get };
}

Deno.test('Dashboard overlaps signed-only summary and installation reads', async () => {
  const f = fixture({ concurrent: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      f.get('dashboard').then(response => response.json()),
      new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error('Summary reads are serialized')), 1000); }),
    ]);
    assert(f.summaryStarts.length, 2);
    assert(result.stores.map((row: Row) => [row.id, row.contacts, row.initialized, row.boundDevices, row.pendingDevices]), [[1, 0, false, 0, 0], [2, 1, true, 1, 1]]);
    assert(result.overview.debts, 10);
  } finally { clearTimeout(timer); }
});

Deno.test('Control-only reads skip financial data; dedicated and default Ledger reads stay audited', async () => {
  for (const id of [1, 2]) {
    const f = fixture();
    const controls = await (await f.get(`stores/${id}?includeLedger=false`)).json();
    assert('snapshot' in controls, false); assert('auditEvents' in controls, false);
    assert(f.calls.includes('sadid_support_snapshot'), false); assert(f.calls.includes('contacts'), false); assert(f.calls.includes('audit_events'), false);
    const ledger = await (await f.get(`stores/${id}/ledger`)).json();
    assert(ledger.snapshot.totals.receivable, id === 2 ? 10 : 0);
    assert(ledger.snapshot.contacts.length, id === 2 ? 1 : 0);
    if (id === 2) { assert(ledger.snapshot.requestId, 'audited-read'); assert(f.calls.filter(name => name === 'sadid_support_snapshot').length, 1); }
    const full = await (await f.get(`stores/${id}`)).json();
    assert(full.snapshot.totals.receivable, id === 2 ? 10 : 0); assert(full.store.canDelete, id === 1);
  }
});

Deno.test('Control-only and Ledger routes still require a valid admin session', async () => {
  for (const session of [false, true]) {
    for (const path of ['stores/2?includeLedger=false', 'stores/2/ledger']) {
      const f = fixture({ session });
      let denied = false;
      try { await f.get(path, !session); } catch (error) { denied = (error as { status?: number }).status === 401; }
      assert(denied, true); assert(f.calls.includes('sadid_admin_controls'), false); assert(f.calls.includes('sadid_support_snapshot'), false);
    }
  }
});

Deno.test('Failed summary and audited Ledger reads are propagated', async () => {
  for (const [rpc, route] of [['sadid_admin_summaries', 'dashboard'], ['sadid_support_snapshot', 'stores/2/ledger']]) {
    const f = fixture({ failingRpc: rpc });
    let rejected = false;
    try { await f.get(route); } catch (error) { rejected = (error as Error).message === 'query failed'; }
    assert(rejected, true);
  }
});
