-- The caller's store, read under the caller's JWT and the own_membership policy (null for admins).
create function public.sadid_my_store() returns bigint
language sql stable security invoker set search_path = '' as $$
  select store_id from sadid_private.store_memberships where user_id = (select auth.uid())
$$;
revoke all on function public.sadid_my_store() from public, anon;
grant execute on function public.sadid_my_store() to authenticated;
