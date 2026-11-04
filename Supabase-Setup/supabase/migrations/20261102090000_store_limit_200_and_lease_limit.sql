-- Ticket 136: every store's person limit is at least 200 (stores above 200 keep their value) and
-- the signed mobile lease carries the store's limit for the app to check.
alter table public.stores alter column debtor_limit set default 200;
update public.stores set debtor_limit = 200 where debtor_limit < 200;

-- Same as Supabase-Setup/supabase/schemas/41_lease_limit.sql, plus the CREATE grant around the owner change.
-- The store's cap on active contacts, read by /v1/mobile/lease for the signed lease. Owned by
-- sadid_auth_reader like contact_limit(); the public wrapper below stays with the migration role.
create function sadid_private.lease_limit() returns integer
language plpgsql stable security definer set search_path = '' as $$
declare
  cap sadid_private.capabilities;
begin
  cap := sadid_private.current_capability();
  if cap.scope is distinct from 'lease' then
    raise exception 'authorization_required';
  end if;
  return (select debtor_limit from public.stores where id = cap.store_id);
end
$$;
-- A new owner needs CREATE on the schema; schemas/99_revoke_create.sql takes it back the same way.
grant create on schema sadid_private to sadid_auth_reader;
alter function sadid_private.lease_limit() owner to sadid_auth_reader;
revoke create on schema sadid_private from sadid_auth_reader;
revoke all on function sadid_private.lease_limit() from public, anon, service_role;
grant execute on function sadid_private.lease_limit() to authenticated;

create function public.sadid_lease_limit() returns integer
language sql stable security invoker set search_path = '' as $$
  select sadid_private.lease_limit()
$$;
revoke all on function public.sadid_lease_limit() from public, anon, service_role;
grant execute on function public.sadid_lease_limit() to authenticated;
