import { createClient } from "supabase";
import { fail, makeApi } from "./handler.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL");
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!supabaseUrl || !serviceRoleKey) throw new Error("Supabase Edge Function environment is missing its project credentials.");

// The service-role client is created only inside this server-side function. Never send its key to Android or the admin page.
const db: any = createClient(supabaseUrl, serviceRoleKey, { auth: { persistSession: false, autoRefreshToken: false } });
const api = makeApi({ db, now: Date.now });

Deno.serve(async (request: Request) => {
  try { return await api.route(request); }
  catch (error) { return fail(error); }
});
