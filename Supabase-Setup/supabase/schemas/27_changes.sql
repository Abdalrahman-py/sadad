-- Events after a cursor, in order, one page at a time (no row locks).
create function sadid_private.changes(p_after bigint default 0, p_limit integer default 200) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  c sadid_private.capabilities;
  st public.sadid_ledger_state;
  v_after bigint := greatest(coalesce(p_after, 0), 0);
  v_limit integer := least(greatest(coalesce(p_limit, 200), 1), 500);
  v_events jsonb;
  v_next_cursor bigint;
  v_has_more boolean;
begin
  c := sadid_private.current_capability();
  if c.store_id is null or c.scope <> 'read' then raise exception 'authorization_required'; end if;

  select * into st from public.sadid_ledger_state where store_id = c.store_id;
  if not found then raise exception 'ledger_not_initialized'; end if;

  with page as (
    select e.sequence, e.operation_id, e.kind, e.entity_id, e.user_id, e.installation_id,
           e.generation, e.command, e.created_at, o.result
    from public.sadid_events e
    join public.sadid_operations o on o.store_id = e.store_id and o.operation_id = e.operation_id
    where e.store_id = c.store_id and e.sequence > v_after
    order by e.sequence asc
    limit v_limit + 1
  ),
  bounded as (
    select * from page order by sequence asc limit v_limit
  )
  select
    coalesce(jsonb_agg(jsonb_build_object(
      'sequence', b.sequence,
      'operationId', b.operation_id,
      'kind', b.kind,
      'entityId', b.entity_id,
      'userId', b.user_id,
      'installationId', b.installation_id,
      'generation', b.generation,
      'command', b.command,
      'result', b.result,
      'createdAt', b.created_at
    ) order by b.sequence asc), '[]'::jsonb),
    coalesce((select max(sequence) from bounded), v_after),
    (select count(*) from page) > v_limit
  into v_events, v_next_cursor, v_has_more
  from bounded b;

  return jsonb_build_object(
    'schemaVersion', st.schema_version,
    'storeId', c.store_id,
    'epoch', st.epoch,
    'cursor', st.cursor,
    'nextCursor', v_next_cursor,
    'hasMore', coalesce(v_has_more, false),
    'currency', st.currency,
    'events', v_events
  );
end $$;
alter function sadid_private.changes(bigint, integer) owner to sadid_ledger_executor;
revoke all on function sadid_private.changes(bigint, integer) from public, anon, service_role;
grant execute on function sadid_private.changes(bigint, integer) to authenticated;

create function public.sadid_changes(p_after bigint default 0, p_limit integer default 200) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.changes(p_after, p_limit)
$$;
revoke all on function public.sadid_changes(bigint, integer) from public, anon, service_role;
grant execute on function public.sadid_changes(bigint, integer) to authenticated;
