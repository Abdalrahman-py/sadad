-- The api creates the Auth user <username>@stores.sadad.invalid first, then links it here in one transaction.
-- p_settings takes the keys of sadid_private.store_settings plus subscriptionMode and subscriptionDays.
create function public.sadid_create_store(p_user uuid, p_name text, p_username text, p_settings jsonb default '{}'::jsonb) returns bigint
language plpgsql security definer set search_path = '' as $$
declare s public.stores;
begin
  if jsonb_typeof(p_settings) is distinct from 'object' then raise exception 'invalid_input'; end if;
  begin
    insert into public.stores(name, username) values (p_name, p_username) returning * into s;
  exception when unique_violation then
    raise exception 'username_taken';
  end;
  s := sadid_private.store_settings(
    s,
    (p_settings - 'subscriptionMode' - 'subscriptionDays') || jsonb_build_object('name', p_name, 'username', p_username)
  );
  if p_settings ? 'subscriptionMode' then
    s := sadid_private.store_subscription(s, p_settings ->> 'subscriptionMode', (p_settings ->> 'subscriptionDays')::integer, false);
  end if;
  s := sadid_private.save_store(s);
  insert into sadid_private.store_memberships(user_id, store_id) values (p_user, s.id);
  return s.id;
end $$;
revoke all on function public.sadid_create_store(uuid, text, text, jsonb) from public, anon, authenticated;
grant execute on function public.sadid_create_store(uuid, text, text, jsonb) to service_role;
