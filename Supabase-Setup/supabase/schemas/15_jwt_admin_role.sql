-- admin or support from app_metadata.role, only while the Auth session is live.
create function sadid_private.jwt_admin_role() returns text
language sql stable security invoker set search_path = '' as $$
  select case
    when (select auth.uid()) is not null
      and coalesce(((select auth.jwt()) -> 'app_metadata' ->> 'force_password_change')::boolean, false) = false
      and ((select auth.jwt()) -> 'app_metadata' ->> 'role') in ('admin', 'support')
      and sadid_private.session_active((select auth.uid()), ((select auth.jwt()) ->> 'session_id')::uuid)
    then (select auth.jwt()) -> 'app_metadata' ->> 'role'
    else null
  end
$$;
revoke all on function sadid_private.jwt_admin_role() from public, anon;
grant execute on function sadid_private.jwt_admin_role() to authenticated, sadid_support_reader;
