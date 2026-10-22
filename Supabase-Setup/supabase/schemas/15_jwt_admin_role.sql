-- admin or support from app_metadata.role, only while the Auth session is live.
create function sadid_private.jwt_admin_role() returns text
language sql stable security invoker set search_path = '' as $$
  select case
    when ((select sadid_private.jwt_claims()) ->> 'sub')::uuid is not null
      and coalesce(((select sadid_private.jwt_claims()) -> 'app_metadata' ->> 'force_password_change')::boolean, false) = false
      and ((select sadid_private.jwt_claims()) -> 'app_metadata' ->> 'role') in ('admin', 'support')
      and sadid_private.session_active(((select sadid_private.jwt_claims()) ->> 'sub')::uuid, ((select sadid_private.jwt_claims()) ->> 'session_id')::uuid)
    then (select sadid_private.jwt_claims()) -> 'app_metadata' ->> 'role'
    else null
  end
$$;
revoke all on function sadid_private.jwt_admin_role() from public, anon;
grant execute on function sadid_private.jwt_admin_role() to authenticated, sadid_support_reader;
