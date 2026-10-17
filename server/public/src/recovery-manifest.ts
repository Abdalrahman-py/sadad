// Pre-flight for a recovery manifest. Approving a recovery cannot be undone, and the server reports
// only its first problem. sadid_private.approve_recovery requires 1..1000 commands, each with
// schemaVersion 1, the old installation's id and generation, a unique operationId, and a type in
// contact.create, debt.create, payment.create. All of that is checked here so the admin sees what
// they authorise before they authorise it.
import { englishDigits, fmt, money } from "./format";

export const RECOVERY_TYPES = {
  "contact.create": "إضافة شخص",
  "debt.create": "إضافة دين",
  "payment.create": "تسجيل دفعة",
} as const;

const METHODS: Record<string, string> = {
  cash: "نقدي",
  bank: "بنكي",
  wallet: "محفظة",
  wallet_jawwal: "محفظة جوال",
  wallet_palpay: "محفظة بال بي",
};

export const MAX_MANIFEST_BYTES = 4_000_000;

export interface Problem {
  blocking: boolean;
  message: string;
}

export interface ManifestReport {
  commands: Record<string, unknown>[] | null;
  problems: Problem[];
  stats: {
    total: number;
    byType: Partial<Record<keyof typeof RECOVERY_TYPES, number>>;
    debtCents: number;
    paymentCents: number;
    first: number | null;
    last: number | null;
  } | null;
  details: { at: string; text: string; sub: string }[];
}

type Command = Record<string, unknown>;
const isObject = (value: unknown): value is Command =>
  !!value && typeof value === "object" && !Array.isArray(value);

/** The command array from a pasted manifest: a bare array or the app's export wrappers. */
export function readCommands(text: string): Command[] | string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return "النص ليس JSON صالحًا.";
  }
  const wrapper = isObject(parsed) ? parsed : {};
  const list = Array.isArray(parsed)
    ? parsed
    : Array.isArray(wrapper.commands)
      ? wrapper.commands
      : Array.isArray(wrapper.manifest)
        ? wrapper.manifest
        : Array.isArray(wrapper.entries)
          ? wrapper.entries.map((entry) => (isObject(entry) ? entry.command : null)).filter(Boolean)
          : null;
  if (!list) return "لم أجد قائمة أوامر. الصق مصفوفة الأوامر من شاشة الاسترداد أو ملفها.";
  return list as Command[];
}

export function inspectManifest(
  text: string,
  oldPhone?: { installationId: string; generation: number },
): ManifestReport {
  if (new TextEncoder().encode(text).length > MAX_MANIFEST_BYTES) {
    return report(null, [
      { blocking: true, message: "قائمة الاسترداد أكبر من الحد المسموح (4 MB)." },
    ]);
  }
  const read = readCommands(text);
  if (typeof read === "string") return report(null, [{ blocking: true, message: read }]);

  const problems: Problem[] = [];
  const block = (message: string) => problems.push({ blocking: true, message });
  const seen = new Map<string, string>();
  const stats: NonNullable<ManifestReport["stats"]> = {
    total: read.length,
    byType: {},
    debtCents: 0,
    paymentCents: 0,
    first: null,
    last: null,
  };
  const details: ManifestReport["details"] = [];

  read.forEach((cmd, index) => {
    const at = `الأمر ${fmt(index + 1)}`;
    if (!isObject(cmd)) {
      block(`${at}: ليس كائنًا.`);
      return;
    }
    const type = String(cmd.type ?? "");
    const known = type in RECOVERY_TYPES ? (type as keyof typeof RECOVERY_TYPES) : null;
    if (!known) block(`${at}: نوع غير مدعوم (${type || "فارغ"}).`);
    else stats.byType[known] = (stats.byType[known] ?? 0) + 1;
    if (cmd.schemaVersion !== 1) block(`${at}: إصدار قائمة الإدخالات غير مدعوم.`);
    const operationId = typeof cmd.operationId === "string" ? cmd.operationId : "";
    if (!operationId) block(`${at}: بلا operationId.`);
    else if (seen.has(operationId)) block(`${at}: رقم عملية مكرر مع ${seen.get(operationId)}.`);
    else seen.set(operationId, at);
    if (oldPhone) {
      if (String(cmd.originInstallationId ?? "") !== oldPhone.installationId) {
        block(`${at}: originInstallationId لا يطابق الهاتف السابق المختار.`);
      }
      if (Number(cmd.originGeneration) !== oldPhone.generation) {
        block(`${at}: originGeneration لا يطابق جيل الهاتف السابق (${fmt(oldPhone.generation)}).`);
      }
    }
    const created = Date.parse(String(cmd.createdAt ?? ""));
    if (Number.isFinite(created)) {
      if (stats.first === null || created < stats.first) stats.first = created;
      if (stats.last === null || created > stats.last) stats.last = created;
    }
    const payload = isObject(cmd.payload) ? cmd.payload : null;
    if (!payload) {
      block(`${at}: بلا payload.`);
      return;
    }
    if (known === "contact.create") {
      const name = String(payload.name ?? "").trim();
      if (!name) block(`${at}: شخص بلا اسم.`);
      const phone = englishDigits(payload.phone).replace(/[\s()-]/g, "");
      if (phone && !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
        problems.push({
          blocking: false,
          message: `${at}: رقم الهاتف ليس بصيغة دولية (${phone}).`,
        });
      }
      details.push({ at, text: `شخص: ${name || "—"}`, sub: phone || "بلا رقم" });
    } else if (known === "debt.create") {
      const cents = Number(englishDigits(payload.amountCents));
      if (!Number.isSafeInteger(cents) || cents < 1) block(`${at}: مبلغ الدين غير صالح.`);
      else stats.debtCents += cents;
      if (payload.direction !== "receivable" && payload.direction !== "payable") {
        block(`${at}: اتجاه الدين غير صالح.`);
      }
      details.push({
        at,
        text: `دين: ${money(cents)}`,
        sub: payload.direction === "payable" ? "مستحق على المتجر" : "مستحق للمتجر",
      });
    } else if (known === "payment.create") {
      const cents = Number(englishDigits(payload.amountCents));
      if (!Number.isSafeInteger(cents) || cents < 1) block(`${at}: مبلغ الدفعة غير صالح.`);
      else stats.paymentCents += cents;
      const method = String(payload.method ?? "");
      if (!(method in METHODS)) block(`${at}: طريقة الدفع غير صالحة.`);
      details.push({ at, text: `دفعة: ${money(cents)}`, sub: METHODS[method] ?? (method || "—") });
    }
  });
  if (!read.length) block("القائمة فارغة.");
  if (read.length > 1000) block(`${fmt(read.length)} أمرًا يتجاوز الحد الأقصى 1000.`);
  return { commands: read, problems, stats, details };
}

function report(commands: Command[] | null, problems: Problem[]): ManifestReport {
  return { commands, problems, stats: null, details: [] };
}
