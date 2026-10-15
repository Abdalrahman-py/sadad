-- The store the current capability is for; RLS policies on ledger tables use it.
create function sadid_private.authorized_store() returns bigint
language sql stable security invoker set search_path = '' as $$
  select (sadid_private.current_capability()).store_id
$$;
revoke all on function sadid_private.authorized_store() from public, anon, service_role;
grant execute on function sadid_private.authorized_store() to authenticated, sadid_ledger_executor;
