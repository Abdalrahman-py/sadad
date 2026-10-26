-- Signed ledger tables.
create table public.sadid_ledger_state (
  store_id bigint primary key references public.stores(id) on delete restrict,
  epoch uuid not null default gen_random_uuid(),
  cursor bigint not null default 0 check (cursor >= 0),
  currency text not null default 'ILS' check (currency = 'ILS'),
  schema_version integer not null default 1
);

create table public.sadid_contacts (
  store_id bigint not null references public.sadid_ledger_state(store_id) on delete restrict,
  id uuid not null,
  name text not null check (length(trim(name)) between 1 and 120),
  phone text not null default '',
  kind text not null default 'customer' check (kind in ('customer', 'supplier')),
  category text not null default '',
  note text not null default '',
  external_reference text not null default '',
  archived boolean not null default false,
  version bigint not null default 1,
  operation_id uuid not null,
  created_at timestamptz not null,
  primary key (store_id, id)
);
create unique index sadid_unique_phone on public.sadid_contacts(store_id, phone) where phone <> '';

create table public.sadid_debts (
  store_id bigint not null,
  id uuid not null,
  contact_id uuid not null,
  direction text not null check (direction in ('receivable', 'payable')),
  amount_cents bigint not null check (amount_cents between 1 and 9007199254740991),
  note text not null default '',
  due_date date,
  operation_id uuid not null,
  created_at timestamptz not null,
  primary key (store_id, id),
  foreign key (store_id, contact_id) references public.sadid_contacts(store_id, id)
);
create index sadid_debts_contact on public.sadid_debts(store_id, contact_id);

create table public.sadid_payments (
  store_id bigint not null,
  id uuid not null,
  debt_id uuid not null,
  amount_cents bigint not null check (amount_cents between 1 and 9007199254740991),
  method text not null check (method in ('cash', 'bank', 'wallet', 'wallet_jawwal', 'wallet_palpay')),
  note text not null default '',
  operation_id uuid not null,
  created_at timestamptz not null,
  primary key (store_id, id),
  foreign key (store_id, debt_id) references public.sadid_debts(store_id, id)
);
create index sadid_payments_debt on public.sadid_payments(store_id, debt_id);

create table public.sadid_adjustments (
  store_id bigint not null,
  id uuid not null,
  debt_id uuid not null,
  amount_cents bigint not null check (amount_cents between 1 and 9007199254740991),
  previous_amount_cents bigint not null,
  version bigint not null,
  reason text not null check (length(trim(reason)) between 1 and 2000),
  operation_id uuid not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key (store_id, id),
  unique (store_id, debt_id, version),
  foreign key (store_id, debt_id) references public.sadid_debts(store_id, id)
);
create index sadid_adjustments_debt on public.sadid_adjustments(store_id, debt_id, version desc);

create table public.sadid_reversals (
  store_id bigint not null,
  id uuid not null,
  payment_id uuid not null,
  reason text not null check (length(trim(reason)) between 1 and 2000),
  operation_id uuid not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key (store_id, id),
  unique (store_id, payment_id),
  foreign key (store_id, payment_id) references public.sadid_payments(store_id, id)
);

create table public.sadid_operations (
  store_id bigint not null references public.sadid_ledger_state(store_id),
  operation_id uuid not null,
  request jsonb not null,
  result jsonb not null,
  user_id uuid not null,
  installation_id uuid not null,
  generation integer not null,
  received_at timestamptz not null default clock_timestamp(),
  primary key (store_id, operation_id)
);

create table public.sadid_events (
  store_id bigint not null references public.sadid_ledger_state(store_id),
  sequence bigint not null,
  operation_id uuid not null,
  kind text not null,
  entity_id uuid not null,
  user_id uuid not null,
  installation_id uuid not null,
  generation integer not null,
  command jsonb not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key (store_id, sequence),
  foreign key (store_id, operation_id) references public.sadid_operations(store_id, operation_id) deferrable initially deferred
);
create index sadid_events_operation on public.sadid_events(store_id, operation_id);
