-- Balances in cents per store for the dashboard, with an audit row each.
create function sadid_private.admin_summaries(p_stores bigint[]) returns jsonb
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

create function public.sadid_admin_summaries(p_stores bigint[]) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_summaries(p_stores)
$$;
revoke all on function sadid_private.admin_summaries(bigint[]) from public, anon, service_role;
grant execute on function sadid_private.admin_summaries(bigint[]) to authenticated;
revoke all on function public.sadid_admin_summaries(bigint[]) from public, anon, service_role;
grant execute on function public.sadid_admin_summaries(bigint[]) to authenticated;
