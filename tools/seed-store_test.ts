// Unit tests for the pure builder in tools/seed-store.ts (no network).
import { batchCommands, buildCommands, type Origin } from "./seed-store.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ANCHOR = Date.parse("2026-10-01T00:00:00.000Z");
const YEAR_MS = 365 * 86_400_000;
const origin: Origin = {
  installationId: "11111111-1111-4111-8111-111111111111",
  generation: 1,
  epoch: "22222222-2222-4222-8222-222222222222",
};

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEqual(actual: unknown, expected: unknown): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
  }
}

Deno.test("the builder is deterministic and anchored, never clock-bound", () => {
  const first = buildCommands("t", origin);
  const second = buildCommands("t", origin);
  assertEqual(JSON.stringify(first), JSON.stringify(second));
  for (const command of first) {
    const at = Date.parse(command.createdAt);
    assert(
      Number.isFinite(at),
      `createdAt is not a time: ${command.createdAt}`,
    );
    assert(
      at <= ANCHOR && at >= ANCHOR - YEAR_MS,
      `createdAt outside the anchored year: ${command.createdAt}`,
    );
  }
  const days = new Set(
    first
      .filter((command) => command.type === "debt.create")
      .map((command) => command.createdAt.slice(0, 10)),
  );
  assert(days.size >= 300, `debts only span ${days.size} days, not the year`);
});

Deno.test("the list holds 5,000 contacts, 30,000 debts and 15,000 payments", () => {
  const commands = buildCommands("t", origin);
  assertEqual(commands.length, 50_000);
  const count = (type: string) =>
    commands.filter((command) => command.type === type).length;
  assertEqual(count("contact.create"), 5_000);
  assertEqual(count("debt.create"), 30_000);
  assertEqual(count("payment.create"), 15_000);
});

Deno.test("dependencies come earlier and never move to a later batch", () => {
  const commands = buildCommands("t", origin);
  const at = new Map(
    commands.map((command, index) => [command.operationId, index]),
  );
  const batchOf = new Map<string, number>();
  batchCommands(commands, 100).forEach((batch, index) =>
    batch.forEach((command) => batchOf.set(command.operationId, index))
  );
  for (const [index, command] of commands.entries()) {
    assert(
      UUID.test(command.operationId),
      `bad operationId ${command.operationId}`,
    );
    assert(UUID.test(command.entityId), `bad entityId ${command.entityId}`);
    for (const dependency of command.dependsOn) {
      const earlier = at.get(dependency);
      assert(earlier !== undefined, `${command.type} depends on an unknown id`);
      assert(earlier < index, `${command.type} depends on a later command`);
      assert(
        batchOf.get(dependency)! <= batchOf.get(command.operationId)!,
        `${command.type} depends on a later batch`,
      );
    }
  }
});

Deno.test("payments stay within their debt; amounts are integer cents >= 1", () => {
  const commands = buildCommands("t", origin);
  const amounts = new Map<string, number>();
  for (const command of commands) {
    if (command.type === "debt.create") {
      amounts.set(command.entityId, command.payload.amountCents as number);
    }
  }
  const paid = new Map<string, number>();
  for (const command of commands) {
    if (command.type === "contact.create") continue;
    const amount = command.payload.amountCents as number;
    assert(
      Number.isInteger(amount) && amount >= 1,
      `bad amount ${amount} on ${command.type}`,
    );
    if (command.type !== "payment.create") continue;
    const debt = command.payload.debtId as string;
    assert(amounts.has(debt), "payment on an unknown debt");
    paid.set(debt, (paid.get(debt) ?? 0) + amount);
  }
  for (const [debt, total] of paid) {
    assert(total <= amounts.get(debt)!, "payments exceed the debt");
  }
});

Deno.test("every batch body fits the 512 KiB write limit", () => {
  const encoder = new TextEncoder();
  for (const batch of batchCommands(buildCommands("t", origin), 100)) {
    const bytes = encoder.encode(JSON.stringify({ operations: batch })).length;
    assert(bytes <= 524_288, `batch body ${bytes} bytes`);
  }
});
