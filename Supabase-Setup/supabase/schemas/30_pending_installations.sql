-- Devices waiting for approval.
create function sadid_private.pending_installations() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'installationId', i.id,
      'storeId', i.store_id,
      'generation', i.generation,
      'label', i.label,
      'staffName', i.staff_name,
      'permissions', i.permissions,
      'status', i.status,
      'createdAt', i.created_at
    ))
    from sadid_private.installations i
    where i.status = 'pending'
  ), '[]'::jsonb);
end $$;

create function public.sadid_pending_installations() returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.pending_installations()
$$;
revoke all on function sadid_private.pending_installations() from public, anon, service_role;
grant execute on function sadid_private.pending_installations() to authenticated;
revoke all on function public.sadid_pending_installations() from public, anon, service_role;
grant execute on function public.sadid_pending_installations() to authenticated;
