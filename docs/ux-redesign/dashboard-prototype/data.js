// Sample data for the prototype only. Shapes follow contract/openapi.json (AdminStore, AdminStoreSummary,
// AdminInstallation). Names and amounts are invented; nothing here is a real customer.
const DAY = 86400000;
const NOW = Date.now();

const S = (id, name, username, o) => ({
  id, name, username,
  status: "active", archived: false, verified: true,
  debtorLimit: 300, allowedDevices: 2, activeDevices: 1, pendingDevices: 0,
  subscriptionMode: "timed", subscriptionActive: true,
  createdAt: NOW - 40 * DAY, expiresInDays: 30,
  summary: { contacts: 0, activeContacts: 0, openDebts: 0, receivableCents: 0, payableCents: 0, payments: 0 },
  ...o,
});

window.SAMPLE = {
  me: { name: "عبد الرحمن", email: "admin", role: "admin" },
  stores: [
    S(1, "بقالة الأمل", "amal-grocery", { allowedDevices: 2, activeDevices: 2, pendingDevices: 1, expiresInDays: 23,
      summary: { contacts: 184, activeContacts: 171, openDebts: 96, receivableCents: 1284050, payableCents: 32000, payments: 1412 } }),
    S(2, "سوبرماركت النور", "alnoor-market", { allowedDevices: 3, activeDevices: 2, pendingDevices: 1, expiresInDays: 4,
      summary: { contacts: 402, activeContacts: 388, openDebts: 211, receivableCents: 3906575, payableCents: 120000, payments: 3820 } }),
    S(3, "مخبز الحارة", "hara-bakery", { subscriptionMode: "permanent", expiresInDays: null,
      summary: { contacts: 63, activeContacts: 60, openDebts: 22, receivableCents: 184500, payableCents: 0, payments: 410 } }),
    S(4, "صيدلية الشفاء", "shifa-pharmacy", { expiresInDays: 6, pendingDevices: 1,
      summary: { contacts: 129, activeContacts: 120, openDebts: 41, receivableCents: 922000, payableCents: 15000, payments: 655 } }),
    S(5, "ملحمة البركة", "baraka-butcher", { expiresInDays: -3, subscriptionActive: false,
      summary: { contacts: 77, activeContacts: 70, openDebts: 30, receivableCents: 410250, payableCents: 0, payments: 240 } }),
    S(6, "محل أبو خليل", "abu-khalil", { status: "suspended", expiresInDays: 51,
      summary: { contacts: 35, activeContacts: 35, openDebts: 12, receivableCents: 96000, payableCents: 0, payments: 88 } }),
    S(7, "خضار وفواكه الريف", "reef-veg", { expiresInDays: 112,
      summary: { contacts: 51, activeContacts: 48, openDebts: 19, receivableCents: 132600, payableCents: 4000, payments: 301 } }),
    S(8, "مكتبة القلم", "qalam-books", { expiresInDays: 19, activeDevices: 0, verified: false,
      summary: { contacts: 0, activeContacts: 0, openDebts: 0, receivableCents: 0, payableCents: 0, payments: 0 } }),
    S(9, "ميني ماركت السلام", "salam-mini", { expiresInDays: 64, allowedDevices: 1,
      summary: { contacts: 212, activeContacts: 205, openDebts: 133, receivableCents: 1755000, payableCents: 0, payments: 1990 } }),
    S(10, "دكان الجيران", "jeeran", { archived: true, status: "active", expiresInDays: -40, subscriptionActive: false, activeDevices: 0,
      summary: { contacts: 14, activeContacts: 0, openDebts: 0, receivableCents: 0, payableCents: 0, payments: 31 } }),
    S(11, "متجر التجربة (سوبرماركت)", "supermarket", { subscriptionMode: "permanent", expiresInDays: null, allowedDevices: 5, activeDevices: 3,
      summary: { contacts: 15, activeContacts: 15, openDebts: 9, receivableCents: 54000, payableCents: 2500, payments: 70 } }),
    S(12, "حلويات الشام", "sham-sweets", { expiresInDays: 88,
      summary: { contacts: 46, activeContacts: 44, openDebts: 10, receivableCents: 61800, payableCents: 0, payments: 150 } }),
  ],
  pending: [
    { installationId: "inst-7f2a", storeId: 2, staffName: "محمود", label: "Galaxy A15", createdAt: NOW - 4 * 60000 },
    { installationId: "inst-91c0", storeId: 1, staffName: "سارة", label: "Redmi Note 13", createdAt: NOW - 38 * 60000 },
    { installationId: "inst-3be4", storeId: 4, staffName: "", label: "Oppo A78", createdAt: NOW - 3 * 3600000 },
  ],
  devices: {
    1: [
      { installationId: "inst-11aa", staffName: "أبو أحمد", label: "Galaxy A54", status: "active", createdAt: NOW - 39 * DAY, lastSeen: NOW - 20 * 60000 },
      { installationId: "inst-12bb", staffName: "أحمد", label: "Redmi 12", status: "active", createdAt: NOW - 12 * DAY, lastSeen: NOW - 2 * DAY },
      { installationId: "inst-91c0", staffName: "سارة", label: "Redmi Note 13", status: "pending", createdAt: NOW - 38 * 60000, lastSeen: null },
      { installationId: "inst-0f0f", staffName: "", label: "Galaxy A12", status: "revoked", createdAt: NOW - 35 * DAY, lastSeen: NOW - 30 * DAY },
    ],
  },
  audit: [
    { at: NOW - 15 * 60000, actor: "عبد الرحمن", action: "اعتماد جهاز", store: "ميني ماركت السلام", reason: "" },
    { at: NOW - 2 * 3600000, actor: "عبد الرحمن", action: "تمديد الاشتراك 30 يومًا", store: "حلويات الشام", reason: "" },
    { at: NOW - 26 * 3600000, actor: "عبد الرحمن", action: "قراءة الدفتر: الديون", store: "بقالة الأمل", reason: "الزبون يسأل عن دين مكرر" },
    { at: NOW - 30 * 3600000, actor: "عبد الرحمن", action: "تجميد المتجر", store: "محل أبو خليل", reason: "طلب صاحب المحل" },
    { at: NOW - 3 * DAY, actor: "عبد الرحمن", action: "إنشاء متجر", store: "مكتبة القلم", reason: "" },
  ],
  // Last known releases (2026-11-14), shown when the GitHub API cannot be reached.
  versionsSnapshot: {
    client: { version: "1.4.0", at: Date.parse("2026-11-09T16:37:00Z"), url: "https://github.com/Abdalrahman-py/sadad/releases/download/v1.4.0/Sadad-1.4.0-release.apk" },
    team: { version: "dev-136-04562fe", at: Date.parse("2026-11-11T12:00:00Z"), url: "https://github.com/Abdalrahman-py/sadad/releases/download/team-latest/Sadad-team.apk" },
  },
  ledgerDebts: [
    { contact: "خالد يوسف", amountCents: 12500, paidCents: 5000, createdAt: NOW - 3 * DAY, status: "مفتوح" },
    { contact: "أم محمد", amountCents: 4000, paidCents: 4000, createdAt: NOW - 6 * DAY, status: "مسدد" },
    { contact: "سامي ناصر", amountCents: 31000, paidCents: 0, createdAt: NOW - 9 * DAY, status: "مفتوح" },
    { contact: "رامي", amountCents: 2250, paidCents: 0, createdAt: NOW - 12 * DAY, status: "ملغى" },
  ],
};
