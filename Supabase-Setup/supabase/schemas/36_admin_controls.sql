-- One store with its devices and recovery permits; the audit trail is paged by sadid_admin_audit.
create function sadid_private.admin_controls(p_store bigint) returns jsonb
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
        'reason', reason
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

create function public.sadid_admin_controls(p_store bigint) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.admin_controls(p_store)
$$;
revoke all on function sadid_private.admin_controls(bigint) from public, anon, service_role;
grant execute on function sadid_private.admin_controls(bigint) to authenticated;
revoke all on function public.sadid_admin_controls(bigint) from public, anon, service_role;
grant execute on function public.sadid_admin_controls(bigint) to authenticated;
