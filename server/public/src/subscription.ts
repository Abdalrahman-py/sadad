// Subscription wording and the two rules the screens share: which stores need a decision now, and
// which stores a filter chip shows. Pure functions — the views and the controller both use them.
import type { Store, StoreRow } from "./api";
import { DAY_MS, day, daysLeft, fmt, fullDay } from "./format";
import type { StoreFilter } from "./model";

/** A timed subscription this close to its end is a decision for the admin. */
export const EXPIRING_MS = 7 * DAY_MS;

export type Tone = "ok" | "warn" | "danger" | "muted";

export interface SubscriptionState {
  /** A pill or a table cell: "متبقي 6 أيام" · "انتهى قبل 5 أيام" · "فعال دائمًا". */
  label: string;
  /** One sentence for the store page: "ينتهي في 31 أكتوبر 2026." */
  detail: string;
  /** The queue's line, with the date and the account state: "ينتهي خلال 3 أيام · 31 أكتوبر 2026". */
  queue: string;
  tone: Tone;
  /** Time left; null when permanent, negative when it already ended. */
  leftMs: number | null;
}

/** "جهاز واحد" · "جهازان" · "6 أجهزة" · "30 جهازًا" — the count with its noun in Arabic. */
export function arabicCount(count: number, one: string, two: string, few: string, many: string) {
  if (count === 1) return one;
  if (count === 2) return two;
  return `${fmt(count)} ${count <= 10 ? few : many}`;
}

export const arabicDays = (count: number) =>
  arabicCount(count, "يوم واحد", "يومان", "أيام", "يومًا");

export function subscriptionState(store: Store): SubscriptionState {
  if (store.archived) {
    return {
      label: "مؤرشف",
      detail: "الحساب مؤرشف؛ الاشتراك لا يسري عليه.",
      queue: "الحساب مؤرشف",
      tone: "muted",
      leftMs: null,
    };
  }
  if (store.subscriptionMode === "permanent") {
    return {
      label: "فعال دائمًا",
      detail: "الاشتراك فعال بلا تاريخ انتهاء.",
      queue: "فعال دائمًا",
      tone: "ok",
      leftMs: null,
    };
  }
  if (store.subscriptionMode === "paused") {
    const keeps = store.subscriptionRemainingMs;
    return {
      label: "موقوف",
      detail:
        keeps === null
          ? "الاشتراك موقوف."
          : `الاشتراك موقوف، والمدة المتبقية محفوظة: ${arabicDays(daysLeft(keeps))}.`,
      queue: "موقوف",
      tone: "muted",
      leftMs: keeps,
    };
  }
  const left = store.subscriptionRemainingMs ?? 0;
  const ends = day(store.subscriptionExpiresAt);
  const endsYear = fullDay(store.subscriptionExpiresAt);
  if (left <= 0) {
    const late = daysLeft(-left);
    const label = late > 0 ? `انتهى قبل ${arabicDays(late)}` : "انتهى اليوم";
    return {
      label,
      detail: `انتهى الاشتراك في ${endsYear}. يحتاج تجديدًا.`,
      queue: `${label} · ${store.status === "active" ? "يحتاج تجديدًا" : "الحساب مجمّد"}`,
      tone: "danger",
      leftMs: left,
    };
  }
  const leftDays = daysLeft(left);
  const label =
    leftDays === 0 ? "ينتهي اليوم" : leftDays === 1 ? "ينتهي غدًا" : `متبقي ${arabicDays(leftDays)}`;
  return {
    label,
    detail: `متبقي ${arabicDays(leftDays)}، وينتهي ${endsYear}.`,
    queue: `${leftDays <= 1 ? label : `ينتهي خلال ${arabicDays(leftDays)}`} · ${ends}`,
    tone: left <= EXPIRING_MS ? "warn" : "ok",
    leftMs: left,
  };
}

export const statusText = (store: Store): string =>
  store.archived ? "مؤرشف" : store.status === "active" ? "نشط" : "مجمّد";

export const statusTone = (store: Store): Tone =>
  store.archived ? "muted" : store.status === "active" ? "ok" : "danger";

/** Ends within a week or already ended: the admin has to extend or let it lapse. */
export function needsDecision(store: StoreRow): boolean {
  if (store.archived || store.subscriptionMode !== "timed") return false;
  return (store.subscriptionRemainingMs ?? 0) <= EXPIRING_MS;
}

/** Urgent first: ended accounts, then the ones closest to their end. */
export const byUrgency = (a: StoreRow, b: StoreRow): number =>
  (a.subscriptionRemainingMs ?? 0) - (b.subscriptionRemainingMs ?? 0);

export function matchesFilter(store: StoreRow, filter: StoreFilter): boolean {
  const sub = subscriptionState(store);
  switch (filter) {
    case "all":
      return !store.archived;
    case "active":
      return !store.archived && store.status === "active";
    case "soon":
      return !store.archived && sub.tone === "warn";
    case "over":
      return !store.archived && sub.tone === "danger" && store.subscriptionMode === "timed";
    case "suspended":
      return !store.archived && store.status !== "active";
    case "archived":
      return store.archived;
  }
}
