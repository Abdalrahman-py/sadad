-- The store's cap on active contacts.
create function sadid_private.contact_limit() returns integer
language sql stable security definer set search_path = '' as $$
  select debtor_limit from public.stores where id = (sadid_private.current_capability()).store_id
$$;
alter function sadid_private.contact_limit() owner to sadid_auth_reader;
revoke all on function sadid_private.contact_limit() from public, anon, authenticated, service_role;
grant execute on function sadid_private.contact_limit() to sadid_ledger_executor;
