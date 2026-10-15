-- At most 300 signed requests per installation per minute.
create function sadid_private.request_rate_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(new.installation_id::text, 310));
  if (select count(*) from sadid_private.request_nonces where installation_id = new.installation_id and created_at > clock_timestamp() - interval '1 minute') >= 300 then
    raise exception 'request_rate_limited';
  end if;
  return new;
end $$;
revoke all on function sadid_private.request_rate_guard() from public, anon, authenticated, service_role;
create trigger sadid_request_rate before insert on sadid_private.request_nonces for each row execute function sadid_private.request_rate_guard();
