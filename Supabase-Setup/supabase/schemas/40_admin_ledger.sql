-- Each debt's principal after its latest correction, and what was paid on it net of reversals.
create function sadid_private.debt_balances(p_store bigint)
returns table (id uuid, contact_id uuid, direction text, amount_cents bigint, principal bigint, paid bigint)
language sql stable security invoker set search_path = '' as $$
  select d.id, d.contact_id, d.direction, d.amount_cents,
         coalesce(a.amount_cents, d.amount_cents),
         coalesce(p.paid, 0)::bigint
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
  where d.store_id = p_store
$$;
revoke all on function sadid_private.debt_balances(bigint) from public, anon, authenticated, service_role;
grant execute on function sadid_private.debt_balances(bigint) to sadid_support_reader;

-- One page of a store's ledger for admin and support, with an audit row per read. Money is in cents.
-- p_kind: contacts, debts, payments (newest first) or events (latest sequence first).
create function sadid_private.admin_ledger(
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
    'receivableCents', coalesce(sum(principal - paid) filter (where direction = 'receivable'), 0),
    'payableCents', coalesce(sum(principal - paid) filter (where direction = 'payable'), 0)
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
               sum(b.principal - b.paid) filter (where b.direction = 'receivable') as receivable,
               sum(b.principal - b.paid) filter (where b.direction = 'payable') as payable
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
      'remainingCents', t.principal - t.paid,
      'note', t.note,
      'dueDate', t.due_date,
      'createdAt', t.created_at
    ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
    from (
      select d.id, d.contact_id, c.name as contact_name, d.direction, d.amount_cents, b.principal, b.paid,
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

create function public.sadid_admin_ledger(
  p_store bigint,
  p_kind text,
  p_reason text,
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_ledger(p_store, p_kind, p_reason, p_query, p_after, p_limit)
$$;
revoke all on function sadid_private.admin_ledger(bigint, text, text, text, jsonb, integer) from public, anon, service_role;
grant execute on function sadid_private.admin_ledger(bigint, text, text, text, jsonb, integer) to authenticated;
revoke all on function public.sadid_admin_ledger(bigint, text, text, text, jsonb, integer) from public, anon, service_role;
grant execute on function public.sadid_admin_ledger(bigint, text, text, text, jsonb, integer) to authenticated;
