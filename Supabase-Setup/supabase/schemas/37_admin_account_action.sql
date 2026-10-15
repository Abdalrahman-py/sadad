-- archive, unarchive, end-sessions or revoke-all for one store.
create function sadid_private.admin_account_action(p_store bigint, p_action text, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor_name text;
  account public.stores;
begin
  if sadid_private.jwt_admin_role() is distinct from 'admin' then raise exception 'admin_authorization_required'; end if;
  actor_name := sadid_private.jwt_admin_actor();
  if p_action not in ('archive', 'unarchive', 'end-sessions', 'revoke-all') or length(trim(coalesce(p_reason, ''))) not between 1 and 2000 then
    raise exception 'invalid_input';
  end if;

  select * into account from public.stores where id = p_store for update;
  if not found then raise exception 'store_not_found'; end if;

  if p_action = 'unarchive' then
    if account.status <> 'deleted' then raise exception 'store_not_archived'; end if;
    update public.stores set status = 'suspended', suspend_until = null, revision = revision + 1 where id = p_store;
  else
    if p_action = 'archive' then
      update public.stores set status = 'deleted', suspend_until = null, revision = revision + 1 where id = p_store;
    end if;
    if p_action in ('archive', 'revoke-all') then
      update sadid_private.installations
      set status = 'revoked', revoked_at = clock_timestamp(), reason = p_reason
      where store_id = p_store and status <> 'revoked';
    end if;
    delete from auth.sessions where user_id in (select user_id from sadid_private.store_memberships where store_id = p_store);
    delete from sadid_private.capabilities where store_id = p_store;
  end if;

  insert into sadid_private.control_events(store_id, actor, kind, reason, details)
  values (p_store, actor_name, 'account.' || p_action, p_reason, '{}');
  return jsonb_build_object('storeId', p_store, 'action', p_action);
end $$;

create function public.sadid_admin_account_action(p_store bigint, p_action text, p_reason text) returns jsonb
language sql security invoker set search_path = '' as $$
  select sadid_private.admin_account_action(p_store, p_action, p_reason)
$$;
revoke all on function sadid_private.admin_account_action(bigint, text, text) from public, anon, service_role;
grant execute on function sadid_private.admin_account_action(bigint, text, text) to authenticated;
revoke all on function public.sadid_admin_account_action(bigint, text, text) from public, anon, service_role;
grant execute on function public.sadid_admin_account_action(bigint, text, text) to authenticated;
