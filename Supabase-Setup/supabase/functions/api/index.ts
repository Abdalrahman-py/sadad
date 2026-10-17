import { createClient } from "@supabase/supabase-js";
import { createECDH, createHash } from "node:crypto";
import { createApp, DEFAULT_ALLOWED_ORIGINS } from "./app.ts";
import { makeSupabaseLookupStoreId } from "./auth.ts";
import type { LeaseSigner } from "./mobile.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const publishableKey = Deno.env.get("SUPABASE_ANON_KEY")!;
// Service role stays in this function: installation keys, capabilities, sadid_create_store and
// the Auth admin API (store users), each after the caller was checked.
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const rawOrigins = Deno.env.get("SADAD_ALLOWED_ORIGINS");
const allowedOrigins = rawOrigins
  ? rawOrigins
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
  : DEFAULT_ALLOWED_ORIGINS;

const noSession = { persistSession: false, autoRefreshToken: false };

const authClient = createClient(url, publishableKey, { auth: noSession });
const serviceClient = createClient(url, serviceKey, { auth: noSession });

const userClient = (jwt: string, capability?: string) =>
  createClient(url, publishableKey, {
    global: {
      headers: {
        Authorization: `Bearer ${jwt}`,
        ...(capability ? { "x-sadad-capability": capability } : {}),
      },
    },
    auth: noSession,
  });

// The lease key is derived from the service key and separated by purpose (ported from
// sadid-ledger), so there is no extra secret to deploy. Only the public half leaves here.
async function leaseSigner(): Promise<LeaseSigner> {
  const curve = createECDH("prime256v1");
  curve.setPrivateKey(
    createHash("sha256")
      .update("sadid-offline-lease-v1\0")
      .update(serviceKey)
      .digest(),
  );
  const point = curve.getPublicKey(undefined, "uncompressed");
  const b64 = (bytes: Uint8Array) =>
    btoa(String.fromCharCode(...bytes))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
  const publicKey: JsonWebKey = {
    kty: "EC",
    crv: "P-256",
    x: b64(point.subarray(1, 33)),
    y: b64(point.subarray(33)),
  };
  const signingKey = await crypto.subtle.importKey(
    "jwk",
    { ...publicKey, d: b64(curve.getPrivateKey()) },
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  return {
    projectRef: new URL(url).hostname.split(".")[0],
    signingKey,
    publicKey,
  };
}

// Signs in on a client of its own, so no user session is ever held by the shared authClient,
// then ends that session at once.
async function verifyPassword(
  email: string,
  password: string,
): Promise<boolean> {
  const { data, error } = await createClient(url, publishableKey, {
    auth: noSession,
  }).auth.signInWithPassword({ email, password });
  if (error) {
    if (error.code === "invalid_credentials") return false;
    throw error;
  }
  await serviceClient.auth.admin.signOut(data.session.access_token, "local");
  return true;
}

const app = createApp({
  allowedOrigins,
  verifyClaims: (jwt) => authClient.auth.getClaims(jwt),
  lookupStoreId: makeSupabaseLookupStoreId(userClient),
  mobile: {
    serviceDb: serviceClient,
    userDb: userClient,
    lease: await leaseSigner(),
  },
  admin: {
    userDb: (jwt) => userClient(jwt),
    serviceDb: serviceClient,
    authAdmin: serviceClient.auth.admin,
  },
  account: {
    authAdmin: serviceClient.auth.admin,
    verifyPassword,
  },
});

Deno.serve(app.fetch);
