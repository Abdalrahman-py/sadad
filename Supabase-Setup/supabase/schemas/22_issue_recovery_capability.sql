-- A write capability for commands listed in an approved recovery permit.
create function sadid_private.issue_recovery_capability(
  p_user uuid,
  p_session uuid,
  p_installation uuid,
  p_nonce uuid,
  p_request jsonb,
  p_permit uuid
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb;
begin
  if jsonb_typeof(p_request) = 'object' then
    if not exists (
      select 1 from sadid_private.recovery_permits p
      where p.id = p_permit and p.user_id = p_user and p.new_installation_id = p_installation
        and p.expires_at > clock_timestamp()
        and exists (select 1 from jsonb_array_elements(p.manifest) x where x = p_request)
    ) then
      raise exception 'recovery_not_authorized';
    end if;
  elsif jsonb_typeof(p_request) = 'array' then
    if jsonb_array_length(p_request) = 0 or not exists (
      select 1 from sadid_private.recovery_permits p
      where p.id = p_permit and p.user_id = p_user and p.new_installation_id = p_installation
        and p.expires_at > clock_timestamp()
        and not exists (
          select 1 from jsonb_array_elements(p_request) req
          where not exists (select 1 from jsonb_array_elements(p.manifest) m where m = req)
        )
    ) then
      raise exception 'recovery_not_authorized';
    end if;
  else
    raise exception 'recovery_not_authorized';
  end if;

  result := sadid_private.issue_capability(p_user, p_session, p_installation, p_nonce, 'write', p_request);
  update sadid_private.capabilities
  set recovery_permit_id = p_permit
  where token_hash = pg_catalog.sha256(pg_catalog.convert_to(result ->> 'token', 'UTF8'));
  return result;
end $$;

create function public.sadid_issue_recovery_capability(
  p_user uuid,
  p_session uuid,
  p_installation uuid,
  p_nonce uuid,
  p_request jsonb,
  p_permit uuid
) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.issue_recovery_capability(p_user, p_session, p_installation, p_nonce, p_request, p_permit)
$$;
revoke all on function sadid_private.issue_recovery_capability(uuid, uuid, uuid, uuid, jsonb, uuid), public.sadid_issue_recovery_capability(uuid, uuid, uuid, uuid, jsonb, uuid) from public, anon, authenticated;
grant execute on function sadid_private.issue_recovery_capability(uuid, uuid, uuid, uuid, jsonb, uuid), public.sadid_issue_recovery_capability(uuid, uuid, uuid, uuid, jsonb, uuid) to service_role;
