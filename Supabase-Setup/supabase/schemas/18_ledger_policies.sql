-- RLS on the ledger tables: a store reads and writes its own rows; support reads one audited store.
create policy audited_support_insert on sadid_private.admin_access_events
  for insert to sadid_support_reader
  with check (
    actor = (select sadid_private.jwt_admin_actor())
    and store_id = (select sadid_private.support_store())
  );

do $$ declare t text; begin
  foreach t in array array['sadid_ledger_state', 'sadid_contacts', 'sadid_debts', 'sadid_payments', 'sadid_adjustments', 'sadid_reversals', 'sadid_operations', 'sadid_events'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('alter table public.%I force row level security', t);
    execute format('revoke all on public.%I from public, anon, authenticated, service_role', t);
    execute format('grant select on public.%I to authenticated, sadid_ledger_executor, sadid_support_reader', t);
    execute format('grant insert on public.%I to sadid_ledger_executor', t);
    execute format('create policy store_read on public.%I for select to authenticated, sadid_ledger_executor using (store_id = (select sadid_private.authorized_store()))', t);
    execute format('create policy store_insert on public.%I for insert to sadid_ledger_executor with check (store_id = (select sadid_private.authorized_store()))', t);
    execute format('create policy audited_support_read on public.%I for select to sadid_support_reader using (store_id = (select sadid_private.support_store()))', t);
  end loop;
  foreach t in array array['sadid_ledger_state', 'sadid_contacts'] loop
    execute format('grant update on public.%I to sadid_ledger_executor', t);
    execute format('create policy store_update on public.%I for update to sadid_ledger_executor using (store_id = (select sadid_private.authorized_store())) with check (store_id = (select sadid_private.authorized_store()))', t);
  end loop;
end $$;
