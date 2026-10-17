// Smoke run against the production build (vite preview) with Auth and the api mocked.
// Requests and responses follow contract/fixtures/admin and the typed rows in tests/data.ts.
import { expect, type Page, type Route, test } from "@playwright/test";
import messages from "../../../../contract/errors.json" with { type: "json" };
import createStore from "../../../../contract/fixtures/admin/create-store.json" with {
  type: "json",
};
import refusals from "../../../../contract/fixtures/admin/refusals.json" with { type: "json" };
import { API_BASE, SUPABASE_URL } from "../../src/config";
import { auditEvent, detail, jwtFor, overview, PENDING_ID, STORE_ID, storeRow } from "../data";

const JWT = jwtFor("admin");
const PASSWORD = "temp-Password-From-Api-123";

type Call = { method: string; path: string; body: unknown; auth: string | null };

async function mockBackend(page: Page) {
  const calls: Call[] = [];
  let approved = false;
  let created = false;

  await page.route(`${SUPABASE_URL}/auth/v1/**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/auth/v1/token" && url.searchParams.get("grant_type") === "password") {
      const { email } = route.request().postDataJSON();
      return route.fulfill({
        json: {
          access_token: JWT,
          token_type: "bearer",
          expires_in: 3600,
          expires_at: Math.floor(Date.now() / 1000) + 3600,
          refresh_token: "refresh-token",
          user: {
            id: "6c1f0e4a-0000-4000-8000-000000000001",
            aud: "authenticated",
            email,
            app_metadata: { role: "admin" },
            user_metadata: {},
            created_at: "2026-10-15T09:00:00Z",
          },
        },
      });
    }
    return route.fulfill({ status: 204 });
  });

  const refuse = (route: Route, refusal: { status: number; code: string }) =>
    route.fulfill({
      status: refusal.status,
      json: {
        ok: false,
        code: refusal.code,
        message: messages[refusal.code as keyof typeof messages],
      },
    });

  await page.route(`${API_BASE}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/functions\/v1\/api/, "");
    const auth = await request.headerValue("authorization");
    calls.push({ method: request.method(), path, body: request.postDataJSON(), auth });
    if (auth !== `Bearer ${JWT}`) return refuse(route, refusals.noJwt);

    const installations = detail().installations.map((row) =>
      row.installationId === PENDING_ID && approved ? { ...row, status: "active" as const } : row,
    );
    const row = {
      ...storeRow(),
      pendingDevices: approved ? 0 : 1,
      activeDevices: approved ? 1 : 0,
    };
    const key = `${request.method()} ${path}`;
    switch (key) {
      case "GET /v1/admin/overview":
        return route.fulfill({ json: { ok: true, overview } });
      case "GET /v1/admin/stores":
        return route.fulfill({
          json: { ok: true, stores: created ? [row] : [], nextCursor: null },
        });
      case "POST /v1/admin/stores": {
        created = true;
        const response = JSON.parse(
          JSON.stringify(createStore.response)
            .replace('"{storeId}"', String(STORE_ID))
            .replace("{password}", PASSWORD),
        );
        return route.fulfill({ status: 201, json: response });
      }
      case `GET /v1/admin/stores/${STORE_ID}`:
        return route.fulfill({
          json: {
            ok: true,
            detail: { ...detail(), store: { ...detail().store, ...row }, installations },
          },
        });
      case "GET /v1/admin/audit":
        return route.fulfill({ json: { ok: true, events: [auditEvent()], nextCursor: null } });
      case "GET /v1/admin/installations":
        return route.fulfill({ json: { ok: true, installations: [], nextCursor: null } });
      case `POST /v1/admin/installations/${PENDING_ID}/approve`:
        approved = true;
        return route.fulfill({
          json: {
            ok: true,
            installation: { installationId: PENDING_ID, generation: 2, status: "active" },
          },
        });
      default:
        return route.fulfill({
          status: 404,
          json: { ok: false, code: "not_found", message: messages.not_found },
        });
    }
  });
  return calls;
}

test("sign in, create a store, open it and approve its device", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  const calls = await mockBackend(page);

  await page.goto("/");
  await page.getByLabel("البريد الإلكتروني").fill("admin@sadad.test");
  await page.getByLabel("كلمة المرور").fill("correct horse battery");
  await page.getByRole("button", { name: "دخول آمن" }).click();

  await expect(page.getByText("admin@sadad.test")).toBeVisible();
  await expect(page.getByText("لا توجد حسابات متاجر")).toBeVisible();

  // Create the store from contract/fixtures/admin/create-store.json.
  await page.getByRole("button", { name: "إنشاء حساب متجر" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("اسم المتجر").fill(createStore.request.name);
  await dialog.getByLabel("اسم المستخدم").fill(createStore.request.username);
  await dialog.getByLabel("نوع الاشتراك").selectOption(createStore.request.subscriptionMode);
  await dialog
    .getByLabel("مدة الاشتراك بالأيام")
    .fill(String(createStore.request.subscriptionDays));
  await dialog.getByLabel("عدد الأجهزة المسموحة").fill(String(createStore.request.allowedDevices));
  await dialog.getByLabel("حد جهات الاتصال النشطة").fill(String(createStore.request.debtorLimit));
  await dialog.getByLabel("التصدير").uncheck();
  await dialog.getByRole("button", { name: "إنشاء الحساب" }).click();
  await expect(page.getByText(PASSWORD)).toBeVisible();
  const createCall = calls.find((c) => c.method === "POST" && c.path === "/v1/admin/stores");
  expect(createCall?.body).toMatchObject(createStore.request);

  // List → open store.
  const listed = page.getByRole("row", { name: /بقالة الزاوية/ });
  await expect(listed).toBeVisible();
  await listed.getByRole("button", { name: "إدارة" }).click();
  await expect(page.getByRole("heading", { name: "بقالة الزاوية" })).toBeVisible();
  await expect(page.getByText("بانتظار اعتمادك")).toBeVisible();

  // Approve the pending installation with a reason.
  await page.getByRole("button", { name: "اعتماد الهاتف" }).click();
  await page.getByRole("dialog").getByLabel("سبب الإجراء").fill("هاتف المتجر الجديد");
  await page.getByRole("dialog").getByRole("button", { name: "تأكيد" }).click();
  await expect(page.getByText("هاتف معتمد")).toBeVisible();
  const approveCall = calls.find((c) => c.path === `/v1/admin/installations/${PENDING_ID}/approve`);
  expect(approveCall?.body).toEqual({ reason: "هاتف المتجر الجديد" });

  // Every api call carried the admin's JWT, and the page ran clean under its CSP.
  expect(calls.every((c) => c.auth === `Bearer ${JWT}`)).toBe(true);
  expect(errors).toEqual([]);
});

test("refuses a store account at sign-in", async ({ page }) => {
  await page.route(`${SUPABASE_URL}/auth/v1/**`, (route) =>
    route.fulfill({
      json: {
        access_token: jwtFor("store"),
        token_type: "bearer",
        expires_in: 3600,
        expires_at: Math.floor(Date.now() / 1000) + 3600,
        refresh_token: "refresh-token",
        user: {
          id: "u",
          aud: "authenticated",
          app_metadata: { role: "store" },
          user_metadata: {},
          created_at: "",
        },
      },
    }),
  );
  await page.goto("/");
  await page.getByLabel("البريد الإلكتروني").fill("corner-shop@stores.sadad.invalid");
  await page.getByLabel("كلمة المرور").fill("whatever-password");
  await page.getByRole("button", { name: "دخول آمن" }).click();
  await expect(page.getByRole("alert")).toHaveText(
    messages[refusals.notAdmin.code as keyof typeof messages],
  );
});
