-- Revokes a device and its capabilities.
create function sadid_private.revoke_installation(p_installation uuid, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  i sadid_private.installations;
  store_number bigint;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();
  if length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then raise exception 'invalid_input'; end if;

  select store_id into store_number from sadid_private.installations where id = p_installation;
  if not found then raise exception 'installation_unavailable'; end if;

  perform 1 from public.stores where id = store_number for update;
  select * into i from sadid_private.installations where id = p_installation for update;
  update sadid_private.installations set status = 'revoked', revoked_at = clock_timestamp(), reason = p_reason where id = i.id;
  delete from sadid_private.capabilities where installation_id = i.id;

  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (i.store_id, v_actor, 'installation.revoked', p_reason, jsonb_build_object('installationId', i.id, 'generation', i.generation));
  return jsonb_build_object('installationId', i.id, 'status', 'revoked');
end $$;

create function public.sadid_revoke_installation(p_installation uuid, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.revoke_installation(p_installation, p_reason)
$$;
revoke all on function sadid_private.revoke_installation(uuid, text) from public, anon, service_role;
grant execute on function sadid_private.revoke_installation(uuid, text) to authenticated;
revoke all on function public.sadid_revoke_installation(uuid, text) from public, anon, service_role;
grant execute on function public.sadid_revoke_installation(uuid, text) to authenticated;
