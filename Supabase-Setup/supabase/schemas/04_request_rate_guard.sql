-- At most 300 signed requests per installation per minute.
create function sadid_private.request_rate_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(new.installation_id::text, 310));
  if (select count(*) from sadid_private.request_nonces where installation_id = new.installation_id and created_at > clock_timestamp() - interval '1 minute') >= 300 then
    raise exception 'request_rate_limited';
  end if;
  -- A proof is accepted up to MAX_CLOCK_SKEW_MS (5 minutes, functions/api/proof.ts) either side of its time, so a nonce must outlive its first use by 10 minutes; 15 leaves a margin.
  delete from sadid_private.request_nonces where installation_id = new.installation_id and created_at < clock_timestamp() - interval '15 minutes';
  return new;
end $$;
revoke all on function sadid_private.request_rate_guard() from public, anon, authenticated, service_role;
create trigger sadid_request_rate before insert on sadid_private.request_nonces for each row execute function sadid_private.request_rate_guard();
