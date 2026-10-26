// Fills one store on a Supabase project with 5,000 Contacts and 50,000 Ledger operations
// (Contacts, Debts, Payments) through the real signed `api`, so the Phase 3 performance run
// measures the real protocol. It writes test data.
//
//   SADAD_SMOKE_URL=https://<ref>.supabase.co \
//   SADAD_SMOKE_PUBLISHABLE_KEY=sb_publishable_... \
//   SADAD_SMOKE_USERNAME=<store username> SADAD_SMOKE_PASSWORD=... \
//   SADAD_SMOKE_NEW_PASSWORD=... \
//   SADAD_SEED=sadad-perf-1 \
//   deno run --allow-env --allow-net --allow-read --allow-write tools/seed-store.ts
//
// The admin must set the store's debtorLimit to at least 5,000 first (the schema caps active
// Contacts at 100 by default). The first run enrolls an installation (label seed-tool) and
// waits for approval in the dashboard, then saves its id, generation and P-256 private key to
// ~/.sadad/seed-<project ref>-<username>-<seed>.json (mode 0600, outside the repo). A later run
// with the same project, username and seed reuses that file and skips enrolling; delete it to
// enroll again. The data is a pure function of SADAD_SEED, so a re-run resends the same commands:
// an interrupted run resumes where it stopped and a finished one changes nothing. Never prints a
// password or token.
import {
  authenticate,
  type Device,
  deviceFromJwk,
  enroll,
  env,
  fail,
  newDevice,
  projectUrl,
  signed,
  waitForApproval,
} from "./api-client.ts";

export interface Origin {
  installationId: string;
  generation: number;
  epoch: string;
}

export interface SeedCommand {
  schemaVersion: number;
  operationId: string;
  entityId: string;
  type: "contact.create" | "debt.create" | "payment.create";
  dependsOn: string[];
  originInstallationId: string;
  originGeneration: number;
  expectedEpoch: string;
  createdAt: string;
  payload: Record<string, unknown>;
}

const CONTACTS = 5_000;
const DEBTS = 30_000;
const PAYMENTS = 15_000;
const BATCH_SIZE = 100;
/** Created-at values are spread over the year before this fixed anchor, never the clock. */
const ANCHOR_MS = Date.parse("2026-10-01T00:00:00.000Z");
const DAY_MS = 86_400_000;
const FNV_PRIME = 0x01000193;

function fnv(text: string, salt: number): number {
  let hash = salt >>> 0;
  for (let index = 0; index < text.length; index++) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, FNV_PRIME);
  }
  return hash >>> 0;
}

function number(seed: string, kind: string, index: number): number {
  return fnv(`${seed}\u0000${kind}\u0000${index}`, 0x811c9dc5);
}

/** A UUID v5-style id: a hash of seed + kind + index, so it never depends on the clock. */
function id(seed: string, kind: string, index: number): string {
  const text = `${seed}\u0000${kind}\u0000${index}`;
  const bytes = new Uint8Array(16);
  const view = new DataView(bytes.buffer);
  [0x811c9dc5, 0x9e3779b9, 0x85ebca6b, 0xc2b2ae35].forEach((salt, part) =>
    view.setUint32(part * 4, fnv(text, salt))
  );
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${
    hex.slice(16, 20)
  }-${hex.slice(20)}`;
}

function at(milliseconds: number): string {
  return new Date(milliseconds).toISOString();
}

/** Milliseconds before the anchor: a day in the anchored year plus a time within it. */
function before(seed: string, kind: string, index: number): number {
  return (number(seed, `${kind}-day`, index) % 365) * DAY_MS +
    (number(seed, `${kind}-ms`, index) % DAY_MS);
}

/** A time between `after` and the anchor, so a Debt never predates its Contact nor a Payment its Debt. */
function later(
  seed: string,
  kind: string,
  index: number,
  after: number,
): number {
  return after +
    Math.floor(
      (number(seed, `${kind}-later`, index) / 2 ** 32) * (ANCHOR_MS - after),
    );
}

/**
 * Builds the whole command list from the seed and the origin: same inputs, same bytes, on any
 * day. Contacts come first, then Debts (each on an earlier Contact), then Payments (each on an
 * earlier Debt), so a batch never depends on a later one.
 */
export function buildCommands(seed: string, origin: Origin): SeedCommand[] {
  const commands: SeedCommand[] = [];
  const contactOps: string[] = [];
  const contactIds: string[] = [];
  const debtOps: string[] = [];
  const debtIds: string[] = [];
  const debtAmounts: number[] = [];
  const contactTimes: number[] = [];
  const debtTimes: number[] = [];
  const base = {
    schemaVersion: 1,
    originInstallationId: origin.installationId,
    originGeneration: origin.generation,
    expectedEpoch: origin.epoch,
  };

  for (let index = 0; index < CONTACTS; index++) {
    const operationId = id(seed, "op-contact", index);
    const entityId = id(seed, "contact", index);
    contactOps.push(operationId);
    contactIds.push(entityId);
    const createdAt = ANCHOR_MS - before(seed, "contact", index);
    contactTimes.push(createdAt);
    commands.push({
      ...base,
      createdAt: at(createdAt),
      operationId,
      entityId,
      type: "contact.create",
      dependsOn: [],
      payload: {
        name: `زبون ${String(index + 1).padStart(4, "0")}`,
        // apply_one requires kind on every contact; the store this seeds is all customers.
        kind: "customer",
        phone: `+970599${String(index + 1).padStart(6, "0")}`,
      },
    });
  }

  for (let index = 0; index < DEBTS; index++) {
    const operationId = id(seed, "op-debt", index);
    const entityId = id(seed, "debt", index);
    const amountCents = 1 + (number(seed, "debt-amount", index) % 50_000);
    const contact = index % CONTACTS;
    const createdAt = later(seed, "debt", index, contactTimes[contact]);
    debtTimes.push(createdAt);
    debtOps.push(operationId);
    debtIds.push(entityId);
    debtAmounts.push(amountCents);
    commands.push({
      ...base,
      createdAt: at(createdAt),
      operationId,
      entityId,
      type: "debt.create",
      dependsOn: [contactOps[contact]],
      payload: {
        contactId: contactIds[contact],
        direction: "receivable",
        amountCents,
        ...(index % 5 === 0
          ? { dueDate: at(createdAt + 30 * DAY_MS).slice(0, 10) }
          : {}),
      },
    });
  }

  for (let index = 0; index < PAYMENTS; index++) {
    const debt = index % DEBTS;
    commands.push({
      ...base,
      createdAt: at(later(seed, "payment", index, debtTimes[debt])),
      operationId: id(seed, "op-payment", index),
      entityId: id(seed, "payment", index),
      type: "payment.create",
      dependsOn: [debtOps[debt]],
      // 1..what is left on that Debt (each Debt gets at most one Payment).
      payload: {
        debtId: debtIds[debt],
        amountCents: 1 +
          (number(seed, "payment-amount", index) % debtAmounts[debt]),
        method: "cash",
      },
    });
  }

  return commands;
}

export function batchCommands(
  commands: SeedCommand[],
  size = BATCH_SIZE,
): SeedCommand[][] {
  const batches: SeedCommand[][] = [];
  for (let index = 0; index < commands.length; index += size) {
    batches.push(commands.slice(index, index + size));
  }
  return batches;
}

interface SavedInstallation {
  installationId: string;
  generation: number;
  privateKey: JsonWebKey;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function keyFilePath(seed: string): string {
  const home = Deno.env.get("HOME") ?? Deno.env.get("USERPROFILE") ?? ".";
  const ref = new URL(projectUrl()).host.split(".")[0];
  return `${home}/.sadad/seed-${ref}-${
    env("SADAD_SMOKE_USERNAME")
  }-${seed}.json`;
}

function fileExists(path: string): boolean {
  try {
    Deno.statSync(path);
    return true;
  } catch {
    return false;
  }
}

async function saveInstallation(
  path: string,
  saved: SavedInstallation,
): Promise<void> {
  await Deno.mkdir(path.slice(0, path.lastIndexOf("/")), { recursive: true });
  await Deno.writeTextFile(path, `${JSON.stringify(saved)}\n`, { mode: 0o600 });
  await Deno.chmod(path, 0o600);
}

async function run(): Promise<void> {
  const seed = Deno.env.get("SADAD_SEED") ?? "sadad-perf-1";
  const jwt = await authenticate();
  const keyPath = keyFilePath(seed);

  let device: Device;
  let generation: number;
  let epoch: string;
  if (fileExists(keyPath)) {
    const saved = JSON.parse(
      await Deno.readTextFile(keyPath),
    ) as SavedInstallation;
    device = await deviceFromJwk(saved.installationId, saved.privateKey);
    generation = saved.generation;
    const probe = await signed(
      jwt,
      device,
      "GET",
      "/v1/mobile/changes?after=0",
    );
    if (probe.status !== 200) {
      console.error(
        `saved installation ${device.installationId} is no longer active (${probe.status} ${
          probe.body?.code ?? ""
        }); delete ${keyPath} to enroll again.`,
      );
      Deno.exit(1);
    }
    epoch = probe.body.changes.epoch;
    console.log(
      `reusing saved installation ${device.installationId} (generation ${generation})`,
    );
  } else {
    device = await newDevice();
    const installation = await enroll(jwt, device, "seed-tool");
    console.log(
      `enrolled ${device.installationId} (generation ${installation.generation}, ${installation.status}); approve it in the dashboard`,
    );
    const changes = await waitForApproval(jwt, device);
    generation = installation.generation;
    epoch = changes.epoch;
    await saveInstallation(keyPath, {
      installationId: device.installationId,
      generation,
      privateKey: await crypto.subtle.exportKey("jwk", device.privateKey),
    });
    console.log(`saved installation to ${keyPath}`);
  }

  const commands = buildCommands(seed, {
    installationId: device.installationId,
    generation,
    epoch,
  });
  console.log(`${commands.length} commands, ${epoch} epoch`);

  const durations: number[] = [];
  const started = performance.now();
  let accepted = 0;
  let lastRequest = 0;
  for (const operations of batchCommands(commands)) {
    const pending = 250 - (Date.now() - lastRequest);
    if (pending > 0) await sleep(pending);
    lastRequest = Date.now();
    const time = async () => {
      const before = performance.now();
      const result = await signed(
        jwt,
        device,
        "POST",
        "/v1/mobile/operations",
        {
          operations,
        },
      );
      durations.push(performance.now() - before);
      return result;
    };
    let response = await time();
    while (response.status === 429) {
      console.error("rate limited; waiting 20 s");
      await sleep(20_000);
      lastRequest = Date.now();
      response = await time();
    }
    if (response.status !== 200) fail("operations", response);
    for (const receipt of response.body.receipts) {
      if (receipt.status === "rejected") {
        console.error(`rejected ${receipt.operationId}: ${receipt.code}`);
        Deno.exit(1);
      }
    }
    accepted += response.body.receipts.length;
    if (accepted % 1_000 === 0) {
      console.log(`pushed ${accepted}/${commands.length}`);
    }
  }

  durations.sort((a, b) => a - b);
  // Nearest-rank percentile: the smallest value at or above the quantile.
  const percentile = (quantile: number) =>
    durations[
      Math.min(
        durations.length - 1,
        Math.ceil(quantile * durations.length) - 1,
      )
    ];
  console.log(
    `${commands.length} commands, ${accepted} accepted, ${
      ((performance.now() - started) / 1000).toFixed(1)
    } s, p50 ${percentile(0.5).toFixed(0)} ms, p95 ${
      percentile(0.95).toFixed(0)
    } ms`,
  );
}

if (import.meta.main) await run();
