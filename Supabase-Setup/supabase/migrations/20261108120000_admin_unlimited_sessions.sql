-- An account with app_metadata.unlimited_sessions = true skips the one-session admin lease.
create or replace function sadid_private.claim_admin_session() returns boolean
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := (((select sadid_private.jwt_claims()) ->> 'sub'))::uuid;
  v_session uuid := nullif((select sadid_private.jwt_claims()) ->> 'session_id', '')::uuid;
  r sadid_private.admin_session_lease;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if v_session is null then raise exception 'invalid_input'; end if;
  -- app_metadata is written only by the service role, so a user cannot grant this to themselves.
  if coalesce(((select sadid_private.jwt_claims()) -> 'app_metadata' ->> 'unlimited_sessions')::boolean, false) then
    return true;
  end if;
  -- on conflict do nothing, then read for update: two first requests of the same session cannot
  -- both insert, and the row lock serialises a claim racing an expiry takeover.
  insert into sadid_private.admin_session_lease (user_id, session_id) values (v_user, v_session)
    on conflict (user_id) do nothing;
  select * into r from sadid_private.admin_session_lease where user_id = v_user for update;
  if r.session_id = v_session then
    update sadid_private.admin_session_lease set seen_at = now() where user_id = v_user;
    return true;
  end if;
  if r.seen_at > now() - interval '10 minutes' then
    return false;
  end if;
  update sadid_private.admin_session_lease
    set session_id = v_session, claimed_at = now(), seen_at = now()
    where user_id = v_user;
  return true;
end $$;
