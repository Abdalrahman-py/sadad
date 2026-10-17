import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { PGlite } from '../../.local-tools/node_modules/@electric-sql/pglite/dist/index.js';

const db = new PGlite();
const baselineDir = new URL('../supabase/baseline/', import.meta.url);
const schemasDir = new URL('../supabase/schemas/', import.meta.url);
const tests = [];
const storeUser = randomUUID();
const otherStoreUser = randomUUID();
const adminUser = randomUUID();
const supportUser = randomUUID();
const session = randomUUID();
const otherSession = randomUUID();
const installation = randomUUID();
let activeSession = session;
let activeInstallation = installation;
let ledgerEpoch = '';

const command = (type, payload, extra = {}) => ({
  schemaVersion: 1,
  operationId: randomUUID(),
  entityId: randomUUID(),
  type,
  originInstallationId: activeInstallation,
  originGeneration: 1,
  expectedEpoch: ledgerEpoch,
  createdAt: '2026-10-10T10:00:00Z',
  dependsOn: [],
  payload,
  ...extra,
});

async function check(name, fn) {
  await fn();
  tests.push({ name, status: 'passed' });
}

async function setClaims(claims, headers = {}) {
  await db.exec('reset role; set role authenticated');
  await db.query(
    "select set_config('request.jwt.claims', $1, false), set_config('request.headers', $2, false)",
    [JSON.stringify(claims), JSON.stringify(headers)],
  );
}

async function asStore(user = storeUser, sess = activeSession, capabilityToken = '') {
  await setClaims(
    {
      sub: user,
      session_id: sess,
      email: 'test@stores.sadad.invalid',
      app_metadata: { role: 'store', force_password_change: false },
    },
    capabilityToken ? { 'x-sadad-capability': capabilityToken } : {},
  );
}

async function asAdmin(role = 'admin', user = adminUser, sess = randomUUID()) {
  await db.exec('reset role');
  await db.query('insert into auth.sessions(id, user_id, not_after) values ($1, $2, null) on conflict (id) do nothing', [sess, user]);
  await setClaims({
    sub: user,
    session_id: sess,
    email: `${role}@sadad.invalid`,
    app_metadata: { role, force_password_change: false },
  });
}

async function capability(scope, request = null, permit = null, user = storeUser, sess = activeSession, inst = activeInstallation) {
  await db.exec('reset role; set role service_role');
  const r = permit
    ? await db.query(
        'select public.sadid_issue_recovery_capability($1,$2,$3,$4,$5,$6) as result',
        [user, sess, inst, randomUUID(), request, permit],
      )
    : await db.query(
        'select public.sadid_issue_capability($1,$2,$3,$4,$5,$6) as result',
        [user, sess, inst, randomUUID(), scope, request],
      );
  const token = r.rows[0].result.token;
  await asStore(user, sess, token);
}

async function apply(cmd, permit = null) {
  await capability('write', cmd, permit);
  return (await db.query('select public.sadid_apply($1) as result', [cmd])).rows[0].result;
}

async function applyBatch(cmds, permit = null) {
  await capability('write', cmds, permit);
  return (await db.query('select public.sadid_apply_batch($1) as result', [JSON.stringify(cmds)])).rows[0].result;
}

try {
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin bypassrls;
    create function public.rls_auto_enable() returns event_trigger language plpgsql as $$ begin end $$;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_app_meta_data jsonb default '{}'::jsonb);
    create table auth.sessions(id uuid primary key, user_id uuid, not_after timestamptz);
    alter table auth.sessions enable row level security;
    create function auth.uid() returns uuid language sql stable as $$
      select (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')::uuid
    $$;
    create function auth.jwt() returns jsonb language sql stable as $$
      select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb)
    $$;
    grant usage on schema auth to anon, authenticated, service_role;
    grant execute on function auth.uid(), auth.jwt() to anon, authenticated, service_role;
    grant usage on schema public to authenticated, service_role;
  `);

  // Verify baseline migration matches concatenated schemas byte-for-byte
  const schemaFiles = readdirSync(schemasDir).filter(f => f.endsWith('.sql')).sort();
  assert.ok(schemaFiles.length > 0, 'schemas directory must contain SQL files');
  const expectedBaseline = schemaFiles.map(f => readFileSync(new URL(f, schemasDir), 'utf8')).join('');
  assert.ok(existsSync(baselineDir), 'baseline directory must exist');
  const baselineFiles = readdirSync(baselineDir).filter(f => f.endsWith('_baseline.sql')).sort();
  assert.equal(baselineFiles.length, 1, 'exactly one baseline migration must exist');
  const actualBaseline = readFileSync(new URL(baselineFiles[0], baselineDir), 'utf8');
  assert.equal(actualBaseline, expectedBaseline, 'baseline migration must match concatenated schemas/');

  await db.exec(actualBaseline);

  await check('Omitted legacy objects do not exist in the baseline', async () => {
    const omittedTables = [
      ['public', 'sessions'],
      ['public', 'admins'],
      ['public', 'login_rate_limits'],
      ['public', 'sadad_auth_bindings'],
      ['public', 'store_devices'],
      ['sadid_private', 'installation_sessions'],
      ['public', 'contacts'],
      ['public', 'debts'],
      ['public', 'payments'],
      ['public', 'store_backups'],
      ['public', 'contact_archives'],
      ['public', 'sync_receipts'],
      ['public', 'audit_events'],
    ];
    for (const [schema, table] of omittedTables) {
      const r = await db.query(
        'select to_regclass($1) as reg',
        [`${schema}.${table}`],
      );
      assert.equal(r.rows[0].reg, null, `${schema}.${table} must not exist`);
    }
    const storeCols = (
      await db.query(
        "select column_name from information_schema.columns where table_schema='public' and table_name='stores'",
      )
    ).rows.map(r => r.column_name);
    for (const col of ['salt', 'password_hash', 'force_password_change']) {
      assert.ok(!storeCols.includes(col), `stores.${col} must not exist`);
    }
    const omittedFunctions = [
      'public.sadad_create_store_session',
      'public.sadad_record_login_failure',
      'sadid_private.admin_session_username',
      'sadid_private.auth_session_active',
      'sadid_private.store_auth_identity',
      'public.sadid_store_auth_identity',
      'public.sadid_is_v3_store',
      'public.sadid_snapshot',
      'sadid_private.snapshot',
    ];
    for (const fn of omittedFunctions) {
      const [schema, name] = fn.split('.');
      const r = await db.query(
        'select count(*) as n from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname=$1 and p.proname=$2',
        [schema, name],
      );
      assert.equal(Number(r.rows[0].n), 0, `function ${fn} must not exist`);
    }
  });

  await check('Internal roles have no CREATE privilege on sadid_private', async () => {
    for (const role of ['sadid_ledger_executor', 'sadid_auth_reader', 'sadid_support_reader']) {
      assert.equal(
        (await db.query("select has_schema_privilege($1, 'sadid_private', 'CREATE') as allowed", [role])).rows[0].allowed,
        false,
        `${role} must not create in sadid_private`,
      );
    }
  });

  // Seed users and stores
  await db.query('insert into auth.users(id, email) values ($1, $2), ($3, $4), ($5, $6), ($7, $8)', [
    storeUser, 'test@stores.sadad.invalid',
    otherStoreUser, 'other@stores.sadad.invalid',
    adminUser, 'admin@sadad.invalid',
    supportUser, 'support@sadad.invalid',
  ]);
  await db.query('insert into auth.sessions(id, user_id, not_after) values ($1, $2, null), ($3, $4, null)', [
    session, storeUser,
    otherSession, otherStoreUser,
  ]);
  await db.exec(`
    insert into public.stores(id, name, username, allowed_devices, created_at)
    values (1, 'تجربة', 'test', 1, 0), (2, 'متجر آخر', 'other', 1, 0);
    insert into public.sadid_ledger_state(store_id) values (1), (2);
    select setval(pg_get_serial_sequence('public.stores', 'id'), 2);
  `);
  ledgerEpoch = (await db.query('select epoch from public.sadid_ledger_state where store_id=1')).rows[0].epoch;
  await db.query('insert into sadid_private.store_memberships(user_id, store_id) values ($1, 1), ($2, 2)', [
    storeUser,
    otherStoreUser,
  ]);

  const proofKey = '{"kty":"EC","crv":"P-256","x":"' + 'A'.repeat(43) + '","y":"' + 'B'.repeat(43) + '"}';

  await check('Enrollment registers device metadata and refuses replay nonce or exceeding allowed_devices', async () => {
    const nonce1 = randomUUID();
    await asStore(storeUser, session);
    const enrolled = (
      await db.query(
        'select public.sadid_enroll($1, $2, $3, $4, $5) as result',
        [installation, proofKey, nonce1, 'هاتف الكاشير', 'أحمد'],
      )
    ).rows[0].result;
    assert.equal(enrolled.status, 'pending');
    assert.equal(enrolled.generation, 1);

    // Replay nonce refused
    await assert.rejects(
      db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [
        installation, proofKey, nonce1, 'هاتف الكاشير', 'أحمد',
      ]),
      /proof_replayed/,
    );

    // A second device may wait as pending while no device is active yet
    const extra = randomUUID();
    await db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [extra, proofKey, randomUUID(), 'هاتف ثان', 'سامي']);

    // Approve installation 1 as admin
    await asAdmin('admin');
    const approved = (
      await db.query(
        'select public.sadid_approve_installation($1, $2) as result',
        [installation, 'اعتماد جهاز المتجر الأول'],
      )
    ).rows[0].result;
    assert.equal(approved.status, 'active');

    // Approving the pending second device would exceed allowed_devices = 1: refused, the active device stays
    await assert.rejects(
      db.query('select public.sadid_approve_installation($1, $2)', [extra, 'جهاز زائد']),
      /device_limit_reached/,
    );
    await db.exec('reset role');
    const statuses = (
      await db.query('select id, status from sadid_private.installations where store_id = 1')
    ).rows.reduce((all, row) => ({ ...all, [row.id]: row.status }), {});
    assert.deepEqual(statuses, { [installation]: 'active', [extra]: 'pending' });

    // Enrolling a second installation when allowed_devices = 1 and 1 active installation exists -> device_limit_reached
    await asStore(storeUser, session);
    await assert.rejects(
      db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [
        randomUUID(), proofKey, randomUUID(), 'هاتف إضافي', 'سامي',
      ]),
      /device_limit_reached/,
    );
  });

  const pendingInstallation = async () => {
    await db.exec('reset role');
    return (await db.query("select id from sadid_private.installations where store_id = 1 and status = 'pending'")).rows[0].id;
  };
  // Inserts every nonce at now() before ageing any, so the rate guard's pruning cannot remove a row of the setup.
  const agedNonces = async (...rows) => {
    const nonces = rows.map(() => randomUUID());
    for (const [i, [inst]] of rows.entries()) {
      await db.query('insert into sadid_private.request_nonces(installation_id, nonce) values ($1, $2)', [inst, nonces[i]]);
    }
    for (const [i, [inst, minutes]] of rows.entries()) {
      await db.query(
        'update sadid_private.request_nonces set created_at = clock_timestamp() - make_interval(mins => $3) where installation_id = $1 and nonce = $2',
        [inst, nonces[i], minutes],
      );
    }
    return nonces;
  };
  const nonceExists = async (inst, nonce) =>
    (await db.query('select count(*)::int n from sadid_private.request_nonces where installation_id = $1 and nonce = $2', [inst, nonce])).rows[0].n === 1;

  await check('A signed request prunes its own installation nonces older than 15 minutes', async () => {
    const pending = await pendingInstallation();
    const [old, recent, otherOld] = await agedNonces([installation, 20], [installation, 5], [pending, 20]);
    const nonces = async () =>
      (await db.query('select count(*)::int n from sadid_private.request_nonces where installation_id = $1', [installation])).rows[0].n;
    const before = await nonces();

    await capability('read');
    await db.exec('reset role');
    assert.equal(await nonceExists(installation, old), false);
    assert.equal(await nonceExists(installation, recent), true);
    assert.equal(await nonceExists(pending, otherOld), true);
    assert.equal(await nonces(), before, 'one old nonce gone, the nonce of this request stored');
  });

  await check('Enrollment prunes the installation nonces older than 15 minutes', async () => {
    const pending = await pendingInstallation();
    const [old] = await agedNonces([pending, 20]);
    const fresh = randomUUID();
    await asStore(storeUser, session);
    await db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [pending, proofKey, fresh, 'هاتف ثان', 'سامي']);
    await db.exec('reset role');
    assert.equal(await nonceExists(pending, old), false);
    assert.equal(await nonceExists(pending, fresh), true);
  });

  await check('A nonce used 9 minutes ago is still refused as replayed', async () => {
    await db.exec('reset role');
    const [used] = await agedNonces([installation, 9]);
    await db.exec('reset role; set role service_role');
    await assert.rejects(
      db.query('select public.sadid_issue_capability($1,$2,$3,$4,$5,$6)', [storeUser, session, installation, used, 'read', null]),
      /proof_replayed/,
    );
    await db.exec('reset role');
    assert.equal(await nonceExists(installation, used), true);
  });

  await check('A capability request prunes its own installation expired capabilities', async () => {
    const pending = await pendingInstallation();
    const capabilityRow = async (inst, expiresIn) => {
      const hash = randomUUID();
      await db.query(
        `insert into sadid_private.capabilities(token_hash, user_id, auth_session_id, store_id, installation_id, generation, scope, expires_at)
         select pg_catalog.sha256(pg_catalog.convert_to($1, 'UTF8')), $2, $3, i.store_id, i.id, i.generation, 'read', clock_timestamp() + $4::interval
         from sadid_private.installations i where i.id = $5`,
        [hash, storeUser, session, expiresIn, inst],
      );
      return hash;
    };
    const capabilityExists = async hash =>
      (await db.query("select count(*)::int n from sadid_private.capabilities where token_hash = pg_catalog.sha256(pg_catalog.convert_to($1, 'UTF8'))", [hash])).rows[0].n === 1;
    const expired = await capabilityRow(installation, '-1 minute');
    const live = await capabilityRow(installation, '1 minute');
    const otherExpired = await capabilityRow(pending, '-1 minute');
    const before = (await db.query('select count(*)::int n from sadid_private.capabilities where installation_id = $1', [installation])).rows[0].n;

    await capability('read');
    await db.exec('reset role');
    assert.equal(await capabilityExists(expired), false);
    assert.equal(await capabilityExists(live), true);
    assert.equal(await capabilityExists(otherExpired), true);
    const after = (await db.query('select count(*)::int n from sadid_private.capabilities where installation_id = $1', [installation])).rows[0].n;
    assert.equal(after, before, 'one expired row gone, one new row issued');
    assert.equal(
      (await db.query('select count(*)::int n from sadid_private.capabilities where installation_id = $1 and expires_at < clock_timestamp()', [installation])).rows[0].n,
      0,
    );
  });

  const contact = command('contact.create', { name: 'عميل تجربة', phone: '', externalReference: 'TEST-001' });

  await check('Refuses operation with wrong epoch', async () => {
    await assert.rejects(
      apply(command('contact.create', { name: 'قديم' }, { expectedEpoch: randomUUID() })),
      /epoch_changed/,
    );
  });

  await check('Creates contact and idempotently returns same receipt on retry without advancing cursor', async () => {
    const a = await apply(contact);
    assert.equal(a.status, 'accepted');
    const b = await apply(contact);
    assert.deepEqual(a, b);
    assert.equal(b.sequence, 1);
    await db.exec('reset role');
    for (const table of ['sadid_operations', 'sadid_events', 'sadid_contacts']) {
      assert.equal(
        (await db.query(`select count(*)::int n from public.${table} where operation_id = $1`, [contact.operationId])).rows[0].n,
        1,
        `${table} must hold one row for the retried operationId`,
      );
    }
  });

  await check('Refuses reused operationId with different payload', async () => {
    await assert.rejects(
      apply({ ...contact, payload: { name: 'تغيير' } }),
      /operation_id_reused/,
    );
  });

  const debt = command(
    'debt.create',
    { contactId: contact.entityId, amountCents: 50000, direction: 'receivable' },
    { dependsOn: [contact.operationId] },
  );

  await check('Creates 500 ILS debt', async () => {
    assert.equal((await apply(debt)).status, 'accepted');
  });

  await check('Failure after money write rolls back money, receipt, event and cursor', async () => {
    await db.exec(
      "reset role; create function public.test_fail_event() returns trigger language plpgsql as $$ begin raise exception 'injected_failure'; end $$; create trigger test_fail before insert on public.sadid_events for each row execute function public.test_fail_event()",
    );
    const before = (await db.query('select cursor from public.sadid_ledger_state where store_id=1')).rows[0].cursor;
    const c = command('payment.create', { debtId: debt.entityId, amountCents: 1, method: 'cash' });
    await assert.rejects(apply(c), /injected_failure/);
    await db.exec('reset role');
    assert.equal((await db.query('select count(*) n from public.sadid_payments')).rows[0].n, 0);
    assert.equal((await db.query('select count(*) n from public.sadid_operations where operation_id=$1', [c.operationId])).rows[0].n, 0);
    assert.equal((await db.query('select cursor from public.sadid_ledger_state where store_id=1')).rows[0].cursor, before);
    await db.exec('drop trigger test_fail on public.sadid_events; drop function public.test_fail_event()');
  });

  await check('Requires schemaVersion and validates normalized phone uniqueness across archive', async () => {
    const missingSchema = command('contact.create', { name: 'طلب ناقص' });
    delete missingSchema.schemaVersion;
    await assert.rejects(apply(missingSchema), /unsupported_schema/);

    await db.exec('reset role; begin');
    try {
      const a = command('contact.create', { name: 'هاتف تحقق اصطناعي', phone: '+٩٩٩ ١٢٣٤٥٦٧٨٩' });
      assert.equal((await apply(a)).status, 'accepted');
      assert.equal((await apply(command('contact.create', { name: 'مكرر', phone: '+999123456789' }))).code, 'duplicate_phone');
      assert.equal((await apply(command('contact.archive', { contactId: a.entityId, reason: 'تحقق' }, { expectedVersion: 1 }))).status, 'accepted');
      assert.equal((await apply(command('contact.create', { name: 'مكرر مؤرشف', phone: '+999123456789' }))).code, 'duplicate_phone');
    } finally {
      await db.exec('rollback; reset role');
    }
  });

  await check('Supports all five payment methods and contact.update version guard', async () => {
    await db.exec('reset role; begin');
    try {
      for (const method of ['wallet_jawwal', 'wallet_palpay']) {
        assert.equal(
          (await apply(command('payment.create', { debtId: debt.entityId, amountCents: 100, method }))).status,
          'accepted',
        );
      }
      const edit = command(
        'contact.update',
        { contactId: contact.entityId, name: 'اسم جديد', phone: '+٩٧٠٥٩٩١٢٣٤٥٦', category: 'عميل', note: 'ملاحظة جديدة' },
        { expectedVersion: 1 },
      );
      assert.equal((await apply(edit)).status, 'accepted');
      assert.equal((await apply(command('contact.update', edit.payload, { expectedVersion: 1 }))).code, 'entity_version_changed');
    } finally {
      await db.exec('rollback; reset role');
    }
  });

  const payment = command(
    'payment.create',
    { debtId: debt.entityId, amountCents: 10000, method: 'cash' },
    { dependsOn: [debt.operationId] },
  );

  await check('Payment, overpayment refusal, fractional money refusal, and debt correction/reversal rules', async () => {
    assert.equal((await apply(payment)).status, 'accepted');

    const over = command('payment.create', { debtId: debt.entityId, amountCents: 40001, method: 'cash' });
    const overReceipt = await apply(over);
    assert.equal(overReceipt.code, 'overpayment');
    assert.deepEqual(await apply(over), overReceipt);

    assert.equal(
      (await apply(command('payment.create', { debtId: debt.entityId, amountCents: 0.1, method: 'cash' }))).code,
      'invalid_money',
    );
    await assert.rejects(
      apply(command('contact.create', { name: 'اعتماد' }, { dependsOn: [randomUUID()] })),
      /dependency_pending/,
    );
    assert.equal(
      (await apply(command('debt.correct', { debtId: debt.entityId, amountCents: 45000, reason: 'تصحيح' }, { expectedVersion: 0 }))).code,
      'entity_version_changed',
    );
    assert.equal(
      (await apply(command('debt.correct', { debtId: debt.entityId, amountCents: 9999, reason: 'تصحيح' }, { expectedVersion: 1 }))).code,
      'below_paid_total',
    );
    assert.equal(
      (await apply(command('debt.correct', { debtId: debt.entityId, amountCents: 45000, reason: 'تصحيح المبلغ' }, { expectedVersion: 1 }))).status,
      'accepted',
    );

    const reverse = command('payment.reverse', { paymentId: payment.entityId, reason: 'إلغاء موثق' });
    assert.equal((await apply(reverse)).status, 'accepted');
    assert.equal((await apply(command('payment.reverse', reverse.payload))).code, 'already_reversed');

    assert.equal(
      (await apply(command('contact.archive', { contactId: contact.entityId, reason: 'أرشفة' }, { expectedVersion: 1 }))).status,
      'accepted',
    );
    assert.equal(
      (await apply(command('debt.create', { contactId: contact.entityId, amountCents: 1, direction: 'receivable' }))).code,
      'contact_unavailable',
    );
    assert.equal(
      (await apply(command('contact.restore', { contactId: contact.entityId, reason: 'استعادة' }, { expectedVersion: 2 }))).status,
      'accepted',
    );
  });

  await check('Batch apply returns one receipt per operation and is idempotent on resend', async () => {
    const c1 = command('contact.create', { name: 'دفعة ١', phone: '' });
    const c2 = command('debt.create', { contactId: c1.entityId, amountCents: 2500, direction: 'receivable' }, { dependsOn: [c1.operationId] });
    const c3 = command('payment.create', { debtId: c2.entityId, amountCents: 3000, method: 'cash' }, { dependsOn: [c2.operationId] });

    const receipts1 = await applyBatch([c1, c2, c3]);
    assert.equal(receipts1.length, 3);
    assert.equal(receipts1[0].status, 'accepted');
    assert.equal(receipts1[1].status, 'accepted');
    assert.equal(receipts1[2].status, 'rejected');
    assert.equal(receipts1[2].code, 'overpayment');

    const receipts2 = await applyBatch([c1, c2, c3]);
    assert.deepEqual(receipts2, receipts1);
  });

  await check('changes(after, limit) returns events after cursor in order and read routines take no row locks on stores', async () => {
    await db.exec('reset role; begin');
    try {
      await capability('read');
      const page1 = (await db.query('select public.sadid_changes($1, $2) as result', [0, 3])).rows[0].result;
      assert.equal(page1.events.length, 3);
      assert.deepEqual(page1.events.map(e => e.sequence), [1, 2, 3]);
      assert.equal(page1.nextCursor, 3);
      assert.equal(page1.hasMore, true);

      const page2 = (await db.query('select public.sadid_changes($1, $2) as result', [3, 50])).rows[0].result;
      assert.ok(page2.events.length > 0);
      assert.ok(page2.events.every((e, i) => i === 0 || page2.events[i - 1].sequence < e.sequence));
      assert.ok(page2.events[0].sequence > 3);
      assert.equal(page2.hasMore, false);

      await db.query('select public.sadid_activity()');

      await db.exec('reset role');
      const locks = (
        await db.query(
          "select mode from pg_locks where relation = 'public.stores'::regclass and mode in ('RowExclusiveLock', 'RowShareLock', 'ShareUpdateExclusiveLock', 'ExclusiveLock')",
        )
      ).rows;
      assert.equal(locks.length, 0, 'read routines must not take row locks on public.stores');
    } finally {
      await db.exec('rollback; reset role');
    }
  });

  await check('Role separation: admin reaches admin routines, support reaches only read routines, store user reaches neither', async () => {
    // Store user denied on both read and write admin routines
    await asStore(storeUser, session);
    await assert.rejects(db.query('select public.sadid_admin_summaries($1)', [[1]]), /admin_authorization_required/);
    await assert.rejects(db.query('select public.sadid_admin_controls(1)'), /admin_authorization_required/);
    await assert.rejects(db.query("select public.sadid_support_snapshot(1, 'test')"), /admin_authorization_required/);
    await assert.rejects(db.query("select public.sadid_admin_account_action(1, 'end-sessions', 'test')"), /admin_authorization_required/);
    await assert.rejects(db.query('select public.sadid_pending_installations()'), /admin_authorization_required/);

    // An admin JWT whose Auth session was ended is refused, like a store's
    const endedSession = randomUUID();
    await asAdmin('admin', adminUser, endedSession);
    await db.exec('reset role');
    await db.query('delete from auth.sessions where id = $1', [endedSession]);
    await setClaims({ sub: adminUser, session_id: endedSession, email: 'admin@sadad.invalid', app_metadata: { role: 'admin' } });
    await assert.rejects(db.query('select public.sadid_admin_summaries($1)', [[1]]), /admin_authorization_required/);
    await assert.rejects(db.query("select public.sadid_support_snapshot(1, 'جلسة منتهية')"), /admin_authorization_required/);

    // Support user reaches read routines, refused on write routines
    await asAdmin('support', supportUser);
    const summaries = (await db.query('select public.sadid_admin_summaries($1) as result', [[1, 2]])).rows[0].result;
    assert.equal(summaries.length, 2);
    const controls = (await db.query('select public.sadid_admin_controls(1) as result')).rows[0].result;
    assert.equal(controls.hasDeviceHistory, true);
    const snap = (await db.query("select public.sadid_support_snapshot(1, 'مراجعة دعم') as result")).rows[0].result;
    assert.equal(snap.storeId, 1);

    await assert.rejects(
      db.query("select public.sadid_admin_account_action(1, 'end-sessions', 'محاولة دعم')"),
      /admin_authorization_required/,
    );
    await assert.rejects(
      db.query("select public.sadid_revoke_installation($1, 'محاولة دعم')", [installation]),
      /admin_authorization_required/,
    );
    await assert.rejects(
      db.query("select public.sadid_approve_installation($1, 'محاولة دعم')", [installation]),
      /admin_authorization_required/,
    );
    await assert.rejects(
      db.query("select public.sadid_approve_recovery($1, $2, '[]'::jsonb, 'محاولة دعم')", [installation, installation]),
      /admin_authorization_required/,
    );
    const pending = (await db.query('select public.sadid_pending_installations() as result')).rows[0].result.installations;
    assert.ok(pending.length > 0 && pending.every(i => i.status === 'pending'), 'pending list holds only pending devices');

    // Admin user reaches both read and write admin routines
    await asAdmin('admin', adminUser);
    const act = (await db.query("select public.sadid_admin_account_action(1, 'end-sessions', 'اختبار إدارة') as result")).rows[0].result;
    assert.equal(act.action, 'end-sessions');
  });

  await check('Store isolation and suspended store or expired subscription refusal in current_capability', async () => {
    // Re-create session after end-sessions
    await db.exec('reset role');
    await db.query('insert into auth.sessions(id, user_id, not_after) values ($1, $2, null) on conflict (id) do nothing', [
      session, storeUser,
    ]);

    // The api reads the caller's store from the membership under the caller's JWT
    const myStore = async () => (await db.query('select public.sadid_my_store()::int as id')).rows[0].id;
    await asStore(storeUser, session);
    assert.equal(await myStore(), 1);
    await asStore(otherStoreUser, otherSession);
    assert.equal(await myStore(), 2);
    await asAdmin('admin', adminUser);
    assert.equal(await myStore(), null);

    // Store user 2 cannot see Store 1 rows or events
    const otherInstallation = randomUUID();
    await asStore(otherStoreUser, otherSession);
    await db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [
      otherInstallation, proofKey, randomUUID(), 'جهاز متجر ٢', 'خالد',
    ]);
    await asAdmin('admin', adminUser);
    await db.query('select public.sadid_approve_installation($1, $2)', [otherInstallation, 'تفعيل متجر ٢']);

    await capability('read', null, null, otherStoreUser, otherSession, otherInstallation);
    const otherChanges = (await db.query('select public.sadid_changes(0, 50) as result')).rows[0].result;
    assert.equal(otherChanges.events.length, 0);
    assert.equal((await db.query('select count(*) n from public.sadid_contacts')).rows[0].n, 0);

    // Suspended store refused in current_capability even after capability was issued
    await db.exec('reset role; begin');
    try {
      await capability('read');
      await db.exec("reset role; update public.stores set status='suspended' where id=1; set role authenticated");
      await assert.rejects(db.query('select public.sadid_changes(0, 10)'), /authorization_required/);
    } finally {
      await db.exec('rollback; reset role');
    }

    // Paused subscription refused in current_capability
    await db.exec('reset role; begin');
    try {
      await capability('read');
      await db.exec("reset role; update public.stores set subscription_mode='paused' where id=1; set role authenticated");
      await assert.rejects(db.query('select public.sadid_changes(0, 10)'), /authorization_required/);
    } finally {
      await db.exec('rollback; reset role');
    }
  });

  await check('Recovery permit flow works across installation transfer', async () => {
    await db.exec('reset role');
    const recovered = command('contact.create', { name: 'مدخل مستعاد' });
    const newInstallation = randomUUID();

    // Revoke the lost installation as admin, then enroll and approve the replacement installation
    await asAdmin('admin', adminUser);
    await db.query('select public.sadid_revoke_installation($1, $2)', [installation, 'هاتف مفقود']);

    await asStore(storeUser, session);
    await db.query('select public.sadid_enroll($1, $2, $3, $4, $5)', [
      newInstallation, proofKey, randomUUID(), 'جهاز بديل', 'أحمد',
    ]);
    await asAdmin('admin', adminUser);
    await db.query('select public.sadid_approve_installation($1, $2)', [newInstallation, 'نقل الجهاز']);

    // Old installation is now revoked
    await assert.rejects(
      capability('write', recovered, null, storeUser, session, installation),
      /installation_or_session_revoked/,
    );

    activeInstallation = newInstallation;
    // Applying command signed by old installation without permit fails
    await assert.rejects(apply(recovered), /installation_revoked/);

    // Approve recovery as admin
    await asAdmin('admin', adminUser);
    const permit = (
      await db.query(
        'select public.sadid_approve_recovery($1, $2, $3, $4) as result',
        [installation, newInstallation, JSON.stringify([recovered, contact]), 'استرداد معتمد للاختبار'],
      )
    ).rows[0].result.permitId;

    const first = await apply(recovered, permit);
    assert.equal(first.status, 'accepted');
    assert.deepEqual(await apply(recovered, permit), first);
    await assert.rejects(
      apply({ ...recovered, payload: { name: 'محتوى غير معتمد' } }, permit),
      /recovery_not_authorized/,
    );
  });

  await check('service_role links a new store to its Auth user; only a store without history can be deleted', async () => {
    const owner = randomUUID();
    await db.exec('reset role');
    await db.query('insert into auth.users(id, email) values ($1, $2)', [owner, 'new@stores.sadad.invalid']);

    await asStore(storeUser, session);
    await assert.rejects(db.query("select public.sadid_create_store($1, 'متجر', 'new')", [owner]), /permission denied/);

    await db.exec('reset role; set role service_role');
    const id = (await db.query("select public.sadid_create_store($1, 'متجر جديد', 'new')::int as id", [owner])).rows[0].id;
    await assert.rejects(db.query("select public.sadid_create_store($1, 'نسخة', 'NEW')", [owner]), /username_taken/);

    await db.exec('reset role');
    assert.equal((await db.query('select store_id::int from sadid_private.store_memberships where user_id = $1', [owner])).rows[0].store_id, id);

    await asAdmin('support', supportUser);
    assert.equal((await db.query('select public.sadid_admin_controls($1) as result', [id])).rows[0].result.hasDeviceHistory, false);

    await db.exec('reset role; set role service_role');
    await assert.rejects(db.query('delete from public.stores where id = 1'), /violates .*foreign key constraint/);
    await db.query('delete from public.stores where id = $1', [id]);
    await db.exec('reset role');
    assert.equal((await db.query('select count(*)::int n from sadid_private.store_memberships where user_id = $1', [owner])).rows[0].n, 0);
  });

  console.log(JSON.stringify({ engine: (await db.query('select version()')).rows[0].version, tests }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ message: error.message, code: error.code, where: error.where, tests }, null, 2));
  process.exitCode = 1;
} finally {
  await db.close();
}
