-- Lets a replacement device resend listed commands of a revoked one.
create function sadid_private.approve_recovery(p_old uuid, p_new uuid, p_manifest jsonb, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_actor text;
  old_i sadid_private.installations;
  new_i sadid_private.installations;
  p sadid_private.recovery_permits;
  cmd jsonb;
  v_user uuid;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  v_actor := sadid_private.jwt_admin_actor();
  if jsonb_typeof(p_manifest) <> 'array' or jsonb_array_length(p_manifest) not between 1 and 1000 or octet_length(p_manifest::text) > 4000000 or length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then
    raise exception 'invalid_manifest';
  end if;

  select * into old_i from sadid_private.installations where id = p_old and status = 'revoked';
  select * into new_i from sadid_private.installations where id = p_new and status = 'active';
  if old_i.id is null or new_i.id is null or old_i.store_id <> new_i.store_id or old_i.generation >= new_i.generation then
    raise exception 'invalid_transfer';
  end if;

  perform 1 from public.stores where id = new_i.store_id for update;
  if not exists (select 1 from sadid_private.installations where id = p_new and status = 'active') then
    raise exception 'installation_revoked';
  end if;

  select user_id into v_user from sadid_private.store_memberships where store_id = new_i.store_id;
  for cmd in select jsonb_array_elements(p_manifest) loop
    if cmd ->> 'type' not in ('contact.create', 'debt.create', 'payment.create')
       or cmd ->> 'originInstallationId' is distinct from p_old::text
       or (cmd ->> 'originGeneration')::integer is distinct from old_i.generation
       or cmd ->> 'schemaVersion' is distinct from '1'
       or cmd ->> 'operationId' is null then
      raise exception 'invalid_manifest';
    end if;
  end loop;

  if (select count(distinct x ->> 'operationId') from jsonb_array_elements(p_manifest) x) <> jsonb_array_length(p_manifest) then
    raise exception 'duplicate_operation_id';
  end if;

  insert into sadid_private.recovery_permits(
    store_id, user_id, old_installation_id, new_installation_id, old_generation,
    manifest, manifest_hash, approved_by, reason, expires_at, epoch
  ) values (
    new_i.store_id, v_user, p_old, p_new, old_i.generation,
    p_manifest, pg_catalog.sha256(pg_catalog.convert_to(p_manifest::text, 'UTF8')),
    v_actor, p_reason, clock_timestamp() + interval '24 hours',
    (select epoch from public.sadid_ledger_state where store_id = new_i.store_id)
  ) returning * into p;

  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (
    p.store_id, v_actor, 'recovery.approved', p_reason,
    jsonb_build_object(
      'permitId', p.id,
      'oldInstallationId', p_old,
      'newInstallationId', p_new,
      'manifestHash', encode(p.manifest_hash, 'hex'),
      'count', jsonb_array_length(p_manifest)
    )
  );
  return jsonb_build_object('permitId', p.id, 'expiresAt', p.expires_at, 'manifestHash', encode(p.manifest_hash, 'hex'));
end $$;

create function public.sadid_approve_recovery(p_old uuid, p_new uuid, p_manifest jsonb, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.approve_recovery(p_old, p_new, p_manifest, p_reason)
$$;
revoke all on function sadid_private.approve_recovery(uuid, uuid, jsonb, text) from public, anon, service_role;
grant execute on function sadid_private.approve_recovery(uuid, uuid, jsonb, text) to authenticated;
revoke all on function public.sadid_approve_recovery(uuid, uuid, jsonb, text) from public, anon, service_role;
grant execute on function public.sadid_approve_recovery(uuid, uuid, jsonb, text) to authenticated;
