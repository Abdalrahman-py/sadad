-- Idempotency receipts for the Android batched delta sync (POST /mobile/sync-delta).
--
-- The app sends `batchId = <deviceId>:<outbox watermark>`. If the function commits a batch but
-- the response is lost, the device retries the same batchId; without a receipt the retry hits the
-- revision guard and reads as a conflict, which blocks that store's sync until a human intervenes.
-- The receipt lets the function answer the retry with the revision it already committed.
--
-- Mirrors the SQLite sync_receipts table of the legacy Node server.

create table public.sync_receipts (
  store_id bigint not null references public.stores(id) on delete cascade,
  device_id text not null,
  batch_id text not null,
  revision bigint not null,
  created_at bigint not null,
  primary key (store_id, device_id, batch_id)
);

create index sync_receipts_device_idx on public.sync_receipts(store_id, device_id, created_at desc);

alter table public.sync_receipts enable row level security;
revoke all on public.sync_receipts from public, anon, authenticated;
grant select, insert, delete on public.sync_receipts to service_role;
-- No client policies: only the Edge Function's service-role client touches this table.
