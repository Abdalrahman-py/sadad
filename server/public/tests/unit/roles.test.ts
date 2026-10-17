import { render } from "lit-html";
import { describe, expect, it } from "vitest";
import { app as view } from "../../src/views/app";
import { loadedModel, noActions } from "./support";

// Labels of every control that changes data. A support user must see none of them.
const MUTATING = [
  "إنشاء حساب متجر",
  "حفظ إعدادات المتجر",
  "تفعيل الحساب",
  "تجميد الحساب",
  "حفظ حالة الاشتراك",
  "تمديد الاشتراك",
  "إنشاء كلمة مؤقتة",
  "أرشفة الحساب",
  "إنهاء جلسات الدخول",
  "إلغاء اعتماد جميع الأجهزة",
  "اعتماد الهاتف",
  "إلغاء الاعتماد",
  "اعتماد الاسترداد",
];

/** A form control is disabled by any disabled fieldset around it, nested ones included. */
function insideDisabledFieldset(field: Element): boolean {
  for (let node = field.parentElement; node; node = node.parentElement) {
    if (node instanceof HTMLFieldSetElement && node.disabled) return true;
  }
  return false;
}

function buttonsFor(role: "admin" | "support", tab: "stores" | "installations" | "audit") {
  const root = document.createElement("main");
  const model = loadedModel(role);
  model.tab = tab;
  render(view(model, noActions), root);
  const buttons = [...root.querySelectorAll("button")];
  return {
    root,
    labels: buttons.map((b) => b.textContent?.trim() ?? ""),
    submits: buttons.filter((b) => b.type === "submit" && !b.closest("[data-read]")),
  };
}

describe("role-aware UI", () => {
  for (const tab of ["stores", "installations", "audit"] as const) {
    it(`shows an admin the mutating controls (${tab})`, () => {
      const { labels } = buttonsFor("admin", tab);
      expect(labels).toEqual(expect.arrayContaining(["اعتماد الهاتف", "إلغاء الاعتماد"]));
    });

    it(`shows a support user no mutating buttons (${tab})`, () => {
      const { root, labels, submits } = buttonsFor("support", tab);
      for (const label of MUTATING) expect(labels).not.toContain(label);
      expect(submits).toHaveLength(0);
      // The settings stay readable but cannot be edited.
      const fields = root.querySelectorAll<HTMLInputElement>("[data-settings] input");
      expect(fields.length).toBeGreaterThan(0);
      for (const field of fields) expect(insideDisabledFieldset(field)).toBe(true);
    });
  }

  it("lists every mutating label for an admin, so the support check is not vacuous", () => {
    const { labels } = buttonsFor("admin", "stores");
    for (const label of MUTATING) expect(labels).toContain(label);
  });
});
