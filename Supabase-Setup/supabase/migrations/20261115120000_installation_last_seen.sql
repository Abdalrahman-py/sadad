-- Installations record when they last connected (ticket 198). issue_capability stamps
-- last_seen_at at most once a minute; admin_controls returns it as lastSeenAt.
-- Same signatures, so create or replace keeps each function's owner and grants.
alter table sadid_private.installations add column last_seen_at timestamptz;

create or replace function sadid_private.issue_capability(
  p_user uuid,
  p_session uuid,
  p_installation uuid,
  p_nonce uuid,
  p_scope text,
  p_request jsonb default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  i sadid_private.installations;
  s public.stores;
  v_token text;
begin
  if p_scope not in ('read', 'write', 'lease') then raise exception 'invalid_scope'; end if;
  select inst.* into i
  from sadid_private.installations inst
  join sadid_private.store_memberships m on m.store_id = inst.store_id and m.user_id = p_user
  join auth.sessions a on a.id = p_session and a.user_id = p_user and (a.not_after is null or a.not_after > clock_timestamp())
  where inst.id = p_installation and inst.status = 'active';
  if not found then raise exception 'installation_or_session_revoked'; end if;

  select * into s from public.stores where id = i.store_id;
  if s.status <> 'active'
     or s.subscription_mode = 'paused'
     or (s.subscription_mode = 'timed' and coalesce(s.subscription_expires_at, 0) <= (extract(epoch from clock_timestamp()) * 1000)::bigint) then
    raise exception 'store_suspended';
  end if;

  if p_scope = 'write' and (p_request is null or octet_length(p_request::text) > 524288) then
    raise exception 'invalid_input';
  end if;

  begin
    insert into sadid_private.request_nonces(installation_id, nonce) values (p_installation, p_nonce);
  exception when unique_violation then
    raise exception 'proof_replayed';
  end;

  delete from sadid_private.capabilities
  where store_id = i.store_id and installation_id = p_installation and expires_at < clock_timestamp();

  v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into sadid_private.capabilities(
    token_hash, user_id, auth_session_id, store_id, installation_id, generation, scope, request, expires_at
  ) values (
    pg_catalog.sha256(pg_catalog.convert_to(v_token, 'UTF8')),
    p_user, p_session, i.store_id, p_installation, i.generation, p_scope, p_request, clock_timestamp() + interval '60 seconds'
  );
  -- At most one write a minute per installation keeps the capability path cheap.
  update sadid_private.installations set last_seen_at = clock_timestamp()
  where id = p_installation and (last_seen_at is null or last_seen_at < clock_timestamp() - interval '1 minute');
  return jsonb_build_object('token', v_token, 'expiresIn', 60);
end $$;

create or replace function sadid_private.admin_controls(p_store bigint) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  s public.stores;
  v_history boolean;
  v_ledger boolean;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  select * into s from public.stores where id = p_store;
  if not found then raise exception 'store_not_found'; end if;
  v_history := exists (select 1 from sadid_private.installations where store_id = p_store);
  v_ledger := exists (select 1 from public.sadid_ledger_state where store_id = p_store);
  return jsonb_build_object(
    'store', sadid_private.store_json(s),
    'hasDeviceHistory', v_history,
    'ledgerInitialized', v_ledger,
    'canDelete', not v_history and not v_ledger,
    'installations', coalesce((
      select jsonb_agg(jsonb_build_object(
        'installationId', id,
        'storeId', store_id,
        'generation', generation,
        'label', label,
        'staffName', staff_name,
        'permissions', permissions,
        'status', status,
        'createdAt', created_at,
        'approvedAt', approved_at,
        'approvedBy', approved_by,
        'revokedAt', revoked_at,
        'reason', reason,
        'lastSeenAt', last_seen_at
      ) order by generation desc)
      from sadid_private.installations where store_id = p_store
    ), '[]'::jsonb),
    'recoveryPermits', coalesce((
      select jsonb_agg(jsonb_build_object(
        'permitId', id,
        'oldInstallationId', old_installation_id,
        'newInstallationId', new_installation_id,
        'approvedBy', approved_by,
        'reason', reason,
        'expiresAt', expires_at,
        'createdAt', created_at
      ) order by created_at desc)
      from (select * from sadid_private.recovery_permits where store_id = p_store order by created_at desc limit 20) t
    ), '[]'::jsonb)
  );
end $$;
