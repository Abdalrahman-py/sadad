// Typed sample rows for the unit tests and the Playwright mock. The types come from
// contract/openapi.json, so a contract change that breaks these shapes fails `tsc`.
import type { AuditEvent, Overview, PendingInstallation, StoreDetail, StoreRow } from "../src/api";

export const STORE_ID = 7;
export const PENDING_ID = "2f0c1d6e-9b7a-4c3e-8d2f-5a6b7c8d9e01";
export const REVOKED_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

export const overview: Overview = {
  stores: 1,
  active: 1,
  suspended: 0,
  archived: 0,
  verified: 1,
  pendingDevices: 1,
};

export function storeRow(name = "بقالة الزاوية"): StoreRow {
  return {
    id: STORE_ID,
    name,
    username: "corner-shop",
    status: "active",
    archived: false,
    verified: true,
    debtorLimit: 300,
    allowedDevices: 2,
    permissions: { export: false },
    revision: 3,
    createdAt: Date.parse("2026-10-15T09:00:00Z"),
    subscriptionMode: "timed",
    subscriptionActive: true,
    subscriptionStartedAt: Date.parse("2026-10-15T09:00:00Z"),
    subscriptionExpiresAt: Date.parse("2026-11-14T09:00:00Z"),
    subscriptionRemainingMs: 20 * 86_400_000,
    activeDevices: 0,
    pendingDevices: 1,
    summary: {
      storeId: STORE_ID,
      initialized: true,
      contacts: 4,
      activeContacts: 4,
      debts: 3,
      openDebts: 2,
      debtTotalCents: 12_500,
      receivableCents: 12_500,
      payableCents: 0,
      payments: 1,
    },
  };
}

export function pending(storeName = "بقالة الزاوية"): PendingInstallation {
  return {
    installationId: PENDING_ID,
    storeId: STORE_ID,
    generation: 2,
    label: "Pixel",
    staffName: "أحمد",
    permissions: {},
    status: "pending",
    createdAt: "2026-10-16T08:00:00Z",
    storeName,
  };
}

export function detail(name = "بقالة الزاوية"): StoreDetail {
  const { summary: _summary, ...store } = storeRow(name);
  return {
    store,
    hasDeviceHistory: true,
    ledgerInitialized: true,
    canDelete: false,
    installations: [
      {
        ...pending(),
        approvedAt: null,
        approvedBy: null,
        revokedAt: null,
        reason: null,
      },
      {
        installationId: REVOKED_ID,
        storeId: STORE_ID,
        generation: 1,
        label: name,
        staffName: name,
        permissions: {},
        status: "revoked",
        createdAt: "2026-10-15T10:00:00Z",
        approvedAt: "2026-10-15T10:05:00Z",
        approvedBy: "admin@sadad.test",
        revokedAt: "2026-10-16T07:00:00Z",
        reason: name,
      },
    ],
    recoveryPermits: [],
  };
}

export function auditEvent(reason = "اعتماد الهاتف الجديد"): AuditEvent {
  return {
    id: 41,
    storeId: STORE_ID,
    actor: "admin@sadad.test",
    kind: "installation.approved",
    reason,
    createdAt: "2026-10-16T08:10:00Z",
  };
}

/** An unsigned JWT with the claims the dashboard reads (the api verifies the real one). */
export function jwtFor(role: "admin" | "support" | "store", forcePasswordChange = false): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return [
    encode({ alg: "HS256", typ: "JWT" }),
    encode({
      sub: "6c1f0e4a-0000-4000-8000-000000000001",
      email: `${role}@sadad.test`,
      exp: Math.floor(Date.now() / 1000) + 3600,
      app_metadata: { role, ...(forcePasswordChange ? { force_password_change: true } : {}) },
    }),
    "signature",
  ].join(".");
}
