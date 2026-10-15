import { createClient } from "@supabase/supabase-js";
import { createApp, DEFAULT_ALLOWED_ORIGINS } from "./app.ts";
import { makeSupabaseLookupStoreId } from "./auth.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const publishableKey = Deno.env.get("SUPABASE_ANON_KEY")!;

const rawOrigins = Deno.env.get("SADAD_ALLOWED_ORIGINS");
const allowedOrigins = rawOrigins
  ? rawOrigins
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
  : DEFAULT_ALLOWED_ORIGINS;

const authClient = createClient(url, publishableKey, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const app = createApp({
  allowedOrigins,
  verifyClaims: (jwt) => authClient.auth.getClaims(jwt),
  lookupStoreId: makeSupabaseLookupStoreId((jwt) =>
    createClient(url, publishableKey, {
      global: { headers: { Authorization: `Bearer ${jwt}` } },
      auth: { persistSession: false, autoRefreshToken: false },
    })
  ),
});

Deno.serve(app.fetch);
