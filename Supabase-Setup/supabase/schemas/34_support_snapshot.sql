-- One store's full ledger for support, with an audit row.
create function sadid_private.support_snapshot(p_store bigint, p_reason text) returns jsonb
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

create function public.sadid_support_snapshot(p_store bigint, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.support_snapshot(p_store, p_reason)
$$;
revoke all on function sadid_private.support_snapshot(bigint, text) from public, anon, service_role;
grant execute on function sadid_private.support_snapshot(bigint, text) to authenticated;
revoke all on function public.sadid_support_snapshot(bigint, text) from public, anon, service_role;
grant execute on function public.sadid_support_snapshot(bigint, text) to authenticated;
