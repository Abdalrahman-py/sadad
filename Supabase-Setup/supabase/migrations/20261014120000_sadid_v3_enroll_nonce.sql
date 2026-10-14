-- منع إعادة إرسال دليل تركيب الجهاز: يسجّل enroll رقم الإثبات مع إنشاء التركيب،
-- وإعادة الرقم نفسه تُرفض برمز محدد حتى داخل نافذة الدليل (٣٠٠ ثانية).
drop function public.sadid_enroll(uuid,uuid,text,uuid,jsonb);
drop function sadid_private.enroll(uuid,uuid,text,uuid,jsonb);
create function sadid_private.enroll(p_user uuid,p_session uuid,p_legacy_hash text,p_installation uuid,p_key jsonb,p_nonce uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare b public.sadad_auth_bindings; i sadid_private.installations; v_generation integer;
begin
 if p_key->>'kty' is distinct from 'EC' or p_key->>'crv' is distinct from 'P-256'
 or p_key ? 'd' or length(coalesce(p_key->>'x',''))<>43 or length(coalesce(p_key->>'y',''))<>43
 then raise exception 'invalid_public_key'; end if;
 select x.* into b from public.sadad_auth_bindings x
 join auth.sessions a on a.id=x.auth_session_id and a.user_id=x.user_id and (a.not_after is null or a.not_after>clock_timestamp())
 join public.sessions l on l.token_hash=x.session_hash and l.kind='store' and l.principal_id=x.store_id and l.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint
 join public.stores s on s.id=x.store_id and s.status='active' and not s.force_password_change
 where x.session_hash=p_legacy_hash and x.user_id=p_user and x.auth_session_id=p_session
 and x.expires_at>(extract(epoch from clock_timestamp())*1000)::bigint;
 if not found then raise exception 'authorization_required'; end if;
 perform 1 from public.stores where id=b.store_id for update;
 insert into sadid_private.store_memberships(user_id,store_id) values(p_user,b.store_id) on conflict(user_id) do nothing;
 if not exists(select 1 from sadid_private.store_memberships where user_id=p_user and store_id=b.store_id) then raise exception 'membership_conflict'; end if;
 select * into i from sadid_private.installations where id=p_installation;
 if found then
  if i.store_id<>b.store_id or i.public_key<>p_key then raise exception 'installation_key_changed'; end if;
  if i.status='revoked' then raise exception 'installation_revoked'; end if;
 else
  select coalesce(max(generation),0)+1 into v_generation from sadid_private.installations where store_id=b.store_id;
  insert into sadid_private.installations(id,store_id,generation,public_key,status) values(p_installation,b.store_id,v_generation,p_key,'pending') returning * into i;
 end if;
 -- الرقم الأحادي يُسجّل بعد وجود التركيب (قيد المفتاح الأجنبي) وبنفس المعاملة؛ ونقضه مبدئيًا مرفض.
 begin
  insert into sadid_private.request_nonces(installation_id,nonce) values(p_installation,p_nonce);
 exception when unique_violation then raise exception 'proof_replayed'; end;
 insert into sadid_private.installation_sessions(auth_session_id,user_id,store_id,installation_id,generation)
 values(p_session,p_user,b.store_id,p_installation,i.generation)
 on conflict(auth_session_id) do nothing;
 if not exists(select 1 from sadid_private.installation_sessions where auth_session_id=p_session and user_id=p_user and installation_id=p_installation and generation=i.generation) then raise exception 'session_installation_conflict'; end if;
 return jsonb_build_object('installationId',i.id,'generation',i.generation,'status',i.status);
end $$;

create function public.sadid_enroll(p_user uuid,p_session uuid,p_legacy_hash text,p_installation uuid,p_key jsonb,p_nonce uuid) returns jsonb language sql security invoker set search_path='' as $$ select sadid_private.enroll(p_user,p_session,p_legacy_hash,p_installation,p_key,p_nonce) $$;
revoke all on function sadid_private.enroll(uuid,uuid,text,uuid,jsonb,uuid),public.sadid_enroll(uuid,uuid,text,uuid,jsonb,uuid) from public,anon,authenticated;
grant execute on function sadid_private.enroll(uuid,uuid,text,uuid,jsonb,uuid),public.sadid_enroll(uuid,uuid,text,uuid,jsonb,uuid) to service_role;
