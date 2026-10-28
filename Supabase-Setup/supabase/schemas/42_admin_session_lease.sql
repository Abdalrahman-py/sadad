-- One signed-in admin or support account at a time. The first request of an Auth session claims the
-- account; a second session is refused until the holder signs out or stays silent for the lease
-- window. The browser is never trusted with this: every /v1/admin/* request asks the routine before
-- it reads or writes anything, and the routine reads the session id from the JWT, not the body.
create table sadid_private.admin_session_lease (
  user_id uuid primary key references auth.users(id) on delete cascade,
  session_id uuid not null,
  claimed_at timestamptz not null default now(),
  seen_at timestamptz not null default now()
);

-- True when the caller's Auth session holds the account; false when another live session does.
-- A lease that has not been renewed for ten minutes is stale: a browser closed without signing out
-- must not keep the account until someone clears it by hand. The window is repeated in
-- contract/errors.json's session_in_use message, which tells the reader what to expect.
create function sadid_private.claim_admin_session() returns boolean
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := (((select sadid_private.jwt_claims()) ->> 'sub'))::uuid;
  v_session uuid := nullif((select sadid_private.jwt_claims()) ->> 'session_id', '')::uuid;
  r sadid_private.admin_session_lease;
begin
  if sadid_private.jwt_admin_role() is null then raise exception 'admin_authorization_required'; end if;
  if v_session is null then raise exception 'invalid_input'; end if;
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

-- Frees the account at sign-out. Reads only sub and session_id, so it still works while
-- app_metadata.force_password_change blocks every other admin routine.
create function sadid_private.release_admin_session() returns void
language plpgsql volatile security definer set search_path = '' as $$
declare
  v_user uuid := (((select sadid_private.jwt_claims()) ->> 'sub'))::uuid;
  v_session uuid := nullif((select sadid_private.jwt_claims()) ->> 'session_id', '')::uuid;
begin
  if v_user is null or v_session is null then raise exception 'admin_authorization_required'; end if;
  delete from sadid_private.admin_session_lease
    where user_id = v_user and session_id = v_session;
end $$;

create function public.sadid_admin_session_claim() returns boolean
language sql volatile security invoker set search_path = '' as $$
  select sadid_private.claim_admin_session()
$$;

create function public.sadid_admin_session_release() returns void
language sql volatile security invoker set search_path = '' as $$
  select sadid_private.release_admin_session()
$$;

revoke all on function sadid_private.claim_admin_session() from public, anon, service_role;
grant execute on function sadid_private.claim_admin_session() to authenticated;
revoke all on function sadid_private.release_admin_session() from public, anon, service_role;
grant execute on function sadid_private.release_admin_session() to authenticated;
revoke all on function public.sadid_admin_session_claim() from public, anon, service_role;
grant execute on function public.sadid_admin_session_claim() to authenticated;
revoke all on function public.sadid_admin_session_release() from public, anon, service_role;
grant execute on function public.sadid_admin_session_release() to authenticated;
