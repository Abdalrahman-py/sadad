-- Devices waiting for approval, newest first; q matches part of the label, staff name or store name.
create function sadid_private.pending_installations(
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
  v_query text := nullif(lower(trim(coalesce(p_query, ''))), '');
  v_rows jsonb;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if p_after is not null and jsonb_typeof(p_after) <> 'object' then raise exception 'invalid_input'; end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'installationId', t.id,
    'storeId', t.store_id,
    'storeName', t.store_name,
    'generation', t.generation,
    'label', t.label,
    'staffName', t.staff_name,
    'permissions', t.permissions,
    'status', t.status,
    'createdAt', t.created_at
  ) order by t.created_at desc, t.id desc), '[]'::jsonb) into v_rows
  from (
    select i.*, s.name as store_name
    from sadid_private.installations i
    join public.stores s on s.id = i.store_id
    where i.status = 'pending'
      and (p_after is null or (i.created_at, i.id) < ((p_after ->> 'at')::timestamptz, (p_after ->> 'id')::uuid))
      and (v_query is null
        or strpos(lower(i.label), v_query) > 0
        or strpos(lower(i.staff_name), v_query) > 0
        or strpos(lower(s.name), v_query) > 0)
    order by i.created_at desc, i.id desc
    limit v_limit + 1
  ) t;

  if jsonb_array_length(v_rows) > v_limit then
    return jsonb_build_object(
      'installations', v_rows - v_limit,
      'nextCursor', jsonb_build_object(
        'at', v_rows -> (v_limit - 1) -> 'createdAt',
        'id', v_rows -> (v_limit - 1) -> 'installationId'
      )
    );
  end if;
  return jsonb_build_object('installations', v_rows, 'nextCursor', null);
end $$;

create function public.sadid_pending_installations(
  p_query text default null,
  p_after jsonb default null,
  p_limit integer default 50
) returns jsonb
language sql stable security invoker set search_path = '' as $$
  select sadid_private.pending_installations(p_query, p_after, p_limit)
$$;
revoke all on function sadid_private.pending_installations(text, jsonb, integer) from public, anon, service_role;
grant execute on function sadid_private.pending_installations(text, jsonb, integer) to authenticated;
revoke all on function public.sadid_pending_installations(text, jsonb, integer) from public, anon, service_role;
grant execute on function public.sadid_pending_installations(text, jsonb, integer) to authenticated;
