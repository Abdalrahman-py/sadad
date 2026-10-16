// Device proof: an installation signs each request with its P-256 key.
// The text must stay byte-identical to InstallationKey.proofText in the app.
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const encoder = new TextEncoder();
const MAX_CLOCK_SKEW_MS = 300_000;

export async function sha256(raw: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(raw));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function proofText(
  method: string,
  route: string,
  digest: string,
  time: string,
  nonce: string,
  installation: string,
): string {
  return [
    "sadid-v3",
    method.toUpperCase(),
    route,
    digest,
    time,
    nonce,
    installation,
  ].join("\n");
}

export interface ProofHeaders {
  installationId: string;
  nonce: string;
  time: string;
  signature: string;
}

/** The x-sadad-* headers, or null when any is missing or malformed. */
export function readProofHeaders(
  request: Request,
  now = Date.now(),
): ProofHeaders | null {
  const installationId = request.headers.get("x-sadad-installation") || "";
  const nonce = request.headers.get("x-sadad-nonce") || "";
  const time = request.headers.get("x-sadad-time") || "";
  const signature = request.headers.get("x-sadad-signature") || "";
  if (
    !uuid.test(installationId) ||
    !uuid.test(nonce) ||
    !/^\d{13}$/.test(time) ||
    Math.abs(now - Number(time)) > MAX_CLOCK_SKEW_MS ||
    signature.length > 100
  ) {
    return null;
  }
  return { installationId, nonce, time, signature };
}

export async function verifyProof(
  request: Request,
  route: string,
  raw: string,
  jwk: JsonWebKey,
  now = Date.now(),
): Promise<{ installationId: string; nonce: string } | null> {
  const headers = readProofHeaders(request, now);
  if (!headers) return null;
  if (jwk.kty !== "EC" || jwk.crv !== "P-256" || jwk.d) return null;
  try {
    const key = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    const bytes = Uint8Array.from(
      atob(headers.signature.replace(/-/g, "+").replace(/_/g, "/")),
      (c) => c.charCodeAt(0),
    );
    if (bytes.length !== 64) return null;
    const text = proofText(
      request.method,
      route,
      await sha256(raw),
      headers.time,
      headers.nonce,
      headers.installationId,
    );
    const valid = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      bytes,
      encoder.encode(text),
    );
    return valid
      ? { installationId: headers.installationId, nonce: headers.nonce }
      : null;
  } catch {
    return null;
  }
}
