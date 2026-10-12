-- Vocabulary: the app offers نقدي / بنكي / محفظة جوال / محفظة بال بي. The ledger accepted only
-- cash|bank|wallet, so a Jawwal or PalPay payment came back as invalid_input and the store could not
-- record money it had actually taken. The ledger now speaks the same five methods the till prints.
--
-- The contract is patched where it is defined -- in the database, which AGENTS.md makes the reference
-- -- rather than restated here: a copy of a 100-line function in a migration is a second source that
-- drifts. The patch asserts it found the list, so it cannot silently do nothing.
alter table public.sadid_payments drop constraint if exists sadid_payments_method_check;
alter table public.sadid_payments add constraint sadid_payments_method_check
  check (method in ('cash','bank','wallet','wallet_jawwal','wallet_palpay'));

do $$
declare definition text; patched text;
  old_list constant text := 'not in (''cash'',''bank'',''wallet'')';
  new_list constant text := 'not in (''cash'',''bank'',''wallet'',''wallet_jawwal'',''wallet_palpay'')';
begin
  definition := pg_get_functiondef('sadid_private.apply(jsonb)'::regprocedure);
  if position(old_list in definition) = 0 then
    if position(new_list in definition) > 0 then return; end if;  -- already applied
    raise exception 'the deployed operation contract has no method list to widen';
  end if;
  patched := replace(definition, old_list, new_list);
  if patched = definition then raise exception 'the method list was not widened'; end if;
  execute patched;
end $$;
