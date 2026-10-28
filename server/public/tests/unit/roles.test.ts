import { render } from "lit-html";
import { describe, expect, it } from "vitest";
import type { Model } from "../../src/model";
import { app as view } from "../../src/views/app";
import { loadedModel, noActions } from "./support";

interface Screen {
  name: string;
  set(model: Model): void;
  /** Labels of the controls that change data on this screen. */
  controls: string[];
  /** The account settings form only exists on its tab. */
  hasSettings?: boolean;
}

const SCREENS: Screen[] = [
  {
    name: "المهام",
    set: (m) => {
      m.screen = "tasks";
    },
    controls: ["اعتماد الجهاز", "رفض"],
  },
  {
    name: "المتاجر",
    set: (m) => {
      m.screen = "stores";
    },
    controls: ["إنشاء حساب متجر"],
  },
  {
    name: "الأجهزة",
    set: (m) => {
      m.screen = "store";
      if (m.store) m.store.tab = "devices";
    },
    controls: ["اعتماد", "رفض"],
  },
  {
    name: "الإعدادات",
    set: (m) => {
      m.screen = "store";
      if (m.store) m.store.tab = "settings";
    },
    controls: ["حفظ التغييرات", "تجميد", "إنهاء الجلسات", "إلغاء الكل", "أرشفة"],
    hasSettings: true,
  },
  {
    name: "الاشتراك",
    set: (m) => {
      m.screen = "store";
      if (m.store) m.store.tab = "subscription";
    },
    controls: ["تفعيل بعدد الأيام"],
  },
  {
    name: "الاسترداد",
    set: (m) => {
      m.screen = "store";
      if (m.store) m.store.tab = "recovery";
    },
    controls: ["اعتماد الاسترداد"],
  },
  {
    name: "إنشاء متجر",
    set: (m) => {
      m.screen = "create";
    },
    controls: ["إنشاء الحساب"],
  },
];

function renderScreen(role: "admin" | "support", screen: Screen) {
  const root = document.createElement("main");
  const model = loadedModel(role);
  screen.set(model);
  render(view(model, noActions), root);
  const buttons = [...root.querySelectorAll("button")];
  // Links that are styled as buttons change data too (the create-store call to action).
  const actions = [...root.querySelectorAll("a.btn")];
  return {
    root,
    labels: [...buttons, ...actions].map((element) => element.textContent?.trim() ?? ""),
    submits: buttons.filter((button) => button.type === "submit" && !button.closest("[data-read]")),
  };
}

/** A form control is disabled by any disabled fieldset around it, nested ones included. */
function insideDisabledFieldset(field: Element): boolean {
  for (let node = field.parentElement; node; node = node.parentElement) {
    if (node instanceof HTMLFieldSetElement && node.disabled) return true;
  }
  return false;
}

describe("role-aware UI", () => {
  for (const screen of SCREENS) {
    it(`shows an admin the controls that change data (${screen.name})`, () => {
      const { labels } = renderScreen("admin", screen);
      for (const control of screen.controls) expect(labels).toContain(control);
    });

    it(`shows a support user no control that changes data (${screen.name})`, () => {
      const { root, labels, submits } = renderScreen("support", screen);
      for (const control of screen.controls) expect(labels).not.toContain(control);
      expect(submits).toHaveLength(0);
      if (!screen.hasSettings) return;
      // Readable, not editable: the settings form renders with its fieldset disabled.
      const fields = root.querySelectorAll<HTMLInputElement>("[data-settings] input");
      expect(fields.length).toBeGreaterThan(0);
      for (const field of fields) expect(insideDisabledFieldset(field)).toBe(true);
    });
  }
});
