import { render } from "lit-html";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Model } from "../../src/model";
import { app as view } from "../../src/views/app";
import { loadedModel, noActions } from "./support";

const HOSTILE = '<img src=x onerror="globalThis.__xss = true">';

const SCREENS: [string, (model: Model) => void][] = [
  [
    "المهام",
    (m) => {
      m.screen = "tasks";
    },
  ],
  [
    "المتاجر",
    (m) => {
      m.screen = "stores";
    },
  ],
  [
    "صفحة المتجر",
    (m) => {
      m.screen = "store";
    },
  ],
  [
    "الأجهزة",
    (m) => {
      m.screen = "store";
      if (m.store) m.store.tab = "devices";
    },
  ],
  [
    "سجل الإدارة",
    (m) => {
      m.screen = "audit";
    },
  ],
];

describe("untrusted text", () => {
  let root: HTMLElement;
  beforeEach(() => {
    root = document.createElement("main");
    document.body.append(root);
  });
  afterEach(() => {
    root.remove();
    delete (globalThis as { __xss?: boolean }).__xss;
  });

  for (const [name, set] of SCREENS) {
    it(`renders a store name with markup as text (${name})`, () => {
      const model = loadedModel("admin", HOSTILE);
      set(model);
      render(view(model, noActions), root);

      // Only the brand logo is an image; the hostile name made none.
      for (const img of root.querySelectorAll("img")) expect(img.getAttribute("src")).not.toBe("x");
      expect(root.querySelectorAll("[onerror]")).toHaveLength(0);
      expect(root.textContent).toContain(HOSTILE);
      expect((globalThis as { __xss?: boolean }).__xss).toBeUndefined();
    });
  }
});
