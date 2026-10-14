import { makeHandler } from './handler.ts';
import { proofText, sha256 } from './proof.ts';
const equal=(actual:unknown,expected:unknown)=>{if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);};

async function activityFixture() {
 const pair=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
 const publicKey=await crypto.subtle.exportKey('jwk',pair.publicKey);
 const user=crypto.randomUUID(),session=crypto.randomUUID(),installation=crypto.randomUUID();
 const jwt='test.'+btoa(JSON.stringify({session_id:session}))+'.signature';
 const route='/mobile/v3/activity',time=String(Date.now()),nonce=crypto.randomUUID();
 const raw=proofText('GET',route,await sha256(''),time,nonce,installation);
 const signature=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},pair.privateKey,new TextEncoder().encode(raw)));
 const request=new Request('https://test.invalid/functions/v1/sadid-ledger/api'+route,{headers:{authorization:'Bearer '+jwt,
  'x-sadad-installation':installation,'x-sadad-time':time,'x-sadad-nonce':nonce,'x-sadad-signature':btoa(String.fromCharCode(...signature))}});
 const events=[{sequence:4,commandType:'payment.create',userId:user,installationId:installation,generation:1}];
 const calls:string[]=[];
 const db={auth:{getUser:()=>Promise.resolve({data:{user:{id:user}},error:null})},rpc:(name:string,args:Record<string,unknown>)=>{
  calls.push(name);
  if(name==='sadid_installation_key')return Promise.resolve({data:{publicKey,storeId:1,generation:1},error:null});
  if(name==='sadid_issue_capability'){
   equal(args.p_scope,'read');equal(args.p_user,user);equal(args.p_session,session);equal(args.p_installation,installation);equal(args.p_nonce,nonce);
   return Promise.resolve({data:{token:'scoped-capability'},error:null});
  }
  throw new Error('Unexpected RPC '+name);
 }};
 const newUserClient=(bearer:string,ticket:string)=>{
  equal(bearer,jwt);equal(ticket,'scoped-capability');
  return {rpc:(name:string)=>{calls.push(name);equal(name,'sadid_activity');return Promise.resolve({data:events,error:null});}};
 };
 return {request,events,calls,handler:makeHandler({db,newUserClient,leaseSigningKey:pair.privateKey,leaseJwk:publicKey})};
}
Deno.test('signed activity uses a read capability and the authenticated RPC',async()=>{
 const f=await activityFixture(),response=await f.handler(f.request);
 equal(response.status,200);equal(await response.json(),{ok:true,events:f.events});
 equal(f.calls,['sadid_installation_key','sadid_issue_capability','sadid_activity']);
});
Deno.test('forged activity proof never issues a capability or reads events',async()=>{
 const f=await activityFixture();
 const headers=new Headers(f.request.headers);headers.set('x-sadad-signature','forged');
 const response=await f.handler(new Request(f.request.url,{headers}));
 equal(response.status,403);equal((await response.json()).code,'invalid_proof');
 equal(f.calls,['sadid_installation_key']);
});
Deno.test('sadid-ledger no longer serves admin login',async()=>{
 const handler=makeHandler({db:{},newUserClient:()=>{throw new Error('not called');},leaseSigningKey:undefined as unknown as CryptoKey,leaseJwk:{}});
 // Admin identity lives in sadad-api; with the stale-header form the ledger's own /admin/*
 // branch must answer 404, not fall through to an identity session.
 const response=await handler(new Request('https://test.invalid/functions/v1/sadid-ledger/api/admin/login',{method:'POST',headers:{'x-sadad-admin':'stale-token'},body:'{}'}));
 equal(response.status,404);equal((await response.json()).message,'المسار غير موجود.');
});
