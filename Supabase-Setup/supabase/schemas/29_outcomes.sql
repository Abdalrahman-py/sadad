-- Receipts for up to 100 operation ids.
create function public.sadid_outcomes(p_ids uuid[]) returns jsonb
language plpgsql stable security invoker set search_path = '' as $$
declare c sadid_private.capabilities;
begin
  c := sadid_private.current_capability();
  if c.store_id is null or c.scope not in ('outcomes', 'read') or coalesce(array_length(p_ids, 1), 0) > 100 then
    raise exception 'authorization_required';
  end if;
  return coalesce((select jsonb_agg(result) from public.sadid_operations where store_id = c.store_id and operation_id = any(p_ids)), '[]'::jsonb);
end $$;
revoke all on function public.sadid_outcomes(uuid[]) from public, anon, service_role;
grant execute on function public.sadid_outcomes(uuid[]) to authenticated;
