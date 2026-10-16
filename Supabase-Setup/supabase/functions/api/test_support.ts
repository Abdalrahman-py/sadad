import { createClient } from "@supabase/supabase-js";
import { proofText, sha256 } from "./proof.ts";

export function assert(
  condition: unknown,
  message: string,
): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

// Object keys in sorted order: jsonb does not keep the order a command was written in.
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [
        key,
        canonical((value as Record<string, unknown>)[key]),
      ]),
    );
  }
  return value;
}

export function assertEqual(actual: unknown, expected: unknown): void {
  if (
    JSON.stringify(canonical(actual)) !== JSON.stringify(canonical(expected))
  ) {
    throw new Error(
      `Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
  }
}

const encoder = new TextEncoder();

export function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export async function makeSignedJwt(
  privateKey: CryptoKey,
  kid: string,
  claims: Record<string, unknown>,
): Promise<string> {
  const header = base64Url(
    encoder.encode(JSON.stringify({ alg: "ES256", typ: "JWT", kid })),
  );
  const now = Math.floor(Date.now() / 1000);
  const payload = base64Url(
    encoder.encode(
      JSON.stringify({
        iss: "https://test.invalid/auth/v1",
        aud: "authenticated",
        iat: now,
        exp: now + 3600,
        role: "authenticated",
        ...claims,
      }),
    ),
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      privateKey,
      encoder.encode(`${header}.${payload}`),
    ),
  );
  return `${header}.${payload}.${base64Url(signature)}`;
}

/** An Auth signing key and a getClaims() that trusts only it. */
export async function createTestKeyPair() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const kid = "test-signing-key-1";
  const exported = await crypto.subtle.exportKey("jwk", pair.publicKey);
  const publicJwk = {
    ...exported,
    kty: "EC" as const,
    crv: "P-256",
    key_ops: ["verify"],
    ext: true,
    kid,
    alg: "ES256",
    use: "sig",
  };
  const supa = createClient("https://test.invalid", "sb_publishable_test", {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  return {
    pair,
    kid,
    verifyClaims: (jwt: string) =>
      supa.auth.getClaims(jwt, { jwks: { keys: [publicJwk] } }),
  };
}

/** The four x-sadad-* headers an installation sends, signed the way the app signs them. */
export async function signProof(
  privateKey: CryptoKey,
  request: {
    method: string;
    route: string;
    body: string;
    installationId: string;
    nonce?: string;
    time?: number;
  },
): Promise<Record<string, string>> {
  const nonce = request.nonce ?? crypto.randomUUID();
  const time = String(request.time ?? Date.now());
  const text = proofText(
    request.method,
    request.route,
    await sha256(request.body),
    time,
    nonce,
    request.installationId,
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      privateKey,
      encoder.encode(text),
    ),
  );
  return {
    "x-sadad-installation": request.installationId,
    "x-sadad-time": time,
    "x-sadad-nonce": nonce,
    "x-sadad-signature": base64Url(signature),
  };
}
