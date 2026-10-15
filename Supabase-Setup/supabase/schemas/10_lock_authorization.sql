-- Serializes ledger writes per store and pins the installation and session.
create function sadid_private.lock_authorization() returns void
language plpgsql security definer set search_path = '' as $$
declare c sadid_private.capabilities;
begin
  c := sadid_private.current_capability();
  if c.store_id is null then raise exception 'authorization_required'; end if;
  perform 1 from public.stores where id = c.store_id for update;
  perform 1 from sadid_private.installations where id = c.installation_id for share;
  perform 1 from auth.sessions where id = c.auth_session_id and user_id = c.user_id for share;
end $$;
revoke all on function sadid_private.lock_authorization() from public, anon, authenticated, service_role;
grant execute on function sadid_private.lock_authorization() to sadid_ledger_executor;
