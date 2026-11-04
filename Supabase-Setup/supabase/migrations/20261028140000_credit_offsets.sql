-- Ticket 88: overpayment becomes customer credit. A Debt gains origin ('manual' default,
-- 'overpayment' for credit), the new append-only sadid_offsets table settles a receivable
-- against a payable of the same Contact, and a correction may zero an unused Debt.
-- Function bodies match schemas/ exactly; debt_balances changes its return type, so it is
-- dropped and recreated (its only caller, admin_ledger, is plpgsql and binds at run time).
alter table public.sadid_debts
  add column origin text not null default 'manual' check (origin in ('manual', 'overpayment'));

alter table public.sadid_adjustments
  drop constraint sadid_adjustments_amount_cents_check,
  add constraint sadid_adjustments_amount_cents_check check (amount_cents between 0 and 9007199254740991);

create table public.sadid_offsets (
  store_id bigint not null,
  id uuid not null,
  receivable_debt_id uuid not null,
  payable_debt_id uuid not null,
  amount_cents bigint not null check (amount_cents between 1 and 9007199254740991),
  operation_id uuid not null,
  created_at timestamptz not null,
  primary key (store_id, id),
  foreign key (store_id, receivable_debt_id) references public.sadid_debts(store_id, id),
  foreign key (store_id, payable_debt_id) references public.sadid_debts(store_id, id)
);
create index sadid_offsets_receivable on public.sadid_offsets(store_id, receivable_debt_id);
create index sadid_offsets_payable on public.sadid_offsets(store_id, payable_debt_id);


create trigger immutable before update or delete on public.sadid_offsets for each row execute function sadid_private.immutable_history();

alter table public.sadid_offsets enable row level security;
alter table public.sadid_offsets force row level security;
revoke all on public.sadid_offsets from public, anon, authenticated, service_role;
grant select on public.sadid_offsets to authenticated, sadid_ledger_executor, sadid_support_reader;
grant insert on public.sadid_offsets to sadid_ledger_executor;
create policy store_read on public.sadid_offsets for select to authenticated, sadid_ledger_executor using (store_id = (select sadid_private.authorized_store()));
create policy store_insert on public.sadid_offsets for insert to sadid_ledger_executor with check (store_id = (select sadid_private.authorized_store()));
create policy audited_support_read on public.sadid_offsets for select to sadid_support_reader using (store_id = (select sadid_private.support_store()));

-- The ledger operation contract: validates one command, writes it, and records its receipt and event.
create or replace function sadid_private.apply_one(p_command jsonb, c sadid_private.capabilities) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  st public.sadid_ledger_state;
  oldop public.sadid_operations;
  op uuid;
  entity uuid;
  kind text;
  payload jsonb;
  result jsonb;
  code text;
  dependency text;
  contact uuid;
  debt uuid;
  payment uuid;
  amount bigint;
  paid bigint;
  offset_sum bigint;
  principal bigint;
  ver bigint;
  v_phone text;
  occurred timestamptz;
  -- debt.offset's two Debts and what remains on each after payments and earlier offsets.
  v_receivable uuid;
  v_payable uuid;
  r_debt public.sadid_debts;
  p_debt public.sadid_debts;
  r_left bigint;
  p_left bigint;
  -- The archive verdict, captured before the UPDATE: the variable name kind would be ambiguous
  -- against sadid_contacts' new kind column inside the statement.
  toggled boolean;
  -- Whether the command corrects a Debt, for the same reason.
  correcting boolean;
  reason text;
  seq bigint;
begin
  if jsonb_typeof(p_command) <> 'object' or p_command ->> 'schemaVersion' is distinct from '1' or octet_length(p_command::text) > 65536 then
    raise exception 'unsupported_schema';
  end if;
  begin
    op := (p_command ->> 'operationId')::uuid;
    entity := (p_command ->> 'entityId')::uuid;
  exception when invalid_text_representation then
    raise exception 'invalid_input';
  end;
  kind := p_command ->> 'type';
  payload := p_command -> 'payload';
  if op is null or entity is null or kind is null or jsonb_typeof(payload) is distinct from 'object' then
    raise exception 'invalid_input';
  end if;

  if not coalesce(sadid_private.origin_allowed(p_command), false) then raise exception 'installation_revoked'; end if;

  select * into st from public.sadid_ledger_state where store_id = c.store_id for update;
  if not found then raise exception 'ledger_not_initialized'; end if;

  select * into oldop from public.sadid_operations where store_id = c.store_id and operation_id = op;
  if found then
    if oldop.request is distinct from p_command then raise exception 'operation_id_reused'; end if;
    return oldop.result;
  end if;

  if p_command ->> 'expectedEpoch' is distinct from st.epoch::text and not coalesce(sadid_private.recovery_epoch_allowed(st.epoch, p_command), false) then
    raise exception 'epoch_changed';
  end if;

  -- per-command refusals become receipts; caller-level ones raise
  if not sadid_private.operation_allowed(kind) then
    code := 'store_permission_denied';
  elsif jsonb_typeof(coalesce(p_command -> 'dependsOn', '[]')) <> 'array' or jsonb_array_length(coalesce(p_command -> 'dependsOn', '[]')) > 100 then
    code := 'invalid_dependencies';
  else
    begin
      if exists (select 1 from jsonb_array_elements_text(p_command -> 'dependsOn') e where e::uuid is null) then
        code := 'invalid_dependencies';
      end if;
    exception when data_exception then
      code := 'invalid_dependencies';
    end;
  end if;

  if code is null then
    for dependency in select jsonb_array_elements_text(coalesce(p_command -> 'dependsOn', '[]')) loop
      select * into oldop from public.sadid_operations where store_id = c.store_id and operation_id = dependency::uuid;
      if not found then raise exception 'dependency_pending'; end if;
      if oldop.result ->> 'status' <> 'accepted' then code := 'dependency_rejected'; exit; end if;
    end loop;
  end if;

  if code is null then
    begin
      -- match strict ISO-8601 with an offset; ledger-core reads createdAt with OffsetDateTime.parse
      if p_command ->> 'createdAt' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9](\.[0-9]{1,9})?)?(Z|[+-][0-9]{2}:[0-9]{2})$' then
        code := 'invalid_timestamp';
      else
        occurred := (p_command ->> 'createdAt')::timestamptz;
        if occurred is null or not isfinite(occurred) then code := 'invalid_timestamp'; end if;
      end if;
    exception when data_exception then
      code := 'invalid_timestamp';
    end;
  end if;

  if code is null then
    begin
      if kind = 'contact.create' then
        if (select count(*) from public.sadid_contacts where store_id = c.store_id and not archived) >= sadid_private.contact_limit() then
          raise exception 'contact_limit_reached';
        end if;
        if length(trim(coalesce(payload ->> 'name', ''))) not between 1 and 120
           or coalesce(payload ->> 'kind', '') not in ('customer', 'supplier')
           or length(coalesce(payload ->> 'note', '')) > 2000
           or length(coalesce(payload ->> 'category', '')) > 80
           or length(coalesce(payload ->> 'externalReference', '')) > 120 then
          raise exception 'invalid_input';
        end if;
        v_phone := regexp_replace(coalesce(payload ->> 'phone', ''), '[[:space:]()\-]', '', 'g');
        v_phone := translate(v_phone, '٠١٢٣٤٥٦٧٨٩', '0123456789');
        if v_phone <> '' and v_phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'invalid_phone'; end if;
        if v_phone <> '' and exists (select 1 from public.sadid_contacts where store_id = c.store_id and sadid_contacts.phone = v_phone) then
          raise exception 'duplicate_phone';
        end if;
        insert into public.sadid_contacts(store_id, id, name, phone, kind, category, note, external_reference, operation_id, created_at)
        values (
          c.store_id, entity, trim(payload ->> 'name'), v_phone,
          payload ->> 'kind', coalesce(payload ->> 'category', ''), coalesce(payload ->> 'note', ''), coalesce(payload ->> 'externalReference', ''),
          op, occurred
        );
      elsif kind = 'contact.update' then
        contact := (payload ->> 'contactId')::uuid;
        if contact is null then raise exception 'invalid_input'; end if;
        if length(trim(coalesce(payload ->> 'name', ''))) not between 1 and 120
           or coalesce(payload ->> 'kind', '') not in ('customer', 'supplier')
           or length(coalesce(payload ->> 'note', '')) > 2000
           or length(coalesce(payload ->> 'category', '')) > 80 then
          raise exception 'invalid_input';
        end if;
        v_phone := regexp_replace(coalesce(payload ->> 'phone', ''), '[[:space:]()\-]', '', 'g');
        v_phone := translate(v_phone, '٠١٢٣٤٥٦٧٨٩', '0123456789');
        if v_phone <> '' and v_phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'invalid_phone'; end if;
        if v_phone <> '' and exists (
          select 1 from public.sadid_contacts
          where store_id = c.store_id and sadid_contacts.phone = v_phone and sadid_contacts.id <> contact
        ) then
          raise exception 'duplicate_phone';
        end if;
        update public.sadid_contacts
        set name = trim(payload ->> 'name'),
            phone = v_phone,
            kind = payload ->> 'kind',
            category = coalesce(payload ->> 'category', ''),
            note = coalesce(payload ->> 'note', ''),
            version = version + 1
        where store_id = c.store_id and id = contact and not archived
          and version = coalesce((p_command ->> 'expectedVersion')::bigint, -1);
        if not found then raise exception 'entity_version_changed'; end if;
      elsif kind = 'debt.create' then
        contact := (payload ->> 'contactId')::uuid;
        if not exists (select 1 from public.sadid_contacts where store_id = c.store_id and id = contact and not archived) then
          raise exception 'contact_unavailable';
        end if;
        if jsonb_typeof(payload -> 'amountCents') <> 'number' or payload ->> 'amountCents' !~ '^[0-9]+$' then
          raise exception 'invalid_money';
        end if;
        amount := (payload ->> 'amountCents')::bigint;
        if amount not between 1 and 9007199254740991 or payload ->> 'direction' not in ('receivable', 'payable') or length(coalesce(payload ->> 'note', '')) > 2000 then
          raise exception 'invalid_input';
        end if;
        if coalesce(payload ->> 'origin', 'manual') not in ('manual', 'overpayment')
           or (payload ->> 'origin' = 'overpayment' and payload ->> 'direction' <> 'payable') then
          raise exception 'invalid_input';
        end if;
        insert into public.sadid_debts(store_id, id, contact_id, direction, origin, amount_cents, note, due_date, operation_id, created_at)
        values (
          c.store_id, entity, contact, payload ->> 'direction', coalesce(payload ->> 'origin', 'manual'), amount,
          coalesce(payload ->> 'note', ''), nullif(payload ->> 'dueDate', '')::date, op, occurred
        );
      elsif kind in ('payment.create', 'debt.correct') then
        debt := (payload ->> 'debtId')::uuid;
        correcting := (kind = 'debt.correct');
        select d.amount_cents into principal
        from public.sadid_debts d
        join public.sadid_contacts t on t.store_id = d.store_id and t.id = d.contact_id
        where d.store_id = c.store_id and d.id = debt and (correcting or not t.archived);
        if not found then raise exception 'debt_unavailable'; end if;
        select coalesce(max(a.version), 1) into ver from public.sadid_adjustments a where a.store_id = c.store_id and a.debt_id = debt;
        select a.amount_cents into amount from public.sadid_adjustments a where a.store_id = c.store_id and a.debt_id = debt order by a.version desc limit 1;
        principal := coalesce(amount, principal);
        select coalesce(sum(p.amount_cents), 0) into paid
        from public.sadid_payments p
        where p.store_id = c.store_id and p.debt_id = debt
          and not exists (select 1 from public.sadid_reversals r where r.store_id = p.store_id and r.payment_id = p.id);
        select coalesce(sum(o.amount_cents), 0) into offset_sum
        from public.sadid_offsets o
        where o.store_id = c.store_id and (o.receivable_debt_id = debt or o.payable_debt_id = debt);
        if jsonb_typeof(payload -> 'amountCents') <> 'number' or payload ->> 'amountCents' !~ '^[0-9]+$' then
          raise exception 'invalid_money';
        end if;
        amount := (payload ->> 'amountCents')::bigint;
        -- A correction may zero an unused Debt; a payment is always at least one cent.
        if (kind = 'payment.create' and amount not between 1 and 9007199254740991)
           or (correcting and amount not between 0 and 9007199254740991) then raise exception 'invalid_money'; end if;
        if kind = 'payment.create' then
          if amount > principal - paid - offset_sum then raise exception 'overpayment'; end if;
          if payload ->> 'method' not in ('cash', 'bank', 'wallet', 'wallet_jawwal', 'wallet_palpay') or length(coalesce(payload ->> 'note', '')) > 2000 then
            raise exception 'invalid_input';
          end if;
          insert into public.sadid_payments(store_id, id, debt_id, amount_cents, method, note, operation_id, created_at)
          values (c.store_id, entity, debt, amount, payload ->> 'method', coalesce(payload ->> 'note', ''), op, occurred);
        else
          reason := trim(coalesce(payload ->> 'reason', ''));
          if length(reason) not between 1 and 2000 then raise exception 'reason_required'; end if;
          if coalesce((p_command ->> 'expectedVersion')::bigint, -1) <> ver then raise exception 'entity_version_changed'; end if;
          if amount < paid + offset_sum then raise exception 'below_paid_total'; end if;
          insert into public.sadid_adjustments(store_id, id, debt_id, amount_cents, previous_amount_cents, version, reason, operation_id)
          values (c.store_id, entity, debt, amount, principal, ver + 1, reason, op);
        end if;
      elsif kind = 'debt.offset' then
        -- Credit settles a new debt: both Debts are one Contact's, opposite directions,
        -- and the amount fits what remains on each. An offset is not cash.
        v_receivable := (payload ->> 'receivableDebtId')::uuid;
        v_payable := (payload ->> 'payableDebtId')::uuid;
        if v_receivable is null or v_payable is null or v_receivable = v_payable then raise exception 'offset_invalid'; end if;
        if jsonb_typeof(payload -> 'amountCents') <> 'number' or payload ->> 'amountCents' !~ '^[0-9]+$' then
          raise exception 'invalid_money';
        end if;
        amount := (payload ->> 'amountCents')::bigint;
        if amount < 1 or amount > 9007199254740991 then raise exception 'offset_invalid'; end if;
        select d.* into r_debt from public.sadid_debts d
          join public.sadid_contacts t on t.store_id = d.store_id and t.id = d.contact_id
          where d.store_id = c.store_id and d.id = v_receivable and d.direction = 'receivable' and not t.archived;
        select d.* into p_debt from public.sadid_debts d
          join public.sadid_contacts t on t.store_id = d.store_id and t.id = d.contact_id
          where d.store_id = c.store_id and d.id = v_payable and d.direction = 'payable' and not t.archived;
        if r_debt.id is null or p_debt.id is null or r_debt.contact_id <> p_debt.contact_id then raise exception 'offset_invalid'; end if;
        select coalesce((select a.amount_cents from public.sadid_adjustments a where a.store_id = c.store_id and a.debt_id = v_receivable order by a.version desc limit 1), r_debt.amount_cents)
               - coalesce((select sum(p.amount_cents) from public.sadid_payments p where p.store_id = c.store_id and p.debt_id = v_receivable
                           and not exists (select 1 from public.sadid_reversals r where r.store_id = p.store_id and r.payment_id = p.id)), 0)
               - coalesce((select sum(o.amount_cents) from public.sadid_offsets o where o.store_id = c.store_id and (o.receivable_debt_id = v_receivable or o.payable_debt_id = v_receivable)), 0)
          into r_left;
        select coalesce((select a.amount_cents from public.sadid_adjustments a where a.store_id = c.store_id and a.debt_id = v_payable order by a.version desc limit 1), p_debt.amount_cents)
               - coalesce((select sum(p.amount_cents) from public.sadid_payments p where p.store_id = c.store_id and p.debt_id = v_payable
                           and not exists (select 1 from public.sadid_reversals r where r.store_id = p.store_id and r.payment_id = p.id)), 0)
               - coalesce((select sum(o.amount_cents) from public.sadid_offsets o where o.store_id = c.store_id and (o.receivable_debt_id = v_payable or o.payable_debt_id = v_payable)), 0)
          into p_left;
        if amount > r_left or amount > p_left then raise exception 'offset_invalid'; end if;
        insert into public.sadid_offsets(store_id, id, receivable_debt_id, payable_debt_id, amount_cents, operation_id, created_at)
        values (c.store_id, entity, v_receivable, v_payable, amount, op, occurred);
      elsif kind = 'payment.reverse' then
        payment := (payload ->> 'paymentId')::uuid;
        reason := trim(coalesce(payload ->> 'reason', ''));
        if length(reason) not between 1 and 2000 then raise exception 'reason_required'; end if;
        if not exists (select 1 from public.sadid_payments where store_id = c.store_id and id = payment) then
          raise exception 'payment_unavailable';
        end if;
        if exists (select 1 from public.sadid_reversals where store_id = c.store_id and payment_id = payment) then
          raise exception 'already_reversed';
        end if;
        insert into public.sadid_reversals(store_id, id, payment_id, reason, operation_id)
        values (c.store_id, entity, payment, reason, op);
      elsif kind in ('contact.archive', 'contact.restore') then
        contact := (payload ->> 'contactId')::uuid;
        if length(trim(coalesce(payload ->> 'reason', ''))) not between 1 and 2000 then raise exception 'reason_required'; end if;
        toggled := (kind = 'contact.archive');
        update public.sadid_contacts
        set archived = toggled, version = version + 1
        where store_id = c.store_id and id = contact and version = coalesce((p_command ->> 'expectedVersion')::bigint, -1);
        if not found then raise exception 'entity_version_changed'; end if;
      else
        raise exception 'unsupported_operation';
      end if;
    exception
      when raise_exception then code := sqlerrm;
      when unique_violation then code := 'entity_id_exists';
      when check_violation or not_null_violation or invalid_text_representation or datetime_field_overflow or numeric_value_out_of_range then code := 'invalid_input';
    end;
  end if;

  seq := st.cursor + 1;
  result := jsonb_build_object(
    'operationId', op,
    'entityId', entity,
    'status', case when code is null then 'accepted' else 'rejected' end,
    'code', code,
    'sequence', seq,
    'epoch', st.epoch,
    'serverTime', clock_timestamp()
  );
  insert into public.sadid_operations(store_id, operation_id, request, result, user_id, installation_id, generation)
  values (c.store_id, op, p_command, result, c.user_id, c.installation_id, c.generation);
  insert into public.sadid_events(store_id, sequence, operation_id, kind, entity_id, user_id, installation_id, generation, command)
  values (c.store_id, seq, op, case when code is null then kind else 'operation.rejected' end, entity, c.user_id, c.installation_id, c.generation, p_command);
  update public.sadid_ledger_state set cursor = seq where store_id = c.store_id;
  return result;
end $$;
alter function sadid_private.apply_one(jsonb, sadid_private.capabilities) owner to sadid_ledger_executor;
revoke all on function sadid_private.apply_one(jsonb, sadid_private.capabilities) from public, anon, authenticated, service_role;
grant execute on function sadid_private.apply_one(jsonb, sadid_private.capabilities) to sadid_ledger_executor;

-- One store's full ledger for support, with an audit row.
create or replace function sadid_private.support_snapshot(p_store bigint, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor_name text;
  result jsonb;
  request_id uuid := gen_random_uuid();
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  actor_name := sadid_private.jwt_admin_actor();
  if length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then raise exception 'reason_required'; end if;

  perform set_config('sadid.support.store', p_store::text, true);
  select jsonb_build_object(
    'storeId', p_store,
    'requestId', request_id,
    'epoch', s.epoch,
    'cursor', s.cursor,
    'contacts', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_contacts t where store_id = p_store), '[]'::jsonb),
    'debts', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_debts t where store_id = p_store), '[]'::jsonb),
    'payments', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_payments t where store_id = p_store), '[]'::jsonb),
    'offsets', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_offsets t where store_id = p_store), '[]'::jsonb),
    'adjustments', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_adjustments t where store_id = p_store), '[]'::jsonb),
    'reversals', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_reversals t where store_id = p_store), '[]'::jsonb),
    'events', coalesce((select jsonb_agg(to_jsonb(t)) from public.sadid_events t where store_id = p_store), '[]'::jsonb)
  ) into result
  from public.sadid_ledger_state s
  where store_id = p_store;

  if result is null then raise exception 'ledger_not_initialized'; end if;

  insert into sadid_private.admin_access_events(id, store_id, actor, reason, outcome, record_counts)
  values (
    request_id, p_store, actor_name, p_reason, 'allowed',
    jsonb_build_object(
      'contacts', jsonb_array_length(result -> 'contacts'),
      'debts', jsonb_array_length(result -> 'debts'),
      'payments', jsonb_array_length(result -> 'payments'),
      'events', jsonb_array_length(result -> 'events')
    )
  );
  return result;
end $$;
alter function sadid_private.support_snapshot(bigint, text) owner to sadid_support_reader;

revoke all on function sadid_private.support_snapshot(bigint, text) from public, anon, service_role;
grant execute on function sadid_private.support_snapshot(bigint, text) to authenticated;

-- Balances in cents per store for the dashboard, with an audit row each.
create or replace function sadid_private.admin_summaries(p_stores bigint[]) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor_name text;
  store_number bigint;
  summary jsonb;
  result jsonb := '[]'::jsonb;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  actor_name := sadid_private.jwt_admin_actor();
  if cardinality(p_stores) > 10000 then raise exception 'invalid_input'; end if;

  for store_number in select distinct unnest(p_stores) order by 1 loop
    perform set_config('sadid.support.store', store_number::text, true);
    if not exists (select 1 from public.sadid_ledger_state where store_id = store_number) then
      result := result || jsonb_build_array(jsonb_build_object('storeId', store_number, 'initialized', false));
      continue;
    end if;
    with balances as (
      select d.id, d.direction,
             coalesce(a.amount_cents, d.amount_cents) as principal,
             coalesce(a.amount_cents, d.amount_cents) - coalesce(p.paid, 0) - coalesce(o.offsetted, 0) as remaining
      from public.sadid_debts d
      left join lateral (
        select amount_cents from public.sadid_adjustments x
        where x.store_id = d.store_id and x.debt_id = d.id order by version desc limit 1
      ) a on true
      left join lateral (
        select sum(x.amount_cents) as paid from public.sadid_payments x
        where x.store_id = d.store_id and x.debt_id = d.id
          and not exists (select 1 from public.sadid_reversals r where r.store_id = x.store_id and r.payment_id = x.id)
      ) p on true
      left join lateral (
        select sum(x.amount_cents) as offsetted from public.sadid_offsets x
        where x.store_id = d.store_id and (x.receivable_debt_id = d.id or x.payable_debt_id = d.id)
      ) o on true
      where d.store_id = store_number
    )
    select jsonb_build_object(
      'storeId', store_number,
      'initialized', true,
      'contacts', (select count(*) from public.sadid_contacts where store_id = store_number),
      'activeContacts', (select count(*) from public.sadid_contacts where store_id = store_number and not archived),
      'debts', count(*),
      'openDebts', count(*) filter (where remaining > 0),
      'debtTotalCents', coalesce(sum(principal), 0),
      'receivableCents', coalesce(sum(remaining) filter (where direction = 'receivable'), 0),
      'payableCents', coalesce(sum(remaining) filter (where direction = 'payable'), 0),
      'payments', (select count(*) from public.sadid_payments where store_id = store_number)
    ) into summary from balances;

    insert into sadid_private.admin_access_events(store_id, actor, reason, outcome, record_counts)
    values (
      store_number, actor_name, 'Dashboard financial summary', 'allowed',
      jsonb_build_object('contacts', summary -> 'contacts', 'debts', summary -> 'debts', 'payments', summary -> 'payments')
    );
    result := result || jsonb_build_array(summary);
  end loop;
  return result;
end $$;
alter function sadid_private.admin_summaries(bigint[]) owner to sadid_support_reader;

revoke all on function sadid_private.admin_summaries(bigint[]) from public, anon, service_role;
grant execute on function sadid_private.admin_summaries(bigint[]) to authenticated;

drop function sadid_private.debt_balances(bigint);
-- Each debt's principal after its latest correction, what was paid on it net of reversals,
-- and what credit offsets settled on it. Remaining is principal - paid - offsetted.
create function sadid_private.debt_balances(p_store bigint)
returns table (id uuid, contact_id uuid, direction text, amount_cents bigint, principal bigint, paid bigint, offsetted bigint)
language sql stable security invoker set search_path = '' as $$
  select d.id, d.contact_id, d.direction, d.amount_cents,
         coalesce(a.amount_cents, d.amount_cents),
         coalesce(p.paid, 0)::bigint,
         coalesce(o.offsetted, 0)::bigint
  from public.sadid_debts d
  left join lateral (
    select x.amount_cents from public.sadid_adjustments x
    where x.store_id = d.store_id and x.debt_id = d.id order by x.version desc limit 1
  ) a on true
  left join lateral (
    select sum(x.amount_cents) as paid from public.sadid_payments x
    where x.store_id = d.store_id and x.debt_id = d.id
      and not exists (select 1 from public.sadid_reversals r where r.store_id = x.store_id and r.payment_id = x.id)
  ) p on true
  left join lateral (
    select sum(x.amount_cents) as offsetted from public.sadid_offsets x
    where x.store_id = d.store_id and (x.receivable_debt_id = d.id or x.payable_debt_id = d.id)
  ) o on true
  where d.store_id = p_store
$$;
revoke all on function sadid_private.debt_balances(bigint) from public, anon, authenticated, service_role;
grant execute on function sadid_private.debt_balances(bigint) to sadid_support_reader;

-- One page of a store's ledger for admin and support, with an audit row per read. Money is in cents.
-- p_kind: contacts, debts, payments (newest first) or events (latest sequence first).
create or replace function sadid_private.admin_ledger(
  p_store bigint,
  p_kind text,
  p_reason text,
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor_name text;
  st public.sadid_ledger_state;
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
  v_query text := nullif(lower(trim(coalesce(p_query, ''))), '');
  v_at timestamptz;
  v_id uuid;
  v_sequence bigint;
  v_rows jsonb;
  v_next jsonb;
  v_totals jsonb;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  actor_name := sadid_private.jwt_admin_actor();
  if length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then raise exception 'reason_required'; end if;
  if p_kind not in ('contacts', 'debts', 'payments', 'events') then raise exception 'invalid_input'; end if;
  if p_after is not null and jsonb_typeof(p_after) <> 'object' then raise exception 'invalid_input'; end if;
  if p_kind = 'events' then
    v_sequence := (p_after ->> 'sequence')::bigint;
  else
    v_at := (p_after ->> 'at')::timestamptz;
    v_id := (p_after ->> 'id')::uuid;
  end if;

  perform set_config('sadid.support.store', p_store::text, true);
  select * into st from public.sadid_ledger_state where store_id = p_store;
  if not found then raise exception 'ledger_not_initialized'; end if;

  select jsonb_build_object(
    'receivableCents', coalesce(sum(principal - paid - offsetted) filter (where direction = 'receivable'), 0),
    'payableCents', coalesce(sum(principal - paid - offsetted) filter (where direction = 'payable'), 0)
  ) into v_totals from sadid_private.debt_balances(p_store);
  v_totals := v_totals || jsonb_build_object('netCents', (v_totals ->> 'receivableCents')::bigint - (v_totals ->> 'payableCents')::bigint);

  if p_kind = 'contacts' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', t.id,
      'name', t.name,
      'phone', t.phone,
      'category', t.category,
      'note', t.note,
      'archived', t.archived,
      'receivableCents', t.receivable,
      'payableCents', t.payable,
      'createdAt', t.created_at
    ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
    from (
      with page as (
        select c.id, c.name, c.phone, c.category, c.note, c.archived, c.created_at
        from public.sadid_contacts c
        where c.store_id = p_store
          and (v_at is null or (c.created_at, c.id) < (v_at, v_id))
          and (v_query is null or strpos(lower(c.name), v_query) > 0 or strpos(c.phone, v_query) > 0)
        order by c.created_at desc, c.id desc
        limit v_limit + 1
      ),
      balances as (
        select b.contact_id,
               sum(b.principal - b.paid - b.offsetted) filter (where b.direction = 'receivable') as receivable,
               sum(b.principal - b.paid - b.offsetted) filter (where b.direction = 'payable') as payable
        from sadid_private.debt_balances(p_store) b
        where b.contact_id in (select id from page)
        group by b.contact_id
      )
      select page.*, coalesce(balances.receivable, 0) as receivable, coalesce(balances.payable, 0) as payable
      from page left join balances on balances.contact_id = page.id
    ) t;
  elsif p_kind = 'debts' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', t.id,
      'contactId', t.contact_id,
      'contactName', t.contact_name,
      'direction', t.direction,
      'originalCents', t.amount_cents,
      'amountCents', t.principal,
      'paidCents', t.paid,
      'remainingCents', t.principal - t.paid - t.offsetted,
      'note', t.note,
      'dueDate', t.due_date,
      'createdAt', t.created_at
    ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
    from (
      select d.id, d.contact_id, c.name as contact_name, d.direction, d.amount_cents, b.principal, b.paid, b.offsetted,
             d.note, d.due_date, d.created_at
      from public.sadid_debts d
      join sadid_private.debt_balances(p_store) b on b.id = d.id
      join public.sadid_contacts c on c.store_id = d.store_id and c.id = d.contact_id
      where d.store_id = p_store
        and (v_at is null or (d.created_at, d.id) < (v_at, v_id))
        and (v_query is null or strpos(lower(c.name), v_query) > 0 or strpos(lower(d.note), v_query) > 0)
      order by d.created_at desc, d.id desc
      limit v_limit + 1
    ) t;
  elsif p_kind = 'payments' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', t.id,
      'debtId', t.debt_id,
      'contactId', t.contact_id,
      'contactName', t.contact_name,
      'direction', t.direction,
      'amountCents', t.amount_cents,
      'method', t.method,
      'note', t.note,
      'reversed', t.reversed,
      'createdAt', t.created_at
    ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
    from (
      select x.id, x.debt_id, d.contact_id, c.name as contact_name, d.direction, x.amount_cents, x.method, x.note,
             exists (select 1 from public.sadid_reversals r where r.store_id = x.store_id and r.payment_id = x.id) as reversed,
             x.created_at
      from public.sadid_payments x
      join public.sadid_debts d on d.store_id = x.store_id and d.id = x.debt_id
      join public.sadid_contacts c on c.store_id = d.store_id and c.id = d.contact_id
      where x.store_id = p_store
        and (v_at is null or (x.created_at, x.id) < (v_at, v_id))
        and (v_query is null or strpos(lower(c.name), v_query) > 0 or strpos(lower(x.note), v_query) > 0)
      order by x.created_at desc, x.id desc
      limit v_limit + 1
    ) t;
  else
    select coalesce(jsonb_agg(jsonb_build_object(
      'sequence', t.sequence,
      'operationId', t.operation_id,
      'kind', t.kind,
      'entityId', t.entity_id,
      'userId', t.user_id,
      'installationId', t.installation_id,
      'generation', t.generation,
      'command', t.command,
      'createdAt', t.created_at
    ) order by t.sequence desc), '[]'::jsonb) into v_rows
    from (
      select * from public.sadid_events e
      where e.store_id = p_store
        and (v_sequence is null or e.sequence < v_sequence)
        and (v_query is null or strpos(e.kind, v_query) > 0)
      order by e.sequence desc
      limit v_limit + 1
    ) t;
  end if;

  if jsonb_array_length(v_rows) > v_limit then
    v_rows := v_rows - v_limit;
    v_next := case p_kind
      when 'events' then jsonb_build_object('sequence', v_rows -> (v_limit - 1) -> 'sequence')
      else jsonb_build_object('at', v_rows -> (v_limit - 1) -> 'createdAt', 'id', v_rows -> (v_limit - 1) -> 'id')
    end;
  end if;

  insert into sadid_private.admin_access_events(store_id, actor, reason, outcome, record_counts)
  values (p_store, actor_name, p_reason, 'allowed', jsonb_build_object(p_kind, jsonb_array_length(v_rows)));

  return jsonb_build_object(
    'storeId', p_store,
    'kind', p_kind,
    'epoch', st.epoch,
    'latestSequence', st.cursor,
    'totals', v_totals,
    'rows', v_rows,
    'nextCursor', v_next
  );
end $$;
alter function sadid_private.admin_ledger(bigint, text, text, text, jsonb, integer) owner to sadid_support_reader;

revoke all on function sadid_private.admin_ledger(bigint, text, text, text, jsonb, integer) from public, anon, service_role;
grant execute on function sadid_private.admin_ledger(bigint, text, text, text, jsonb, integer) to authenticated;
