-- Ticket 136: every store's person limit is at least 200 (stores above 200 keep their value) and
-- the signed mobile lease carries the store's limit for the app to check.
alter table public.stores alter column debtor_limit set default 200;
update public.stores set debtor_limit = 200 where debtor_limit < 200;

-- Identical to Supabase-Setup/supabase/schemas/41_lease_limit.sql: please edit that file.
create function public.sadid_lease_limit() returns integer
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
alter function public.sadid_lease_limit() owner to sadid_auth_reader;
revoke all on function public.sadid_lease_limit() from public, anon, service_role;
grant execute on function public.sadid_lease_limit() to authenticated;
