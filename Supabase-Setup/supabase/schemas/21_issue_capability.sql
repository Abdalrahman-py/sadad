-- A 60-second token the api issues after it verifies a device proof.
create function sadid_private.issue_capability(
  p_user uuid,
  p_session uuid,
  p_installation uuid,
  p_nonce uuid,
  p_scope text,
  p_request jsonb default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  i sadid_private.installations;
  s public.stores;
  v_token text;
begin
  if p_scope not in ('read', 'write', 'lease') then raise exception 'invalid_scope'; end if;
  select inst.* into i
  from sadid_private.installations inst
  join sadid_private.store_memberships m on m.store_id = inst.store_id and m.user_id = p_user
  join auth.sessions a on a.id = p_session and a.user_id = p_user and (a.not_after is null or a.not_after > clock_timestamp())
  where inst.id = p_installation and inst.status = 'active';
  if not found then raise exception 'installation_or_session_revoked'; end if;

  select * into s from public.stores where id = i.store_id;
  if s.status <> 'active'
     or s.subscription_mode = 'paused'
     or (s.subscription_mode = 'timed' and coalesce(s.subscription_expires_at, 0) <= (extract(epoch from clock_timestamp()) * 1000)::bigint) then
    raise exception 'store_suspended';
  end if;

  if p_scope = 'write' and (p_request is null or octet_length(p_request::text) > 524288) then
    raise exception 'invalid_input';
  end if;

  begin
    insert into sadid_private.request_nonces(installation_id, nonce) values (p_installation, p_nonce);
  exception when unique_violation then
    raise exception 'proof_replayed';
  end;

  delete from sadid_private.capabilities
  where store_id = i.store_id and installation_id = p_installation and expires_at < clock_timestamp();

  v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into sadid_private.capabilities(
    token_hash, user_id, auth_session_id, store_id, installation_id, generation, scope, request, expires_at
  ) values (
    pg_catalog.sha256(pg_catalog.convert_to(v_token, 'UTF8')),
    p_user, p_session, i.store_id, p_installation, i.generation, p_scope, p_request, clock_timestamp() + interval '60 seconds'
  );
  return jsonb_build_object('token', v_token, 'expiresIn', 60);
end $$;

create function public.sadid_issue_capability(
  p_user uuid,
  p_session uuid,
  p_installation uuid,
  p_nonce uuid,
  p_scope text,
  p_request jsonb default null
) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.issue_capability(p_user, p_session, p_installation, p_nonce, p_scope, p_request)
$$;
revoke all on function sadid_private.issue_capability(uuid, uuid, uuid, uuid, text, jsonb), public.sadid_issue_capability(uuid, uuid, uuid, uuid, text, jsonb) from public, anon, authenticated;
grant execute on function sadid_private.issue_capability(uuid, uuid, uuid, uuid, text, jsonb), public.sadid_issue_capability(uuid, uuid, uuid, uuid, text, jsonb) to service_role;
