import { describe, expect, it } from "vitest";
import { inspectManifest, MAX_MANIFEST_BYTES } from "../../src/recovery-manifest";

const phone = { installationId: "a0000000-0000-4000-8000-000000000001", generation: 1 };
// The shape the Android app exports.
const command = {
  schemaVersion: 1,
  operationId: "b0000000-0000-4000-8000-000000000001",
  entityId: "c0000000-0000-4000-8000-000000000001",
  type: "contact.create",
  originInstallationId: phone.installationId,
  originGeneration: 1,
  expectedEpoch: "d0000000-0000-4000-8000-000000000001",
  createdAt: "2026-10-13T12:00:00.000+03:00",
  dependsOn: [],
  payload: { name: "عميل", phone: "" },
};

const blocking = (commands: unknown) =>
  inspectManifest(JSON.stringify(commands), phone).problems.filter((p) => p.blocking);

describe("recovery manifest pre-flight", () => {
  it("accepts the command the Android app exports", () => {
    expect(blocking([command])).toEqual([]);
    expect(blocking({ commands: [command] })).toEqual([]);
  });

  it("accepts both wallet payment methods", () => {
    for (const method of ["wallet_jawwal", "wallet_palpay"]) {
      const payment = { ...command, type: "payment.create", payload: { amountCents: 100, method } };
      expect(blocking([payment])).toEqual([]);
    }
  });

  it.each([
    ["a string schemaVersion", [{ ...command, schemaVersion: "1" }]],
    ["another device as origin", [{ ...command, originInstallationId: command.entityId }]],
    ["another generation as origin", [{ ...command, originGeneration: 2 }]],
    ["a duplicate operationId", [command, command]],
    ["an unsupported type", [{ ...command, type: "debt.correct" }]],
    [
      "a zero payment",
      [{ ...command, type: "payment.create", payload: { amountCents: 0, method: "cash" } }],
    ],
    [
      "fractional cents",
      [{ ...command, type: "debt.create", payload: { amountCents: 1.5, direction: "receivable" } }],
    ],
    [
      "an unknown payment method",
      [{ ...command, type: "payment.create", payload: { amountCents: 5, method: "gold" } }],
    ],
    ["an empty list", []],
    ["not a list", { nothing: true }],
  ])("blocks %s", (_label, commands) => {
    expect(blocking(commands).length).toBeGreaterThan(0);
  });

  it("blocks text that is not JSON or larger than the server accepts", () => {
    expect(inspectManifest("{", phone).problems[0]?.blocking).toBe(true);
    expect(inspectManifest(" ".repeat(MAX_MANIFEST_BYTES + 1), phone).stats).toBeNull();
  });

  it("totals the money it will authorise", () => {
    const report = inspectManifest(
      JSON.stringify([
        {
          ...command,
          type: "debt.create",
          operationId: "o1",
          payload: { amountCents: 1250, direction: "receivable" },
        },
        {
          ...command,
          type: "payment.create",
          operationId: "o2",
          payload: { amountCents: 500, method: "cash" },
        },
      ]),
      phone,
    );
    expect(report.stats).toMatchObject({ total: 2, debtCents: 1250, paymentCents: 500 });
  });
});
