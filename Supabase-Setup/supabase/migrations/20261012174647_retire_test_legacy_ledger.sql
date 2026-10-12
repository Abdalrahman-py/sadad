-- Test-only data retirement. Deploy and verify the cleaned sadad-api before applying.
-- RESTRICT fails on unexpected dependencies; signed ledger and identity tables stay intact.
begin;
set local lock_timeout = '5s';

drop function public.sadad_restore_store_backup_checked(bigint,bigint,bigint,bigint,jsonb,bigint) restrict;
drop function public.sadad_restore_contact_archive_checked(bigint,bigint,bigint,jsonb,bigint) restrict;
drop function public.sadad_restore_store_backup(bigint,bigint,bigint,jsonb,bigint) restrict;
drop function public.sadad_restore_contact_archive(bigint,bigint,jsonb,bigint) restrict;
drop function public.sadad_commit_store_snapshot(bigint,bigint,jsonb,jsonb,jsonb,jsonb,text,text,bigint) restrict;
drop function public.sadad_admin_store_list() restrict;
drop function public.sadad_admin_overview() restrict;
drop function public.sadad_sync_identity_sequences() restrict;

drop table public.payments restrict;
drop table public.debts restrict;
drop table public.contacts restrict;
drop table public.store_backups restrict;
drop table public.contact_archives restrict;
drop table public.sync_receipts restrict;
drop table public.audit_events restrict;
drop function sadid_private.block_legacy_write() restrict;

alter table public.stores
 drop column whatsapp_enabled restrict,
 drop column whatsapp_number_id restrict,
 drop column whatsapp_token_enc restrict,
 drop column whatsapp_template_name restrict,
 drop column whatsapp_template_language restrict;

commit;
