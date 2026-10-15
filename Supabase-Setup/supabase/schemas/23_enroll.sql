-- Registers a device as pending, within the store's device cap.
create function sadid_private.enroll(
  p_installation uuid,
  p_key jsonb,
  p_nonce uuid,
  p_label text default '',
  p_staff_name text default ''
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := (select auth.uid());
  v_session uuid := ((select auth.jwt()) ->> 'session_id')::uuid;
  v_store_id bigint;
  s public.stores;
  i sadid_private.installations;
  v_generation integer;
  v_active_count integer;
begin
  if p_key ->> 'kty' is distinct from 'EC'
     or p_key ->> 'crv' is distinct from 'P-256'
     or p_key ? 'd'
     or length(coalesce(p_key ->> 'x', '')) <> 43
     or length(coalesce(p_key ->> 'y', '')) <> 43 then
    raise exception 'invalid_public_key';
  end if;

  if v_user is null or v_session is null
     or coalesce(((select auth.jwt()) -> 'app_metadata' ->> 'force_password_change')::boolean, false) then
    raise exception 'authorization_required';
  end if;

  if not exists (
    select 1 from auth.sessions a
    where a.id = v_session and a.user_id = v_user and (a.not_after is null or a.not_after > clock_timestamp())
  ) then
    raise exception 'authorization_required';
  end if;

  select m.store_id into v_store_id from sadid_private.store_memberships m where m.user_id = v_user;
  if not found then raise exception 'authorization_required'; end if;

  select * into s from public.stores where id = v_store_id for update;
  if not found or s.status <> 'active'
     or s.subscription_mode = 'paused'
     or (s.subscription_mode = 'timed' and coalesce(s.subscription_expires_at, 0) <= (extract(epoch from clock_timestamp()) * 1000)::bigint) then
    raise exception 'store_suspended';
  end if;

  select * into i from sadid_private.installations where id = p_installation;
  if found then
    if i.store_id <> v_store_id or i.public_key <> p_key then raise exception 'installation_key_changed'; end if;
    if i.status = 'revoked' then raise exception 'installation_revoked'; end if;
    update sadid_private.installations
    set label = case when trim(coalesce(p_label, '')) <> '' then left(trim(p_label), 120) else label end,
        staff_name = case when staff_name = '' and trim(coalesce(p_staff_name, '')) <> '' then left(trim(p_staff_name), 80) else staff_name end
    where id = p_installation
    returning * into i;
  else
    select count(*) into v_active_count from sadid_private.installations where store_id = v_store_id and status = 'active';
    if v_active_count >= s.allowed_devices then
      raise exception 'device_limit_reached';
    end if;
    select coalesce(max(generation), 0) + 1 into v_generation from sadid_private.installations where store_id = v_store_id;
    insert into sadid_private.installations(id, store_id, generation, public_key, label, staff_name, status)
    values (
      p_installation,
      v_store_id,
      v_generation,
      p_key,
      left(trim(coalesce(p_label, '')), 120),
      left(trim(coalesce(p_staff_name, '')), 80),
      'pending'
    )
    returning * into i;
  end if;

  begin
    insert into sadid_private.request_nonces(installation_id, nonce) values (p_installation, p_nonce);
  exception when unique_violation then
    raise exception 'proof_replayed';
  end;

  return jsonb_build_object(
    'installationId', i.id,
    'generation', i.generation,
    'status', i.status,
    'label', i.label,
    'staffName', i.staff_name,
    'permissions', i.permissions
  );
end $$;

create function public.sadid_enroll(
  p_installation uuid,
  p_key jsonb,
  p_nonce uuid,
  p_label text default '',
  p_staff_name text default ''
) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.enroll(p_installation, p_key, p_nonce, p_label, p_staff_name)
$$;
revoke all on function sadid_private.enroll(uuid, jsonb, uuid, text, text), public.sadid_enroll(uuid, jsonb, uuid, text, text) from public, anon, service_role;
grant execute on function sadid_private.enroll(uuid, jsonb, uuid, text, text), public.sadid_enroll(uuid, jsonb, uuid, text, text) to authenticated;
