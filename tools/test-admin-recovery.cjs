const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Exercise the dashboard's actual validator without starting its DOM or making network calls.
const source = fs.readFileSync('server/public/admin.js', 'utf8');
const start = source.indexOf('  const recoveryTypes =');
const end = source.indexOf('  function renderRecoverySummary(', start);
assert.ok(start >= 0 && end > start, 'Recovery validator is missing');
const context = { fmt: String, englishDigits: value => String(value ?? '') };
vm.createContext(context);
vm.runInContext(source.slice(start, end) + '\nthis.inspect = inspectRecoveryManifest;', context);
const phone = { installationId: 'a0000000-0000-4000-8000-000000000001', generation: 1 };
const command = { schemaVersion: 1, operationId: 'b0000000-0000-4000-8000-000000000001',
  entityId: 'c0000000-0000-4000-8000-000000000001', type: 'contact.create', originInstallationId: phone.installationId,
  originGeneration: 1, expectedEpoch: 'd0000000-0000-4000-8000-000000000001', createdAt: '2026-10-13T12:00:00.000+03:00',
  dependsOn: [], payload: { name: 'عميل', phone: '' } };
const blocked = commands => context.inspect(JSON.stringify(commands), phone).problems.filter(problem => problem.level === 'block');
assert.equal(blocked([command]).length, 0, 'Dashboard refused the numeric schemaVersion produced by Android');
assert.ok(blocked([{ ...command, schemaVersion: '1' }]).length);
assert.ok(blocked([{ ...command, originInstallationId: command.entityId }]).length);
assert.ok(blocked([command, command]).length);
assert.ok(blocked([{ ...command, type: 'payment.create', payload: { amountCents: 0, method: 'cash' } }]).length);
for (const method of ['wallet_jawwal', 'wallet_palpay']) assert.equal(blocked([{ ...command, type: 'payment.create', payload: { debtId: command.entityId, amountCents: 100, method } }]).length, 0);
assert.ok(blocked([{ ...command, type: 'debt.create', payload: { amountCents: 1.5, direction: 'receivable' } }]).length);
console.log('Dashboard recovery: Android command accepted; unsupported schema, wrong origin, duplicates and invalid money refused');

// Rendering a large ledger must reuse locale formatters rather than rebuild them per cell.
let formatterConstructions = 0;
const rendering = { Intl: { NumberFormat: class {
  constructor(...args) { formatterConstructions++; this.formatter = new Intl.NumberFormat(...args); }
  format(value) { return this.formatter.format(value); }
} } };
vm.createContext(rendering);
vm.runInContext(source.slice(source.indexOf('  const numberFormat ='), source.indexOf('  const englishDigits =')) + '\nthis.fmt = fmt;', rendering);
for (let index = 0; index < 1000; index++) assert.equal(rendering.fmt(index), new Intl.NumberFormat('ar-PS', { maximumFractionDigits: 2 }).format(index));
assert.equal(formatterConstructions, 1, 'Ledger values must share one number formatter');
console.log('Dashboard rendering: 1000 values reuse one locale formatter');
