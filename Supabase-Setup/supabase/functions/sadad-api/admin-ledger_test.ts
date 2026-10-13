import { adminLedgerSnapshot } from './admin-ledger.ts';
const assert = (value: unknown, expected: unknown) => { if (JSON.stringify(value)!==JSON.stringify(expected)) throw new Error(`${JSON.stringify(value)} != ${JSON.stringify(expected)}`); };
Deno.test('Latest correction, reversed payment, archived contacts, and report reconcile',()=>{
 const raw={contacts:[{id:'c',name:'Customer',archived:true}],debts:[{id:'d',contact_id:'c',direction:'receivable',amount_cents:10000,created_at:'2026-10-10T00:00:00Z'},{id:'d2',contact_id:'c',direction:'payable',amount_cents:4000,created_at:'2026-10-10T00:00:00Z'}],
  payments:[{id:'p',debt_id:'d',amount_cents:3000,created_at:'2026-10-10T01:00:00Z'},{id:'p2',debt_id:'d2',amount_cents:1000,created_at:'2026-10-10T01:00:00Z'}],
  adjustments:[{id:'a2',debt_id:'d',amount_cents:11000,previous_amount_cents:12000,version:3,created_at:'2026-10-10T03:00:00Z'},{id:'a1',debt_id:'d',amount_cents:12000,previous_amount_cents:10000,version:2,created_at:'2026-10-10T02:00:00Z'}],
  reversals:[{id:'r',payment_id:'p',reason:'Returned',created_at:'2026-10-10T04:00:00Z'}],requestId:'audit-id'};
 const s=adminLedgerSnapshot(raw,{id:1});
 assert(s.totals,{receivable:110,payable:30,net:80});
 assert(s.contacts[0].archived,true);assert(s.debts[0].paid,0);assert(s.debts[0].originalAmount,100);assert(s.debts[0].amount,110);
 assert(s.payments[0].reversed,true);assert(s.transactions[0].kind,'reversal');assert(s.transactions[0].amount,-30);
 assert(s.transactions.filter((x:Record<string,unknown>)=>x.kind==='debt').map((x:Record<string,unknown>)=>x.amount),[100,40]);assert(s.requestId,'audit-id');
});
Deno.test('Empty initialized ledger remains valid',()=>assert(adminLedgerSnapshot({},{id:1}).totals,{receivable:0,payable:0,net:0}));

Deno.test('Large ledger groups balances without scanning every debt for every contact',()=>{
 let idReads=0;
 const contacts=Array.from({length:1000},(_,index)=>({get id(){idReads++;return `c${index}`;},name:`Customer ${index}`}));
 const debts=Array.from({length:1000},(_,index)=>({id:`d${index}`,contact_id:`c${index}`,direction:index%2?'payable':'receivable',amount_cents:10000,created_at:'2026-10-10T00:00:00Z'}));
 const result=adminLedgerSnapshot({contacts,debts},{id:1});
 assert(result.contacts[0].receivable,100);assert(result.contacts[1].payable,100);
 assert(result.totals,{receivable:50000,payable:50000,net:0});
 if(idReads>5000)throw new Error(`Contact identifiers read ${idReads} times; balance grouping is quadratic`);
});

Deno.test('Contact balances use bounded passes and integer cents', () => {
 let reads = 0;
 const contacts = Array.from({length:500}, (_, i) => ({get id() { reads++; return String(i); }, name:`Contact ${i}`}));
 const debts = Array.from({length:1500}, (_, i) => ({id:String(i),contact_id:String(i%500),direction:i<1000?'receivable':'payable',amount_cents:i<500?10:i<1000?20:7,created_at:0}));
 const snapshot = adminLedgerSnapshot({contacts,debts},{id:1});
 assert(snapshot.contacts[0].receivable,0.3);assert(snapshot.contacts[0].payable,0.07);
 assert(snapshot.totals,{receivable:150,payable:35,net:115});
 if (reads > contacts.length * 4) throw new Error(`Contact ids read ${reads} times: projection rescans debts per contact`);
});
