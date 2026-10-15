-- A recovered command may carry the epoch its permit was issued in.
create function sadid_private.recovery_epoch_allowed(p_epoch uuid, p_command jsonb) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from sadid_private.recovery_permits p
    where p.id = c.recovery_permit_id
      and p.store_id = c.store_id
      and p.user_id = c.user_id
      and p.new_installation_id = c.installation_id
      and p.epoch = p_epoch
      and p.expires_at > statement_timestamp()
      and exists (select 1 from jsonb_array_elements(p.manifest) x where x = p_command)
  )
  from sadid_private.current_capability() c
$$;
alter function sadid_private.recovery_epoch_allowed(uuid, jsonb) owner to sadid_auth_reader;
revoke all on function sadid_private.recovery_epoch_allowed(uuid, jsonb) from public, anon, authenticated, service_role;
grant execute on function sadid_private.recovery_epoch_allowed(uuid, jsonb) to sadid_ledger_executor;
