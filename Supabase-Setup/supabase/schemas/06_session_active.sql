-- True while the Supabase Auth session exists and has not expired.
create function sadid_private.session_active(p_user uuid, p_session uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from auth.sessions
    where id = p_session and user_id = p_user and (not_after is null or not_after > statement_timestamp())
  )
$$;
revoke all on function sadid_private.session_active(uuid, uuid) from public, anon, authenticated, service_role;
grant execute on function sadid_private.session_active(uuid, uuid) to sadid_auth_reader, sadid_support_reader;
