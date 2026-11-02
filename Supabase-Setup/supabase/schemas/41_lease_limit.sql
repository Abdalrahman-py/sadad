-- The store's cap on active contacts, read by /v1/mobile/lease for the signed lease.
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
