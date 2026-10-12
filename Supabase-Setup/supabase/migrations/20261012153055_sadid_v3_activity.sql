-- آخر ٢٠٠ عملية دفتر فقط؛ أحداث الدخول والإدارة لها سجل منفصل.
create function sadid_private.activity() returns jsonb
language plpgsql security definer set search_path='' as $$
declare c sadid_private.capabilities; result jsonb;
begin
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'read' then raise exception 'authorization_required'; end if;
 perform sadid_private.lock_authorization();
 c:=sadid_private.current_capability();
 if c.store_id is null or c.scope<>'read' then raise exception 'authorization_required'; end if;
 perform 1 from public.sadid_ledger_state where store_id=c.store_id for share;
 if not found then raise exception 'ledger_not_initialized'; end if;
 select coalesce(jsonb_agg(jsonb_build_object(
  'sequence',e.sequence,'operationId',e.operation_id,'kind',e.kind,
  'commandType',e.command->>'type','entityId',e.entity_id,'userId',e.user_id,
  'installationId',e.installation_id,'generation',e.generation,
  'createdAt',(extract(epoch from e.created_at)*1000)::bigint,
  'status',o.result->>'status','code',o.result->>'code'
 ) order by e.sequence desc),'[]'::jsonb) into result
 from (select * from public.sadid_events where store_id=c.store_id order by sequence desc limit 200) e
 join public.sadid_operations o on o.store_id=e.store_id and o.operation_id=e.operation_id;
 return result;
end $$;
alter function sadid_private.activity() owner to sadid_ledger_executor;
revoke all on function sadid_private.activity() from public,anon,authenticated,service_role;
grant execute on function sadid_private.activity() to authenticated;
create function public.sadid_activity() returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.activity() $$;
revoke all on function public.sadid_activity() from public,anon,authenticated,service_role;
grant execute on function public.sadid_activity() to authenticated;
