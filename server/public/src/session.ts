// Supabase Auth sign-in for admin and support users. The api checks the JWT itself; the role read
// here only decides what the UI shows.
import { AuthClient } from "@supabase/auth-js";
import { PUBLISHABLE_KEY, SUPABASE_URL } from "./config";
import { ApiFailure, failure, networkFailure } from "./errors";

export type Role = "admin" | "support";

export interface Caller {
  jwt: string;
  email: string;
  role: Role | null;
  forcePasswordChange: boolean;
}

/** What the app needs from Auth; tests pass a fake. */
export interface SessionPort {
  current(): Promise<Caller | null>;
  signIn(email: string, password: string): Promise<Caller>;
  signOut(): Promise<void>;
}

function decodeClaims(jwt: string): Record<string, unknown> {
  const payload = jwt.split(".")[1] ?? "";
  try {
    const binary = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const text = new TextDecoder().decode(Uint8Array.from(binary, (ch) => ch.charCodeAt(0)));
    const claims = JSON.parse(text);
    return claims && typeof claims === "object" ? claims : {};
  } catch {
    return {};
  }
}

/** The same claims the api reads: app_metadata.role and app_metadata.force_password_change. */
export function callerFromJwt(jwt: string): Caller {
  const claims = decodeClaims(jwt);
  const meta = (claims.app_metadata ?? {}) as Record<string, unknown>;
  const role = meta.role === "admin" || meta.role === "support" ? meta.role : null;
  return {
    jwt,
    email: typeof claims.email === "string" ? claims.email : "",
    role,
    forcePasswordChange: meta.force_password_change === true,
  };
}

/** Refuses a caller the admin routes would refuse, before any view loads. */
export function checkCaller(caller: Caller): Caller {
  if (!caller.role) throw failure("admin_authorization_required", 403);
  if (caller.forcePasswordChange) throw failure("password_change_required", 403);
  return caller;
}

export function supabaseSession(): SessionPort {
  const auth = new AuthClient({
    url: `${SUPABASE_URL}/auth/v1`,
    headers: { apikey: PUBLISHABLE_KEY },
    storageKey: "sadad-admin-auth",
    // The tab's session only: closing the tab signs out, as the old dashboard did.
    storage: window.sessionStorage,
    autoRefreshToken: true,
    persistSession: true,
    detectSessionInUrl: false,
  });
  return {
    async current() {
      const { data } = await auth.getSession();
      return data.session ? callerFromJwt(data.session.access_token) : null;
    },
    async signIn(email, password) {
      let result: Awaited<ReturnType<typeof auth.signInWithPassword>>;
      try {
        result = await auth.signInWithPassword({ email, password });
      } catch {
        throw networkFailure();
      }
      const { data, error } = result;
      if (error || !data.session) {
        if (error?.code === "invalid_credentials") {
          throw new ApiFailure("invalid_credentials", "البريد أو كلمة المرور غير صحيحة.", 400);
        }
        throw error?.status ? failure("temporary_service_failure", error.status) : networkFailure();
      }
      return callerFromJwt(data.session.access_token);
    },
    async signOut() {
      await auth.signOut({ scope: "local" }).catch(() => undefined);
    },
  };
}
