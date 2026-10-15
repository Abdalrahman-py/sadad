-- One store's devices, control events, audit reads and recovery permits.
create function sadid_private.admin_controls(p_store bigint) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if not exists (select 1 from public.stores where id = p_store) then raise exception 'store_not_found'; end if;
  return jsonb_build_object(
    'hasDeviceHistory', exists (select 1 from sadid_private.installations where store_id = p_store),
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
    'controlEvents', coalesce((
      select jsonb_agg(to_jsonb(t))
      from (select id, actor, kind, reason, created_at from sadid_private.control_events where store_id = p_store order by id desc limit 100) t
    ), '[]'::jsonb),
    'accessEvents', coalesce((
      select jsonb_agg(to_jsonb(t))
      from (select id, actor, reason, record_counts, created_at from sadid_private.admin_access_events where store_id = p_store order by created_at desc limit 50) t
    ), '[]'::jsonb),
    'recoveryPermits', coalesce((
      select jsonb_agg(to_jsonb(t))
      from (select id, old_installation_id, new_installation_id, approved_by, reason, expires_at, created_at from sadid_private.recovery_permits where store_id = p_store order by created_at desc limit 20) t
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
