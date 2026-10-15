-- Activates a pending device, within the store's device cap.
create function sadid_private.approve_installation(p_installation uuid, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  i sadid_private.installations;
  s public.stores;
  v_active_count integer;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();
  if length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then raise exception 'reason_required'; end if;

  select * into i from sadid_private.installations where id = p_installation;
  if not found or i.status = 'revoked' then raise exception 'installation_unavailable'; end if;

  select * into s from public.stores where id = i.store_id for update;
  if i.status = 'active' then
    return jsonb_build_object('installationId', i.id, 'generation', i.generation, 'status', 'active');
  end if;

  -- Over the cap the admin revokes a device first; approval never revokes one silently.
  select count(*) into v_active_count from sadid_private.installations where store_id = i.store_id and status = 'active';
  if v_active_count >= s.allowed_devices then raise exception 'device_limit_reached'; end if;

  -- A revoke that committed after the first read wins.
  update sadid_private.installations
  set status = 'active', approved_by = v_actor, approved_at = clock_timestamp()
  where id = i.id and status = 'pending';
  if not found then raise exception 'installation_unavailable'; end if;

  insert into public.sadid_ledger_state(store_id) values (i.store_id) on conflict do nothing;
  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (
    i.store_id,
    v_actor,
    'installation.approved',
    p_reason,
    jsonb_build_object('installationId', i.id, 'generation', i.generation)
  );
  return jsonb_build_object('installationId', i.id, 'generation', i.generation, 'status', 'active');
end $$;

create function public.sadid_approve_installation(p_installation uuid, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.approve_installation(p_installation, p_reason)
$$;
revoke all on function sadid_private.approve_installation(uuid, text) from public, anon, service_role;
grant execute on function sadid_private.approve_installation(uuid, text) to authenticated;
revoke all on function public.sadid_approve_installation(uuid, text) from public, anon, service_role;
grant execute on function public.sadid_approve_installation(uuid, text) to authenticated;
