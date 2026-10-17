-- Store accounts for the dashboard: list, settings, subscription, password reset and permanent delete.
-- The api calls these with the admin's JWT; Auth users are changed through the Auth admin API.

-- admin, support or null; the api checks it before an Auth admin call that no routine guards.
create function public.sadid_admin_role() returns text
language sql stable security definer set search_path = '' as $$
  select sadid_private.jwt_admin_role()
$$;
revoke all on function public.sadid_admin_role() from public, anon, service_role;
grant execute on function public.sadid_admin_role() to authenticated;

create function sadid_private.admin_overview() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  return jsonb_build_object(
    'stores', (select count(*) from public.stores where status <> 'deleted'),
    'active', (select count(*) from public.stores where status = 'active'),
    'suspended', (select count(*) from public.stores where status = 'suspended'),
    'archived', (select count(*) from public.stores where status = 'deleted'),
    'verified', (select count(*) from public.stores where status <> 'deleted' and verified),
    'pendingDevices', (select count(*) from sadid_private.installations where status = 'pending')
  );
end $$;

-- Newest first; q matches part of the name or username; p_after is the previous page's nextCursor.
create function sadid_private.admin_stores(
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50,
  p_include_archived boolean default false
) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
  v_query text := nullif(lower(trim(coalesce(p_query, ''))), '');
  v_after bigint;
  v_rows jsonb;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if p_after is not null and jsonb_typeof(p_after) <> 'object' then raise exception 'invalid_input'; end if;
  v_after := (p_after ->> 'id')::bigint;

  select coalesce(jsonb_agg(sadid_private.store_json(t.s) order by (t.s).id desc), '[]'::jsonb) into v_rows
  from (
    select s from public.stores s
    where (coalesce(p_include_archived, false) or s.status <> 'deleted')
      and (v_after is null or s.id < v_after)
      and (v_query is null or strpos(lower(s.name), v_query) > 0 or strpos(lower(s.username), v_query) > 0)
    order by s.id desc
    limit v_limit + 1
  ) t;

  if jsonb_array_length(v_rows) > v_limit then
    return jsonb_build_object(
      'stores', v_rows - v_limit,
      'nextCursor', jsonb_build_object('id', v_rows -> (v_limit - 1) -> 'id')
    );
  end if;
  return jsonb_build_object('stores', v_rows, 'nextCursor', null);
end $$;

-- p_changes: name, username, verified, debtorLimit, allowedDevices, permissions, status (active or suspended).
create function sadid_private.admin_update_store(p_store bigint, p_changes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  v_old public.stores;
  s public.stores;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();
  if jsonb_typeof(p_changes) is distinct from 'object' or p_changes = '{}'::jsonb then raise exception 'invalid_input'; end if;

  select * into v_old from public.stores where id = p_store for update;
  if not found then raise exception 'store_not_found'; end if;
  if v_old.status = 'deleted' then raise exception 'store_archived'; end if;

  s := sadid_private.store_settings(v_old, p_changes - 'status');
  if p_changes ? 'status' then
    if p_changes ->> 'status' = 'active' then
      if not sadid_private.subscription_active(s) then raise exception 'subscription_inactive'; end if;
    elsif p_changes ->> 'status' is distinct from 'suspended' then
      raise exception 'invalid_input';
    end if;
    s.status := p_changes ->> 'status';
    s.suspend_until := null;
  end if;

  -- Lowering the cap never revokes a device; the admin revokes one first.
  if s.allowed_devices < (select count(*) from sadid_private.installations where store_id = p_store and status = 'active') then
    raise exception 'device_limit_reached';
  end if;

  s := sadid_private.save_store(s);
  if v_old.status = 'active' and s.status <> 'active' then
    perform sadid_private.end_store_sessions(p_store);
  end if;

  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (p_store, v_actor, 'store.updated', 'Store settings changed in the admin dashboard', p_changes);
  return jsonb_build_object(
    'store', sadid_private.store_json(s),
    'userId', (select user_id from sadid_private.store_memberships where store_id = p_store),
    'previousUsername', v_old.username
  );
end $$;

create function sadid_private.admin_subscription(p_store bigint, p_mode text, p_days integer default null, p_extend boolean default false) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  v_old public.stores;
  s public.stores;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();

  select * into v_old from public.stores where id = p_store for update;
  if not found then raise exception 'store_not_found'; end if;
  if v_old.status = 'deleted' then raise exception 'store_archived'; end if;

  s := sadid_private.save_store(sadid_private.store_subscription(v_old, p_mode, p_days, p_extend));
  if v_old.status = 'active' and s.status <> 'active' then
    perform sadid_private.end_store_sessions(p_store);
  end if;

  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (
    p_store, v_actor, 'store.subscription', 'Subscription changed in the admin dashboard',
    jsonb_build_object('mode', p_mode, 'days', p_days, 'extend', coalesce(p_extend, false))
  );
  return sadid_private.store_json(s);
end $$;

-- Ends the store's sessions; the api then sets the new password through the Auth admin API.
create function sadid_private.admin_reset_password(p_store bigint, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  s public.stores;
  v_user uuid;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();
  if length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then raise exception 'reason_required'; end if;

  select * into s from public.stores where id = p_store for update;
  if not found then raise exception 'store_not_found'; end if;
  if s.status = 'deleted' then raise exception 'store_archived'; end if;
  select user_id into v_user from sadid_private.store_memberships where store_id = p_store;
  if v_user is null then raise exception 'store_not_found'; end if;

  perform sadid_private.end_store_sessions(p_store);
  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (p_store, v_actor, 'account.password-reset', p_reason, '{}');
  return jsonb_build_object('userId', v_user, 'username', s.username);
end $$;

-- Only a store with no devices and no ledger; p_confirm is its username. The api then deletes the Auth user.
create function sadid_private.admin_delete_store(p_store bigint, p_confirm text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  s public.stores;
  v_user uuid;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();

  select * into s from public.stores where id = p_store for update;
  if not found then raise exception 'store_not_found'; end if;
  if p_confirm is distinct from s.username then raise exception 'delete_confirmation_required'; end if;
  if exists (select 1 from sadid_private.installations where store_id = p_store)
     or exists (select 1 from public.sadid_ledger_state where store_id = p_store) then
    raise exception 'store_has_history';
  end if;

  select user_id into v_user from sadid_private.store_memberships where store_id = p_store;
  delete from public.stores where id = p_store;
  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (p_store, v_actor, 'account.deleted', 'Permanent delete in the admin dashboard', jsonb_build_object('username', s.username));
  return jsonb_build_object('storeId', p_store, 'userId', v_user);
end $$;

create function public.sadid_admin_overview() returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.admin_overview()
$$;
create function public.sadid_admin_stores(
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50,
  p_include_archived boolean default false
) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.admin_stores(p_query, p_after, p_limit, p_include_archived)
$$;
create function public.sadid_admin_update_store(p_store bigint, p_changes jsonb) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_update_store(p_store, p_changes)
$$;
create function public.sadid_admin_subscription(p_store bigint, p_mode text, p_days integer default null, p_extend boolean default false) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_subscription(p_store, p_mode, p_days, p_extend)
$$;
create function public.sadid_admin_reset_password(p_store bigint, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_reset_password(p_store, p_reason)
$$;
create function public.sadid_admin_delete_store(p_store bigint, p_confirm text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_delete_store(p_store, p_confirm)
$$;

revoke all on function
  sadid_private.admin_overview(),
  sadid_private.admin_stores(text, jsonb, integer, boolean),
  sadid_private.admin_update_store(bigint, jsonb),
  sadid_private.admin_subscription(bigint, text, integer, boolean),
  sadid_private.admin_reset_password(bigint, text),
  sadid_private.admin_delete_store(bigint, text),
  public.sadid_admin_overview(),
  public.sadid_admin_stores(text, jsonb, integer, boolean),
  public.sadid_admin_update_store(bigint, jsonb),
  public.sadid_admin_subscription(bigint, text, integer, boolean),
  public.sadid_admin_reset_password(bigint, text),
  public.sadid_admin_delete_store(bigint, text)
from public, anon, service_role;
grant execute on function
  sadid_private.admin_overview(),
  sadid_private.admin_stores(text, jsonb, integer, boolean),
  sadid_private.admin_update_store(bigint, jsonb),
  sadid_private.admin_subscription(bigint, text, integer, boolean),
  sadid_private.admin_reset_password(bigint, text),
  sadid_private.admin_delete_store(bigint, text),
  public.sadid_admin_overview(),
  public.sadid_admin_stores(text, jsonb, integer, boolean),
  public.sadid_admin_update_store(bigint, jsonb),
  public.sadid_admin_subscription(bigint, text, integer, boolean),
  public.sadid_admin_reset_password(bigint, text),
  public.sadid_admin_delete_store(bigint, text)
to authenticated;
