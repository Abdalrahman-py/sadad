-- Ledger history and audit rows are append-only.
create function sadid_private.immutable_history() returns trigger
language plpgsql set search_path = '' as $$
begin
  raise exception using errcode = 'P0001', message = 'history_is_immutable';
end $$;
revoke all on function sadid_private.immutable_history() from public, anon, authenticated, service_role;

do $$ declare t text; begin
  foreach t in array array['sadid_debts', 'sadid_payments', 'sadid_offsets', 'sadid_adjustments', 'sadid_reversals', 'sadid_operations', 'sadid_events'] loop
    execute format('create trigger immutable before update or delete on public.%I for each row execute function sadid_private.immutable_history()', t);
  end loop;
  create trigger no_delete before delete on public.sadid_contacts for each row execute function sadid_private.immutable_history();
  create trigger no_delete before delete on public.sadid_ledger_state for each row execute function sadid_private.immutable_history();
  create trigger immutable before update or delete on sadid_private.control_events for each row execute function sadid_private.immutable_history();
  create trigger immutable before update or delete on sadid_private.recovery_permits for each row execute function sadid_private.immutable_history();
  create trigger immutable before update or delete on sadid_private.admin_access_events for each row execute function sadid_private.immutable_history();
end $$;
