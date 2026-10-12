-- Editing a person's details had no operation on the signed ledger: the client's edit form changed the
-- local row and the next snapshot put the server's copy straight back — a silent revert the store would
-- read as the app losing their correction. This adds the missing operation.
--
-- Additive on purpose: one new branch, nothing else in the contract touched. The patch rewrites the
-- definition where it lives (the database is the reference per AGENTS.md) and asserts it found the
-- branch to insert before, so it cannot half-apply.
--
-- The version guard is the same one contact.archive uses: the client sends the version it last saw, and
-- a mismatch is a correction the store has to re-read rather than overwrite.
do $migration$
declare definition text; patched text;
  anchor constant text := 'elsif kind=''debt.create'' then';
  branch constant text := $branch$elsif kind='contact.update' then
   contact := (payload->>'contactId')::uuid;
   if contact is null then raise exception 'invalid_input'; end if;
   if length(trim(coalesce(payload->>'name',''))) not between 1 and 120
      or length(coalesce(payload->>'note',''))>2000 or length(coalesce(payload->>'category',''))>80
   then raise exception 'invalid_input'; end if;
   v_phone := regexp_replace(coalesce(payload->>'phone',''),'[[:space:]()\-]','','g');
   v_phone := translate(v_phone,'٠١٢٣٤٥٦٧٨٩','0123456789');
   if v_phone<>'' and v_phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'invalid_phone'; end if;
   if v_phone<>'' and exists(select 1 from public.sadid_contacts where store_id=c.store_id
        and sadid_contacts.phone=v_phone and sadid_contacts.id<>contact) then raise exception 'duplicate_phone'; end if;
   update public.sadid_contacts set name=trim(payload->>'name'), phone=v_phone,
     category=coalesce(payload->>'category',''), note=coalesce(payload->>'note',''), version=version+1
    where store_id=c.store_id and id=contact and not archived
      and version=coalesce((p_command->>'expectedVersion')::bigint,-1);
   if not found then raise exception 'entity_version_changed'; end if;
  $branch$;
begin
  definition := pg_get_functiondef('sadid_private.apply(jsonb)'::regprocedure);
  if position('contact.update' in definition) > 0 then return; end if;  -- already applied
  if position(anchor in definition) = 0 then
    raise exception 'the deployed operation contract has no debt.create branch to insert before';
  end if;
  patched := replace(definition, anchor, branch || anchor);
  if patched = definition then raise exception 'the operation contract was not changed'; end if;
  execute patched;
end $migration$;
