-- The api creates the Auth user <username>@stores.sadad.invalid first, then links it here in one transaction.
create function public.sadid_create_store(p_user uuid, p_name text, p_username text) returns bigint
language plpgsql security definer set search_path = '' as $$
declare v_store bigint;
begin
  insert into public.stores(name, username) values (p_name, p_username) returning id into v_store;
  insert into sadid_private.store_memberships(user_id, store_id) values (p_user, v_store);
  return v_store;
end $$;
revoke all on function public.sadid_create_store(uuid, text, text) from public, anon, authenticated;
grant execute on function public.sadid_create_store(uuid, text, text) to service_role;
