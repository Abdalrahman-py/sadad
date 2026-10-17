import { render } from "lit-html";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { app as view } from "../../src/views/app";
import { loadedModel, noActions } from "./support";

const HOSTILE = '<img src=x onerror="globalThis.__xss = true">';

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

  for (const tab of ["stores", "installations", "audit"] as const) {
    it(`renders a store name with markup as text (${tab} tab and the open store)`, () => {
      const model = loadedModel("admin", HOSTILE);
      model.tab = tab;
      render(view(model, noActions), root);

      // Only the brand logo is an image; the hostile name made none.
      for (const img of root.querySelectorAll("img")) expect(img.getAttribute("src")).not.toBe("x");
      expect(root.querySelectorAll("[onerror]")).toHaveLength(0);
      expect(root.textContent).toContain(HOSTILE);
      expect((globalThis as { __xss?: boolean }).__xss).toBeUndefined();
    });
  }
});
