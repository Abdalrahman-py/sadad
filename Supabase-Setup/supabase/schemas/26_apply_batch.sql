-- Applies up to 100 signed commands in one transaction; one receipt each.
create function sadid_private.apply_batch(p_commands jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  c sadid_private.capabilities;
  cmd jsonb;
  results jsonb := '[]'::jsonb;
begin
  c := sadid_private.current_capability();
  if c.store_id is null or c.scope <> 'write' or c.request is distinct from p_commands then
    raise exception 'authorization_required';
  end if;
  if jsonb_typeof(p_commands) <> 'array' or jsonb_array_length(p_commands) not between 1 and 100 then
    raise exception 'invalid_input';
  end if;
  perform sadid_private.lock_authorization();
  c := sadid_private.current_capability();
  if c.store_id is null then raise exception 'authorization_required'; end if;

  for cmd in select jsonb_array_elements(p_commands) loop
    results := results || jsonb_build_array(sadid_private.apply_one(cmd, c));
  end loop;
  return results;
end $$;
alter function sadid_private.apply_batch(jsonb) owner to sadid_ledger_executor;
revoke all on function sadid_private.apply_batch(jsonb) from public, anon, service_role;
grant execute on function sadid_private.apply_batch(jsonb) to authenticated;

create function public.sadid_apply_batch(p_commands jsonb) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.apply_batch(p_commands)
$$;
revoke all on function public.sadid_apply_batch(jsonb) from public, anon, service_role;
grant execute on function public.sadid_apply_batch(jsonb) to authenticated;
