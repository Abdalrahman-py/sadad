-- Store and installation permissions for one operation type.
create function sadid_private.operation_allowed(p_kind text) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce(
    (s.permissions ->> case
      when p_kind like 'contact.%' then 'contacts'
      when p_kind like 'debt.%' then 'debts'
      when p_kind like 'payment.%' then 'payments'
      else 'invalid'
    end)::boolean,
    true
  )
  and coalesce(
    (i.permissions ->> case
      when p_kind in ('payment.create', 'payment.reverse') then 'registerPayments'
      when p_kind in ('contact.archive', 'contact.restore') then 'deleteContacts'
      when p_kind = 'debt.correct' then 'deleteRecords'
      else 'allowed'
    end)::boolean,
    true
  )
  from sadid_private.current_capability() c
  join public.stores s on s.id = c.store_id
  join sadid_private.installations i on i.id = c.installation_id and i.store_id = c.store_id and i.generation = c.generation
$$;
alter function sadid_private.operation_allowed(text) owner to sadid_auth_reader;
revoke all on function sadid_private.operation_allowed(text) from public, anon, authenticated, service_role;
grant execute on function sadid_private.operation_allowed(text) to sadid_ledger_executor;
