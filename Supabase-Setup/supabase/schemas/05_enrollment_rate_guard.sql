-- At most 20 pending installations per store.
create function sadid_private.enrollment_rate_guard() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.status = 'pending' and (select count(*) from sadid_private.installations where store_id = new.store_id and status = 'pending') >= 20 then
    raise exception 'enrollment_rate_limited';
  end if;
  return new;
end $$;
revoke all on function sadid_private.enrollment_rate_guard() from public, anon, authenticated, service_role;
create trigger sadid_enrollment_rate before insert on sadid_private.installations for each row execute function sadid_private.enrollment_rate_guard();
