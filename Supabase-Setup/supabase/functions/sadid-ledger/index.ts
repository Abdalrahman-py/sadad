import { createClient } from 'supabase';
import { makeHandler } from './handler.ts';
import { createECDH,createHash } from 'node:crypto';
const url=Deno.env.get('SUPABASE_URL')!;
const serviceKey=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const publicKey=Deno.env.get('SUPABASE_ANON_KEY')!;
const db=createClient(url,serviceKey,{auth:{persistSession:false,autoRefreshToken:false}});
// مفتاح توقيع مشتق ومفصول بالغرض عن مفتاح الخدمة؛ لا يُرسل السر أو المفتاح الخاص.
const leaseCurve=createECDH('prime256v1');
leaseCurve.setPrivateKey(createHash('sha256').update('sadid-offline-lease-v1\0').update(serviceKey).digest());
const leasePoint=leaseCurve.getPublicKey(undefined,'uncompressed');
const b64=(bytes:Uint8Array)=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const leaseJwk:JsonWebKey={kty:'EC',crv:'P-256',x:b64(leasePoint.subarray(1,33)),y:b64(leasePoint.subarray(33)),d:b64(leaseCurve.getPrivateKey())};
const leaseSigningKey=await crypto.subtle.importKey('jwk',leaseJwk,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);
Deno.serve(makeHandler({db,leaseSigningKey,leaseJwk,newUserClient:(jwt,capability)=>
 createClient(url,publicKey,{global:{headers:{Authorization:`Bearer ${jwt}`,'X-Sadad-Capability':capability}},auth:{persistSession:false,autoRefreshToken:false}})
}));
