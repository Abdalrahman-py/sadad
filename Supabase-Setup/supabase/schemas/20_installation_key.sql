-- The public key the api verifies a device proof against.
create function sadid_private.installation_key(p_user uuid, p_session uuid, p_installation uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'publicKey', i.public_key,
    'storeId', i.store_id,
    'generation', i.generation,
    'status', i.status,
    'label', i.label,
    'staffName', i.staff_name,
    'permissions', i.permissions
  )
  from sadid_private.installations i
  join sadid_private.store_memberships m on m.store_id = i.store_id and m.user_id = p_user
  join auth.sessions a on a.id = p_session and a.user_id = p_user and (a.not_after is null or a.not_after > statement_timestamp())
  where i.id = p_installation and i.status = 'active'
$$;

create function public.sadid_installation_key(p_user uuid, p_session uuid, p_installation uuid) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.installation_key(p_user, p_session, p_installation)
$$;
revoke all on function sadid_private.installation_key(uuid, uuid, uuid), public.sadid_installation_key(uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function sadid_private.installation_key(uuid, uuid, uuid), public.sadid_installation_key(uuid, uuid, uuid) to service_role;
