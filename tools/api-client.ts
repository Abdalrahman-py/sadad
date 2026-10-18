// Shared pieces for the Deno tools that drive a Supabase project's `api` function: environment
// reading, sign-in with the temporary-password change, a fresh installation, signed requests,
// enrollment and the approval wait. Importing this module has no side effects.
import {
  proofText,
  sha256,
} from "../Supabase-Setup/supabase/functions/api/proof.ts";

const encoder = new TextEncoder();

/** Reads a required environment variable; exits 2 with a message when it is unset. */
export function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) {
    console.error(`Set ${name}.`);
    Deno.exit(2);
  }
  return value;
}

export function fail(step: string, detail: unknown): never {
  console.error(`FAIL ${step}: ${JSON.stringify(detail)}`);
  Deno.exit(1);
}

export function projectUrl(): string {
  return env("SADAD_SMOKE_URL").replace(/\/+$/, "");
}

export function publishableKey(): string {
  return env("SADAD_SMOKE_PUBLISHABLE_KEY");
}

export function apiBase(): string {
  return `${projectUrl()}/functions/v1/api`;
}

export function approvalSeconds(): number {
  return Number(Deno.env.get("SADAD_SMOKE_APPROVAL_SECONDS") ?? "600");
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export interface Device {
  installationId: string;
  privateKey: CryptoKey;
  publicKey: { kty: string; crv: string; x: string; y: string };
}

/** A fresh P-256 installation. The key is extractable so a caller can save and reuse it. */
export async function newDevice(): Promise<Device> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const jwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  return {
    installationId: crypto.randomUUID(),
    privateKey: pair.privateKey,
    publicKey: { kty: "EC", crv: "P-256", x: jwk.x!, y: jwk.y! },
  };
}

/** Rebuilds an installation from a saved private JWK (see seed-store.ts). */
export async function deviceFromJwk(
  installationId: string,
  jwk: JsonWebKey,
): Promise<Device> {
  const privateKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  return {
    installationId,
    privateKey,
    publicKey: { kty: "EC", crv: "P-256", x: jwk.x!, y: jwk.y! },
  };
}

export async function signIn(
  secret = env("SADAD_SMOKE_PASSWORD"),
): Promise<string> {
  const res = await fetch(`${projectUrl()}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { apikey: publishableKey(), "content-type": "application/json" },
    body: JSON.stringify({
      email: `${env("SADAD_SMOKE_USERNAME")}@stores.sadad.invalid`,
      password: secret,
    }),
  });
  const body = await res.json();
  if (!res.ok || typeof body.access_token !== "string") {
    fail("sign in", { status: res.status, body });
  }
  return body.access_token;
}

export function forcePasswordChange(jwt: string): boolean {
  const payload = jwt.split(".")[1] ?? "";
  const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
  const claims = JSON.parse(
    new TextDecoder().decode(Uint8Array.from(json, (ch) => ch.charCodeAt(0))),
  );
  return claims?.app_metadata?.force_password_change === true;
}

/** Signs in, changing a temporary password first, and returns the usable JWT. */
export async function authenticate(): Promise<string> {
  let jwt = await signIn();
  console.log("signed in");
  if (forcePasswordChange(jwt)) {
    const newPassword = env("SADAD_SMOKE_NEW_PASSWORD");
    const changed = await fetch(`${apiBase()}/v1/me/password`, {
      method: "POST",
      headers: {
        apikey: publishableKey(),
        Authorization: `Bearer ${jwt}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        currentPassword: env("SADAD_SMOKE_PASSWORD"),
        newPassword,
      }),
    });
    const body = await changed.json();
    if (changed.status !== 200) {
      fail("change password", { status: changed.status, body });
    }
    jwt = await signIn(newPassword);
    console.log("password changed");
  }
  return jwt;
}

export async function signed(
  jwt: string,
  device: Device,
  method: "GET" | "POST",
  route: string,
  body?: unknown,
  // deno-lint-ignore no-explicit-any
): Promise<{ status: number; body: any }> {
  const raw = body === undefined ? "" : JSON.stringify(body);
  const time = String(Date.now());
  const nonce = crypto.randomUUID();
  const text = proofText(
    method,
    route,
    await sha256(raw),
    time,
    nonce,
    device.installationId,
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      device.privateKey,
      encoder.encode(text),
    ),
  );
  const res = await fetch(`${apiBase()}${route}`, {
    method,
    headers: {
      apikey: publishableKey(),
      Authorization: `Bearer ${jwt}`,
      "content-type": "application/json",
      "x-sadad-installation": device.installationId,
      "x-sadad-time": time,
      "x-sadad-nonce": nonce,
      "x-sadad-signature": base64Url(signature),
    },
    ...(method === "POST" ? { body: raw } : {}),
  });
  return { status: res.status, body: await res.json() };
}

export interface Installation {
  installationId: string;
  generation: number;
  status: string;
  label: string;
  staffName: string;
  permissions: Record<string, boolean>;
}

export async function enroll(
  jwt: string,
  device: Device,
  label: string,
): Promise<Installation> {
  const enrolled = await signed(jwt, device, "POST", "/v1/mobile/enroll", {
    publicKey: device.publicKey,
    label,
  });
  if (enrolled.status !== 200) fail("enroll", enrolled);
  return enrolled.body.installation;
}

export interface Changes {
  epoch: string;
  cursor: number;
  nextCursor: number;
  hasMore: boolean;
  events: { operationId: string }[];
}

/**
 * Polls changes?after=0 until an admin approves the pending installation, then returns the
 * first page (its epoch and cursor are what the caller needs).
 */
export async function waitForApproval(
  jwt: string,
  device: Device,
): Promise<Changes> {
  let first = await signed(jwt, device, "GET", "/v1/mobile/changes?after=0");
  const deadline = Date.now() + approvalSeconds() * 1000;
  while (
    first.status === 401 &&
    first.body.code === "installation_or_session_revoked"
  ) {
    if (Date.now() > deadline) fail("waiting for approval", first);
    await new Promise((resolve) => setTimeout(resolve, 5000));
    first = await signed(jwt, device, "GET", "/v1/mobile/changes?after=0");
  }
  if (first.status !== 200) fail("changes after=0", first);
  return first.body.changes;
}
