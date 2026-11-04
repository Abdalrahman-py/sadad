import { describe, expect, it } from "vitest";
import type { StoreRow } from "../../src/api";
import { parseHash } from "../../src/controller";
import {
  arabicDays,
  matchesFilter,
  needsDecision,
  subscriptionState,
} from "../../src/subscription";
import { storeRow } from "../data";

const DAY = 86_400_000;

const withSub = (patch: Partial<StoreRow>): StoreRow => ({ ...storeRow(), ...patch });

describe("hash routing", () => {
  it("reads the design's five screens", () => {
    expect(parseHash("")).toEqual({ screen: "tasks" });
    expect(parseHash("#/tasks")).toEqual({ screen: "tasks" });
    expect(parseHash("#/stores")).toEqual({ screen: "stores" });
    expect(parseHash("#/audit")).toEqual({ screen: "audit" });
    expect(parseHash("#/create")).toEqual({ screen: "create" });
  });

  it("opens a store on the tab in the hash, and falls back to the overview", () => {
    expect(parseHash("#/stores/12/devices")).toEqual({ screen: "store", id: 12, tab: "devices" });
    expect(parseHash("#/stores/12")).toEqual({ screen: "store", id: 12, tab: "overview" });
    expect(parseHash("#/stores/12/nonsense")).toEqual({
      screen: "store",
      id: 12,
      tab: "overview",
    });
  });

  it("ignores a store id that is not a number", () => {
    expect(parseHash("#/stores/abc")).toEqual({ screen: "stores" });
    expect(parseHash("#/stores/0")).toEqual({ screen: "stores" });
    expect(parseHash("#/unknown")).toEqual({ screen: "tasks" });
  });
});

describe("subscription wording", () => {
  it("counts in Arabic: 1, 2, 3–10, 11+", () => {
    expect(arabicDays(1)).toContain("يوم واحد");
    expect(arabicDays(2)).toContain("يومان");
    expect(arabicDays(6)).toContain("أيام");
    expect(arabicDays(30)).toContain("يومًا");
  });

  it("marks a timed subscription ending within a week and an ended one", () => {
    expect(subscriptionState(withSub({ subscriptionRemainingMs: 6 * DAY })).tone).toBe("warn");
    expect(subscriptionState(withSub({ subscriptionRemainingMs: 30 * DAY })).tone).toBe("ok");
    expect(subscriptionState(withSub({ subscriptionRemainingMs: -2 * DAY })).tone).toBe("danger");
    expect(subscriptionState(withSub({ subscriptionMode: "permanent" })).label).toBe("فعال دائمًا");
    expect(subscriptionState(withSub({ subscriptionMode: "paused" })).tone).toBe("muted");
    expect(subscriptionState(withSub({ archived: true })).tone).toBe("muted");
  });
});

describe("the queue and the filter chips", () => {
  it("needs a decision only for timed subscriptions inside a week", () => {
    expect(needsDecision(withSub({ subscriptionRemainingMs: 6 * DAY }))).toBe(true);
    expect(needsDecision(withSub({ subscriptionRemainingMs: -1 * DAY }))).toBe(true);
    expect(needsDecision(withSub({ subscriptionRemainingMs: 30 * DAY }))).toBe(false);
    expect(needsDecision(withSub({ subscriptionMode: "permanent" }))).toBe(false);
    expect(needsDecision(withSub({ archived: true }))).toBe(false);
  });

  it("filters by the state each chip names", () => {
    const soon = withSub({ subscriptionRemainingMs: 3 * DAY });
    const over = withSub({ subscriptionRemainingMs: -3 * DAY, status: "suspended" });
    const fine = withSub({ subscriptionRemainingMs: 40 * DAY });
    const archived = withSub({ archived: true });

    expect(matchesFilter(soon, "all")).toBe(true);
    expect(matchesFilter(soon, "soon")).toBe(true);
    expect(matchesFilter(soon, "over")).toBe(false);
    expect(matchesFilter(over, "over")).toBe(true);
    expect(matchesFilter(over, "suspended")).toBe(true);
    expect(matchesFilter(fine, "active")).toBe(true);
    expect(matchesFilter(fine, "soon")).toBe(false);
    expect(matchesFilter(archived, "all")).toBe(false);
    expect(matchesFilter(archived, "archived")).toBe(true);
  });
});
