-- A command must come from the calling installation, or from a revoked one under a recovery permit.
create function sadid_private.origin_allowed(p_command jsonb) returns boolean
language sql stable security definer set search_path = '' as $$
  select case
    when p_command ->> 'originInstallationId' = c.installation_id::text and (p_command ->> 'originGeneration')::integer = c.generation then true
    else exists (
      select 1 from sadid_private.recovery_permits p
      where p.id = c.recovery_permit_id
        and p.store_id = c.store_id
        and p.user_id = c.user_id
        and p.new_installation_id = c.installation_id
        and p.old_installation_id::text = p_command ->> 'originInstallationId'
        and p.old_generation = (p_command ->> 'originGeneration')::integer
        and p.expires_at > statement_timestamp()
        and exists (select 1 from jsonb_array_elements(p.manifest) cmd where cmd = p_command)
    )
  end
  from sadid_private.current_capability() c
$$;
alter function sadid_private.origin_allowed(jsonb) owner to sadid_auth_reader;
revoke all on function sadid_private.origin_allowed(jsonb) from public, anon, authenticated, service_role;
grant execute on function sadid_private.origin_allowed(jsonb) to sadid_ledger_executor;
