import { createClient } from "@supabase/supabase-js";
import { createApp, DEFAULT_ALLOWED_ORIGINS } from "./app.ts";
import { authSignInError } from "./account.ts";
import { handleError } from "./errors.ts";
import type { LeaseSigner } from "./mobile.ts";
import { routePath } from "./mobile.ts";
import { captureUnexpected, initSentry } from "./scrub.ts";

// Crash reporting starts only when the project environment carries a DSN; otherwise nothing is sent.
initSentry(Deno.env.get("SENTRY_DSN"));

function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

const url = env("SUPABASE_URL");
// The platform's publishable and secret API keys, both named "default"; the legacy anon and
// service_role keys stop working at the end of 2026.
const publishableKey: string =
  JSON.parse(env("SUPABASE_PUBLISHABLE_KEYS")).default;
// The secret key stays in this function: installation keys, capabilities, sadid_create_store and
// the Auth admin API (store users), each after the caller was checked.
const serviceKey: string = JSON.parse(env("SUPABASE_SECRET_KEYS")).default;
if (!publishableKey || !serviceKey) {
  throw new Error(
    'SUPABASE_PUBLISHABLE_KEYS and SUPABASE_SECRET_KEYS need a "default" key',
  );
}

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

// SADAD_LEASE_PRIVATE_JWK is a P-256 private JWK set as a function secret. It is its own secret so
// rotating an API key never changes the public key the app build pins. Only the public half leaves.
async function leaseSigner(): Promise<LeaseSigner> {
  const { kty, crv, x, y, d } = JSON.parse(
    env("SADAD_LEASE_PRIVATE_JWK"),
  ) as JsonWebKey;
  if (kty !== "EC" || crv !== "P-256" || !x || !y || !d) {
    throw new Error("SADAD_LEASE_PRIVATE_JWK must be a P-256 private JWK");
  }
  const publicKey: JsonWebKey = { kty, crv, x, y };
  const signingKey = await crypto.subtle.importKey(
    "jwk",
    { ...publicKey, d },
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
  requestId: string,
): Promise<boolean> {
  const { data, error } = await createClient(url, publishableKey, {
    auth: noSession,
  }).auth.signInWithPassword({ email, password });
  if (error) {
    if (error.code === "invalid_credentials") return false;
    throw authSignInError(error.code ?? "") ?? error;
  }
  const ended = await serviceClient.auth.admin.signOut(
    data.session.access_token,
    "local",
  );
  if (ended.error) {
    // The throwaway session may stay live; the password was still correct, so let the caller in.
    console.error(JSON.stringify({ requestId, error: "signOut failed" }));
  }
  return true;
}

const app = createApp({
  allowedOrigins,
  verifyClaims: (jwt) => authClient.auth.getClaims(jwt),
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

// The same handler createApp registered (contract responses via handleError); this wrapper only
// adds the Sentry report for 5xx-class unexpected errors, never for contract 4xx refusals.
app.onError((err, c) => {
  captureUnexpected(err, routePath(c.req.raw));
  return handleError(err, c);
});

Deno.serve(app.fetch);
