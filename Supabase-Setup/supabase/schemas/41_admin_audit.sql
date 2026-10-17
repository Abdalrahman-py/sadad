-- The admin audit trail, newest first. p_source: control (admin actions) or access (audited ledger reads).
-- p_store narrows it to one store; q matches part of the actor, kind or reason.
create function sadid_private.admin_audit(
  p_source text,
  p_store bigint default null,
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
  v_query text := nullif(lower(trim(coalesce(p_query, ''))), '');
  v_rows jsonb;
  v_next jsonb;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if p_after is not null and jsonb_typeof(p_after) <> 'object' then raise exception 'invalid_input'; end if;

  if p_source = 'control' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', t.id,
      'storeId', t.store_id,
      'actor', t.actor,
      'kind', t.kind,
      'reason', t.reason,
      'details', t.details,
      'createdAt', t.created_at
    ) order by t.id desc), '[]'::jsonb) into v_rows
    from (
      select * from sadid_private.control_events e
      where (p_store is null or e.store_id = p_store)
        and (p_after is null or e.id < (p_after ->> 'id')::bigint)
        and (v_query is null
          or strpos(lower(e.actor), v_query) > 0
          or strpos(lower(e.kind), v_query) > 0
          or strpos(lower(e.reason), v_query) > 0)
      order by e.id desc
      limit v_limit + 1
    ) t;
  elsif p_source = 'access' then
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', t.id,
      'storeId', t.store_id,
      'actor', t.actor,
      'reason', t.reason,
      'outcome', t.outcome,
      'recordCounts', t.record_counts,
      'createdAt', t.created_at
    ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
    from (
      select * from sadid_private.admin_access_events e
      where (p_store is null or e.store_id = p_store)
        and (p_after is null or (e.created_at, e.id) < ((p_after ->> 'at')::timestamptz, (p_after ->> 'id')::uuid))
        and (v_query is null or strpos(lower(e.actor), v_query) > 0 or strpos(lower(e.reason), v_query) > 0)
      order by e.created_at desc, e.id desc
      limit v_limit + 1
    ) t;
  else
    raise exception 'invalid_input';
  end if;

  if jsonb_array_length(v_rows) > v_limit then
    v_rows := v_rows - v_limit;
    v_next := case p_source
      when 'control' then jsonb_build_object('id', v_rows -> (v_limit - 1) -> 'id')
      else jsonb_build_object('at', v_rows -> (v_limit - 1) -> 'createdAt', 'id', v_rows -> (v_limit - 1) -> 'id')
    end;
  end if;
  return jsonb_build_object('events', v_rows, 'nextCursor', v_next);
end $$;

create function public.sadid_admin_audit(
  p_source text,
  p_store bigint default null,
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.admin_audit(p_source, p_store, p_query, p_after, p_limit)
$$;
revoke all on function sadid_private.admin_audit(text, bigint, text, jsonb, integer) from public, anon, service_role;
grant execute on function sadid_private.admin_audit(text, bigint, text, jsonb, integer) to authenticated;
revoke all on function public.sadid_admin_audit(text, bigint, text, jsonb, integer) from public, anon, service_role;
grant execute on function public.sadid_admin_audit(text, bigint, text, jsonb, integer) to authenticated;
