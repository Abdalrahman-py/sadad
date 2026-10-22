-- The admin name written to audit rows.
create function sadid_private.jwt_admin_actor() returns text
language sql stable security invoker set search_path = '' as $$
  select coalesce(
    nullif((select sadid_private.jwt_claims()) ->> 'email', ''),
    (select sadid_private.jwt_claims()) ->> 'sub'
  )
$$;
revoke all on function sadid_private.jwt_admin_actor() from public, anon;
grant execute on function sadid_private.jwt_admin_actor() to authenticated, sadid_support_reader;
