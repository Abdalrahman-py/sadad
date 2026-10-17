-- Store helpers for the admin routines; none is callable through the Data API.
create function sadid_private.now_ms() returns bigint
language sql stable security invoker set search_path = '' as $$
  select (extract(epoch from statement_timestamp()) * 1000)::bigint
$$;

create function sadid_private.subscription_active(s public.stores) returns boolean
language sql stable security invoker set search_path = '' as $$
  select case s.subscription_mode
    when 'permanent' then true
    when 'paused' then false
    else coalesce(s.subscription_expires_at, 0) > sadid_private.now_ms()
  end
$$;

-- One store as the dashboard shows it.
create function sadid_private.store_json(s public.stores) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select jsonb_build_object(
    'id', s.id,
    'name', s.name,
    'username', s.username,
    'status', s.status,
    'archived', s.status = 'deleted',
    'verified', s.verified,
    'debtorLimit', s.debtor_limit,
    'allowedDevices', s.allowed_devices,
    'permissions', s.permissions,
    'revision', s.revision,
    'createdAt', s.created_at,
    'subscriptionMode', s.subscription_mode,
    'subscriptionActive', sadid_private.subscription_active(s),
    'subscriptionStartedAt', s.subscription_started_at,
    'subscriptionExpiresAt', s.subscription_expires_at,
    'subscriptionRemainingMs', case s.subscription_mode
      when 'paused' then greatest(coalesce(s.subscription_paused_remaining_ms, 0), 0)
      when 'timed' then greatest(coalesce(s.subscription_expires_at, 0) - sadid_private.now_ms(), 0)
    end,
    'activeDevices', (select count(*) from sadid_private.installations where store_id = s.id and status = 'active'),
    'pendingDevices', (select count(*) from sadid_private.installations where store_id = s.id and status = 'pending')
  )
$$;

-- Applies dashboard settings to a store row; an unknown key or a bad value is invalid_input.
create function sadid_private.store_settings(s public.stores, p_changes jsonb) returns public.stores
language plpgsql stable security invoker set search_path = '' as $$
declare
  k text;
  v jsonb;
begin
  if jsonb_typeof(p_changes) is distinct from 'object' then raise exception 'invalid_input'; end if;
  for k, v in select key, value from jsonb_each(p_changes) loop
    case k
      when 'name' then
        if jsonb_typeof(v) <> 'string' or length(trim(v #>> '{}')) not between 1 and 120 then raise exception 'invalid_input'; end if;
        s.name := trim(v #>> '{}');
      when 'username' then
        -- The Auth email is <username>@stores.sadad.invalid, so ASCII only and at most 64 characters.
        if jsonb_typeof(v) <> 'string' or (v #>> '{}') !~ '^[A-Za-z0-9._-]{3,64}$' then raise exception 'invalid_input'; end if;
        s.username := v #>> '{}';
      when 'verified' then
        if jsonb_typeof(v) <> 'boolean' then raise exception 'invalid_input'; end if;
        s.verified := (v #>> '{}')::boolean;
      when 'debtorLimit' then
        if jsonb_typeof(v) <> 'number' or (v #>> '{}')::numeric not between 0 and 1000000 or (v #>> '{}')::numeric % 1 <> 0 then
          raise exception 'invalid_input';
        end if;
        s.debtor_limit := (v #>> '{}')::integer;
      when 'allowedDevices' then
        if jsonb_typeof(v) <> 'number' or (v #>> '{}')::numeric not between 1 and 50 or (v #>> '{}')::numeric % 1 <> 0 then
          raise exception 'invalid_input';
        end if;
        s.allowed_devices := (v #>> '{}')::integer;
      when 'permissions' then
        if jsonb_typeof(v) <> 'object' or exists (
          select 1 from jsonb_each(v) p
          where p.key not in ('contacts', 'debts', 'payments', 'reports', 'analytics', 'export', 'whatsapp', 'appLock')
             or jsonb_typeof(p.value) <> 'boolean'
        ) then
          raise exception 'invalid_input';
        end if;
        s.permissions := s.permissions || v;
      else
        raise exception 'invalid_input';
    end case;
  end loop;
  return s;
end $$;

-- permanent and timed make the store active; paused suspends it and keeps a timed store's remaining time.
create function sadid_private.store_subscription(s public.stores, p_mode text, p_days integer, p_extend boolean) returns public.stores
language plpgsql stable security invoker set search_path = '' as $$
declare
  v_now bigint := sadid_private.now_ms();
  v_base bigint;
begin
  if p_mode = 'permanent' then
    s.subscription_started_at := null;
    s.subscription_expires_at := null;
    s.subscription_paused_remaining_ms := null;
    s.status := 'active';
  elsif p_mode = 'paused' then
    if s.subscription_mode = 'timed' then
      s.subscription_paused_remaining_ms := greatest(coalesce(s.subscription_expires_at, 0) - v_now, 0);
    end if;
    s.status := 'suspended';
  elsif p_mode = 'timed' then
    if p_days is null or p_days not between 1 and 36500 then raise exception 'invalid_input'; end if;
    v_base := case
      when not coalesce(p_extend, false) then v_now
      when s.subscription_mode = 'paused' then v_now + greatest(coalesce(s.subscription_paused_remaining_ms, 0), 0)
      else greatest(v_now, coalesce(s.subscription_expires_at, 0))
    end;
    s.subscription_expires_at := v_base + p_days::bigint * 86400000;
    s.subscription_started_at := coalesce(s.subscription_started_at, v_now);
    s.subscription_paused_remaining_ms := null;
    s.status := 'active';
  else
    raise exception 'invalid_input';
  end if;
  s.subscription_mode := p_mode;
  s.suspend_until := null;
  return s;
end $$;

-- Writes a changed store row back and bumps its revision.
create function sadid_private.save_store(s public.stores) returns public.stores
language plpgsql security invoker set search_path = '' as $$
begin
  update public.stores set
    name = s.name,
    username = s.username,
    status = s.status,
    suspend_until = s.suspend_until,
    verified = s.verified,
    debtor_limit = s.debtor_limit,
    allowed_devices = s.allowed_devices,
    permissions = s.permissions,
    subscription_mode = s.subscription_mode,
    subscription_started_at = s.subscription_started_at,
    subscription_expires_at = s.subscription_expires_at,
    subscription_paused_remaining_ms = s.subscription_paused_remaining_ms,
    revision = revision + 1
  where id = s.id
  returning * into s;
  return s;
exception when unique_violation then
  raise exception 'username_taken';
end $$;

-- Ends the store's Auth sessions; its capabilities go with them.
create function sadid_private.end_store_sessions(p_store bigint) returns void
language sql security invoker set search_path = '' as $$
  delete from auth.sessions where user_id in (select user_id from sadid_private.store_memberships where store_id = p_store);
  delete from sadid_private.capabilities where store_id = p_store;
$$;

revoke all on function
  sadid_private.now_ms(),
  sadid_private.subscription_active(public.stores),
  sadid_private.store_json(public.stores),
  sadid_private.store_settings(public.stores, jsonb),
  sadid_private.store_subscription(public.stores, text, integer, boolean),
  sadid_private.save_store(public.stores),
  sadid_private.end_store_sessions(bigint)
from public, anon, authenticated, service_role;
