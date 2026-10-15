-- Applies one signed command.
create function sadid_private.apply(p_command jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare c sadid_private.capabilities;
begin
  c := sadid_private.current_capability();
  if c.store_id is null or c.scope <> 'write' or c.request is distinct from p_command then
    raise exception 'authorization_required';
  end if;
  perform sadid_private.lock_authorization();
  c := sadid_private.current_capability();
  if c.store_id is null then raise exception 'authorization_required'; end if;
  return sadid_private.apply_one(p_command, c);
end $$;
alter function sadid_private.apply(jsonb) owner to sadid_ledger_executor;
revoke all on function sadid_private.apply(jsonb) from public, anon, service_role;
grant execute on function sadid_private.apply(jsonb) to authenticated;

create function public.sadid_apply(p_command jsonb) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.apply(p_command)
$$;
revoke all on function public.sadid_apply(jsonb) from public, anon, service_role;
grant execute on function public.sadid_apply(jsonb) to authenticated;
