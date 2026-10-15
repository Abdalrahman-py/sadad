-- The one store an audited support read may see in this transaction.
create function sadid_private.support_store() returns bigint
language sql stable security invoker set search_path = '' as $$
  select case
    when sadid_private.jwt_admin_role() is not null
    then nullif(current_setting('sadid.support.store', true), '')::bigint
    else null
  end
$$;
revoke all on function sadid_private.support_store() from public, anon, authenticated, service_role;
grant execute on function sadid_private.support_store() to sadid_support_reader;
