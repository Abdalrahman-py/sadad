-- The capability named by the x-sadad-capability header, if it is still valid for this caller.
create function sadid_private.current_capability() returns sadid_private.capabilities
language sql stable security definer set search_path = '' as $$
  select c from sadid_private.capabilities c
  join sadid_private.store_memberships m on m.user_id = c.user_id and m.store_id = c.store_id
  join sadid_private.installations i on i.id = c.installation_id and i.store_id = c.store_id and i.generation = c.generation and i.status = 'active'
  join public.stores s on s.id = c.store_id
    and s.status = 'active'
    and s.subscription_mode <> 'paused'
    and (s.subscription_mode <> 'timed' or s.subscription_expires_at > (extract(epoch from statement_timestamp()) * 1000)::bigint)
  where c.token_hash = pg_catalog.sha256(pg_catalog.convert_to(coalesce(nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-sadad-capability', ''), 'UTF8'))
    and c.user_id = (select auth.uid())
    and c.auth_session_id = ((select auth.jwt()) ->> 'session_id')::uuid
    and coalesce(((select auth.jwt()) -> 'app_metadata' ->> 'force_password_change')::boolean, false) = false
    and c.expires_at > statement_timestamp()
    and sadid_private.session_active(c.user_id, c.auth_session_id)
  limit 1
$$;
alter function sadid_private.current_capability() owner to sadid_auth_reader;
revoke all on function sadid_private.current_capability() from public, anon, service_role;
grant execute on function sadid_private.current_capability() to authenticated, sadid_ledger_executor;
