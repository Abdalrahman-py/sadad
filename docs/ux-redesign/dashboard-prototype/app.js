// Sadad admin prototype. Vanilla JS, hash routes, sample data from data.js. The React build will replace this;
// it exists to approve the design: layout, words, states, phone composition.
(() => {
  const D = window.SAMPLE;
  const REPO = "Abdalrahman-py/sadad";
  const st = {
    role: "admin",
    navOpen: false,
    menu: null,
    q: "",
    filter: "all",
    secret: null,
    create: { name: "", username: "", plan: 30, paused: false, devices: 2, debtors: 300, done: null },
    ledgerReason: "",
    ledgerOpen: false,
    recovery: { step: 1, from: "", to: "", manifest: "", reason: "" },
    sub: { add: 30 },
    sort: { key: "days", dir: 1 },
    demo: "normal",
    auditKind: "all",
    versions: { loading: true, error: false, client: null, team: null },
  };

  // ---------- formatting ----------
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const int = (n) => new Intl.NumberFormat("en-US").format(n);
  const money = (cents) => `<span class="ltr">₪${new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(cents / 100)}</span>`;
  const dateFmt = new Intl.DateTimeFormat("ar-u-nu-latn", { day: "numeric", month: "long", year: "numeric" });
  const dateTimeFmt = new Intl.DateTimeFormat("ar-u-nu-latn", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
  const date = (ms) => dateFmt.format(new Date(ms));
  const ago = (ms) => `<span title="${dateTimeFmt.format(new Date(ms))}">${agoText(ms)}</span>`;
  const agoText = (ms) => {
    const m = Math.max(1, Math.round((Date.now() - ms) / 60000));
    if (m < 60) return m === 1 ? "منذ دقيقة" : m === 2 ? "منذ دقيقتين" : `منذ ${m} ${m <= 10 ? "دقائق" : "دقيقة"}`;
    const h = Math.round(m / 60);
    if (h < 24) return h === 1 ? "منذ ساعة" : h === 2 ? "منذ ساعتين" : `منذ ${h} ${h <= 10 ? "ساعات" : "ساعة"}`;
    const d = Math.round(h / 24);
    return d === 1 ? "أمس" : d === 2 ? "منذ يومين" : `منذ ${d} ${d <= 10 ? "أيام" : "يومًا"}`;
  };
  const days2 = (n) => (n === 1 ? "جهاز واحد" : n === 2 ? "جهازان" : n <= 10 ? `${n} أجهزة` : `${n} جهازًا`);
  const days = (n) => (n === 1 ? "يوم واحد" : n === 2 ? "يومان" : n <= 10 ? `${n} أيام` : `${n} يومًا`);
  const icon = (name) => `<i data-lucide="${name}"></i>`;
  const write = () => st.role === "admin";

  // ---------- domain ----------
  const store = (id) => D.stores.find((s) => s.id === Number(id));
  const expiresAt = (s) => (s.expiresInDays == null ? null : Date.now() + s.expiresInDays * 86400000);
  const statusOf = (s) => {
    if (s.archived) return { key: "archived", color: "grey", text: "مؤرشف" };
    if (s.status === "suspended") return { key: "suspended", color: "blue", text: "مجمّد" };
    if (s.expiresInDays == null) return { key: "active", color: "green", text: "نشط، دائم" };
    if (s.expiresInDays < 0) return { key: "over", color: "red", text: "منتهي" };
    if (s.expiresInDays <= 7) return { key: "soon", color: "amber", text: "ينتهي قريبًا" };
    return { key: "active", color: "green", text: "نشط" };
  };
  const status = (s) => {
    const x = statusOf(s);
    return `<span class="status"><span class="dot ${x.color}"></span>${x.text}</span>`;
  };
  const daysLeft = (s) => {
    if (s.archived) return `<span class="dash">—</span>`;
    if (s.expiresInDays == null) return `<span class="faint">دائم</span>`;
    if (s.expiresInDays < 0) return `<span class="bad-text">انتهى منذ ${days(-s.expiresInDays)}</span>`;
    return `<span class="${s.expiresInDays <= 7 ? "warn-text" : ""}">${int(s.expiresInDays)}</span>`;
  };
  const counts = () => {
    const c = { all: 0, active: 0, soon: 0, over: 0, suspended: 0, archived: 0 };
    for (const s of D.stores) {
      const k = statusOf(s).key;
      c[k]++;
      if (!s.archived) c.all++;
    }
    c.archived = D.stores.filter((s) => s.archived).length;
    return c;
  };
  const ending = () => D.stores.filter((s) => !s.archived && s.status !== "suspended" && s.expiresInDays != null && s.expiresInDays <= 7).sort((a, b) => a.expiresInDays - b.expiresInDays);

  // ---------- shell ----------
  // Routes live in memory: a claude.ai artifact keeps only plain #name anchors, so "#/stores/1" never reaches
  // location.hash there. A local server still opens a deep link on load.
  let ROUTE = location.hash.startsWith("#/") ? location.hash : "#/tasks";
  const go = (h) => {
    ROUTE = h; st.navOpen = false; st.menu = null; st.ledgerOpen = false;
    window.scrollTo(0, 0); render();
  };
  const routes = () => {
    const parts = ROUTE.split("?")[0].replace(/^#\/?/, "").split("/").filter(Boolean);
    return { name: parts[0] || "tasks", id: parts[1], tab: parts[2] || "overview" };
  };

  const KEYS = { "#/tasks": "G ثم T", "#/stores": "G ثم S", "#/create": "C", "#/audit": "G ثم A" };
  const navItem = (href, ic, label, current, count) =>
    `<a class="nav-item" href="${href}" title="${label} · ${KEYS[href]}" ${current ? 'aria-current="page"' : ""}>${icon(ic)}<span>${label}</span>${count ? `<span class="nav-count" aria-label="${count} تنتظر">${count}</span>` : ""}</a>`;

  const shell = (r, body) => {
    const pend = D.pending.length;
    const sec = r.name;
    return `
    <div class="shell ${st.navOpen ? "nav-open" : ""}">
      <header class="topbar">
        <a class="brand" href="#/tasks"><img class="brand-mark" src="logo.svg" alt="" width="28" height="28"><b>سدد</b></a>
        <div class="tail">
          <button class="btn btn-ghost btn-icon" data-act="focus-search" aria-label="بحث عن متجر">${icon("search")}</button>
          <button class="btn btn-ghost btn-icon" data-act="nav" aria-label="القائمة">${icon("menu")}</button>
        </div>
      </header>
      ${st.navOpen ? '<div class="scrim" data-act="nav"></div>' : ""}
      <aside class="sidebar" aria-label="التنقل">
        <div class="side-head">
          <a class="brand" href="#/tasks"><img class="brand-mark" src="logo.svg" alt="" width="28" height="28"><b>سدد</b><span>الإدارة</span></a>
          <button class="btn btn-ghost btn-icon btn-sm" data-act="focus-search" aria-label="بحث (/)" title="بحث  /">${icon("search")}</button>
          ${write() ? `<a class="btn btn-ghost btn-icon btn-sm" href="#/create" aria-label="إنشاء متجر" title="إنشاء متجر">${icon("square-pen")}</a>` : ""}
        </div>
        <nav class="nav-group">
          ${navItem("#/tasks", "list-checks", "المهام", sec === "tasks", pend)}
          ${navItem("#/stores", "store", "المتاجر", sec === "stores")}
          ${write() ? navItem("#/create", "plus", "إنشاء متجر", sec === "create") : ""}
        </nav>
        <div class="nav-label">أدوات أخرى</div>
        <nav class="nav-group">
          ${navItem("#/audit", "scroll-text", "سجل الإدارة", sec === "audit")}
        </nav>
        <div class="sidebar-foot">
          <div class="menu-wrap">
            <button class="user-row" data-act="menu" data-menu="user" aria-haspopup="menu" aria-expanded="${st.menu === "user"}">
              <span class="avatar">ع</span>
              <span class="who"><span>${esc(D.me.name)}</span><small>${write() ? "مسؤول: يقرأ ويعدّل" : "دعم فني: قراءة فقط"}</small></span>
            </button>
            ${st.menu === "user" ? `<div class="menu menu-up" role="menu">
              <div class="menu-label">للتجربة فقط</div>
              ${[["normal", "بيانات عادية"], ["loading", "حالة التحميل"], ["empty", "لا بيانات"], ["error", "خطأ في التحميل"]].map(([k, l]) => `<button role="menuitemradio" aria-checked="${st.demo === k}" data-act="demo" data-k="${k}">${icon(st.demo === k ? "check" : "dot")}${l}</button>`).join("")}
              <div class="sep"></div>
              <button role="menuitem" data-act="role">${icon(write() ? "eye" : "pencil")}${write() ? "عرض كحساب دعم فني" : "عرض كمسؤول"}</button>
              <div class="sep"></div>
              <a role="menuitem" href="#/signin">${icon("log-out")}تسجيل الخروج</a>
            </div>` : ""}
          </div>
        </div>
      </aside>
      <main class="canvas"><div class="sheet">
        <header class="sheet-head">
          ${head.title ? `<h1 class="head-title">${head.title}</h1>` : `<nav class="crumbs" aria-label="المسار">${crumbs(r)}</nav>`}
          <button class="head-search" data-act="palette" aria-label="بحث وأوامر (Ctrl K)">${icon("search")}<span>ابحث عن متجر أو انتقل…</span><kbd class="ltr">Ctrl K</kbd></button>
          ${head.actions}
        </header>
        <div class="page">
        ${write() ? "" : `<div class="banner">${icon("lock")}حساب الدعم الفني يرى كل شيء ولا يغيّر شيئًا. أزرار التعديل مخفية.</div>`}
        ${body}
        <p class="proto-note">نموذج للتصميم ببيانات تجريبية. أرقام الإصدارات حقيقية من GitHub.</p>
      </div></div></main>
    </div>`;
  };

  // Top-level pages put their h1 and primary action in the sheet's header bar.
  let head = { title: "", actions: "" };
  const pageHead = (title, actions, sub) => {
    head = { title, actions };
    return sub ? `<p class="lede">${sub}</p>` : "";
  };

  const uname = (u) => `<span class="line2 ltr copyable">${esc(u)}<button class="copy-mini" data-act="copy" data-text="${esc(u)}" aria-label="نسخ اسم المستخدم ${esc(u)}" title="نسخ">${icon("copy")}</button></span>`;

  // Extend: one tap for the usual 30 days, the chevron for longer periods (Stripe-style split button).
  const extendBtn = (s, sm) => `<span class="split">
      <button class="btn ${sm ? "btn-sm" : ""}" data-act="extend" data-id="${s.id}" data-n="30">${icon("calendar-plus")}تمديد 30 يومًا</button>
      <span class="menu-wrap"><button class="btn btn-icon ${sm ? "btn-sm" : ""}" data-act="menu" data-menu="ext-${s.id}" aria-label="مدة أخرى" aria-haspopup="menu" aria-expanded="${st.menu === `ext-${s.id}`}">${icon("chevron-down")}</button>
      ${st.menu === `ext-${s.id}` ? `<div class="menu" role="menu"><div class="menu-label">تمديد ${esc(s.name)}</div>${[90, 180, 365].map((n) => `<button role="menuitem" data-act="extend" data-id="${s.id}" data-n="${n}">${days(n)}<span class="menu-n">حتى ${date(Math.max(Date.now(), expiresAt(s) ?? Date.now()) + n * 86400000)}</span></button>`).join("")}</div>` : ""}</span>
    </span>`;

  // Loading and error stand-ins for any data block (st.demo previews them).
  const skeleton = (rows = 3) => `<div class="skeleton" aria-busy="true" aria-label="جارٍ التحميل">${Array.from({ length: rows }, () => `<div class="sk-row"><span class="sk sk-a"></span><span class="sk sk-b"></span><span class="sk sk-c"></span></div>`).join("")}</div>`;
  const failed = () => `<div class="empty" role="alert"><b>تعذّر تحميل البيانات</b><span>تحقّق من الاتصال ثم أعد المحاولة.</span><button class="btn btn-sm" data-act="retry">${icon("refresh-cw")}إعادة المحاولة</button></div>`;
  const stateOr = (fn) => (st.demo === "loading" ? skeleton() : st.demo === "error" ? failed() : fn());

  const crumbs = (r) => {
    // Top-level pages carry their own h1; the bar shows a path only where there is one.
    const s = r.name === "stores" && r.id ? store(r.id) : null;
    return s ? [`<a href="#/stores">المتاجر</a>`, `<span>${esc(s.name)}</span>`].join(icon("chevron-left")) : "";
  };

  // ---------- pieces ----------
  const kpi = (label, value, hint, href, dot) =>
    `<a class="kpi" href="${href}"><span class="kpi-label">${dot ? `<span class="dot ${dot}"></span>` : ""}${label}</span><span class="kpi-value">${value}</span>${hint ? `<span class="kpi-hint">${hint}</span>` : ""}</a>`;

  const versionsPanel = () => {
    const v = st.versions;
    const row = (title, who, data) => {
      if (v.loading) return `<div class="version"><div class="meta"><b>${title}</b><span class="faint small">جارٍ الجلب من GitHub…</span></div></div>`;
      if (!data) return `<div class="version"><div class="meta"><b>${title}</b><span class="small bad-text">تعذّر الجلب</span></div></div>`;
      return `<div class="version">
        <div class="meta"><b>${title}</b><span class="faint small">${who}</span>
          <span class="v ltr">${esc(data.version)}</span>
          <span class="faint small">نُشرت ${date(data.at)} · ${data.snapshot ? "لقطة محفوظة" : ago(data.at)}</span></div>
        <a class="btn btn-sm" href="${esc(data.url)}">${icon("download")}تنزيل APK</a>
      </div>`;
    };
    return `<section class="panel" aria-labelledby="ver-h">
      <div class="panel-head"><h2 id="ver-h">آخر نسخة من التطبيق</h2>
        <div class="tail"><button class="btn btn-ghost btn-icon btn-sm" data-act="versions" aria-label="تحديث">${icon("refresh-cw")}</button></div></div>
      ${row("نسخة الزبائن", "الإصدار الرسمي الذي يثبّته أصحاب المتاجر", v.client)}
      ${row("نسخة المطورين", "بناء الفريق من فرع dev للتجربة", v.team)}
      <div class="panel-note"><a class="link" href="https://github.com/${REPO}/releases">كل الإصدارات على GitHub</a></div>
    </section>`;
  };

  // GitHub allows 60 unauthenticated calls an hour per IP: keep the answer for 10 minutes.
  const loadVersions = async (force) => {
    try {
      const c = JSON.parse(localStorage.getItem("sadad.versions") || "null");
      if (!force && c && Date.now() - c.at < 600000) { st.versions = c.v; render(); return; }
    } catch {}
    st.versions = { loading: true, error: false, client: null, team: null };
    render();
    const get = (p) => fetch(`https://api.github.com/repos/${REPO}/releases/${p}`, { headers: { Accept: "application/vnd.github+json" } }).then((r) => (r.ok ? r.json() : Promise.reject(r.status)));
    const [client, team] = await Promise.allSettled([get("latest"), get("tags/team-latest")]);
    const apk = (rel) => rel.assets.find((a) => a.name.endsWith(".apk"));
    const snap = D.versionsSnapshot;
    st.versions = {
      loading: false,
      client: client.status === "fulfilled" && apk(client.value) ? { version: client.value.tag_name.replace(/^v/, ""), at: Date.parse(client.value.published_at), url: apk(client.value).browser_download_url } : null,
      team: team.status === "fulfilled" && apk(team.value) ? {
        version: (team.value.body.match(/الإصدار: `([^`]+)`/) || [, "team-latest"])[1].replace(/-team$/, ""),
        at: Date.parse(apk(team.value).updated_at),
        url: apk(team.value).browser_download_url,
      } : null,
    };
    // Offline or blocked (an artifact frame blocks GitHub): show the saved snapshot, marked as such.
    if (!st.versions.client) st.versions.client = snap.client && { ...snap.client, snapshot: true };
    if (!st.versions.team) st.versions.team = snap.team && { ...snap.team, snapshot: true };
    try { if (st.versions.client || st.versions.team) localStorage.setItem("sadad.versions", JSON.stringify({ at: Date.now(), v: st.versions })); } catch {}
    render();
  };

  const pendingTable = (rows, compact) => {
    if (!rows.length) return `<div class="empty"><b>لا أجهزة تنتظر</b><span>كل طلبات الأجهزة الجديدة تظهر هنا لتعتمدها أو ترفضها.</span></div>`;
    return `<div class="table-wrap"><table class="data stackable">
      <thead><tr><th>المتجر</th><th>الجهاز الجديد</th><th>وقت الطلب</th><th class="num">الأجهزة المعتمدة</th>${write() ? "<th></th>" : ""}</tr></thead>
      <tbody>${rows.map((d) => {
        const s = store(d.storeId);
        const full = s.activeDevices >= s.allowedDevices;
        return `<tr>
          <td class="id-cell"><a class="name" href="#/stores/${s.id}/devices">${esc(s.name)}</a>${uname(s.username)}</td>
          <td class="m" data-label="الجهاز">${esc(d.label)}<span class="line2">${d.staffName ? `الموظف: ${esc(d.staffName)}` : "بلا اسم موظف"}</span></td>
          <td class="lead faint">${ago(d.createdAt)}</td>
          <td class="m num" data-label="الأجهزة المعتمدة">${int(s.activeDevices)} <span class="of">من ${int(s.allowedDevices)}</span>${full ? ` <span class="warn-text small">الحد ممتلئ</span>` : ""}</td>
          ${write() ? `<td class="actions-cell"><span class="row-actions">
            ${full ? `<button class="btn btn-approve btn-sm" data-act="approve-raise" data-id="${d.installationId}">رفع الحد إلى ${int(s.allowedDevices + 1)} واعتماد</button>`
                   : `<button class="btn btn-approve btn-sm" data-act="approve" data-id="${d.installationId}">${icon("check")}اعتماد</button>`}
            <button class="btn btn-sm" data-act="reject" data-id="${d.installationId}">رفض</button>
          </span></td>` : ""}
        </tr>`;
      }).join("")}</tbody></table></div>`;
  };

  const endingTable = (rows) => {
    if (!rows.length) return `<div class="empty"><b>لا اشتراكات تنتهي هذا الأسبوع</b><span>المتاجر التي ينتهي اشتراكها خلال 7 أيام أو انتهى تظهر هنا.</span></div>`;
    return `<div class="table-wrap"><table class="data stackable">
      <thead><tr><th>المتجر</th><th>ينتهي في</th><th class="num">الأيام المتبقية</th>${write() ? "<th></th>" : ""}</tr></thead>
      <tbody>${rows.map((s) => `<tr>
        <td class="id-cell"><a class="name" href="#/stores/${s.id}/subscription">${esc(s.name)}</a>${uname(s.username)}</td>
        <td class="m" data-label="ينتهي في">${date(expiresAt(s))}</td>
        <td class="m num" data-label="الأيام المتبقية">${daysLeft(s)}</td>
        ${write() ? `<td class="actions-cell"><span class="row-actions">${extendBtn(s, true)}</span></td>` : ""}
      </tr>`).join("")}</tbody></table></div>`;
  };

  // ---------- screens ----------
  const tasksView = () => {
    const empty = st.demo === "empty";
    const loading = st.demo === "loading" || st.demo === "error";
    const c = empty ? { soon: 0, over: 0, active: 0, all: 0 } : counts();
    const end = empty ? [] : ending();
    const P = empty ? [] : D.pending;
    const oldest = P.reduce((a, d) => Math.min(a, d.createdAt), Date.now());
    const v = (x) => (loading ? '<span class="sk sk-kpi"></span>' : x);
    const finding = loading ? "" : P.length
      ? `${int(P.length)} ${P.length <= 10 ? "أجهزة" : "جهازًا"} ${P.length === 1 ? "ينتظر" : "تنتظر"} اعتمادك، أقدمها ${ago(oldest)}.`
      : "لا شيء ينتظرك الآن.";
    return `
      ${pageHead("المهام", `${write() ? `<a class="btn btn-primary btn-sm" href="#/create">${icon("plus")}إنشاء متجر</a>` : ""}`, finding)}
      <section class="panel kpis" aria-label="أرقام اليوم">
        ${kpi("أجهزة تنتظر الاعتماد", v(int(P.length)), "طلبات من هواتف جديدة", "#/tasks", P.length && !loading ? "amber" : "")}
        ${kpi("تنتهي خلال 7 أيام", v(int(c.soon)), "اشتراكات تحتاج تمديدًا", "#/stores?f=soon", c.soon && !loading ? "amber" : "")}
        ${kpi("اشتراكات منتهية", v(int(c.over)), "التطبيق متوقف عندهم", "#/stores?f=over", c.over && !loading ? "red" : "")}
        ${kpi("متاجر نشطة", v(`${int(c.active)}<small>من ${int(c.all)}</small>`), "غير المؤرشفة", "#/stores", "")}
      </section>
      <div class="grid-main">
        <div class="stack">
          <section class="panel" aria-labelledby="pend-h">
            <div class="panel-head"><h2 id="pend-h">أجهزة تنتظر الاعتماد</h2><span class="count">${loading ? "" : int(P.length)}</span></div>
            ${stateOr(() => pendingTable(P))}
          </section>
          <section class="panel" aria-labelledby="end-h">
            <div class="panel-head"><h2 id="end-h">اشتراكات تنتهي أو انتهت</h2><span class="count">${loading ? "" : int(end.length)}</span>
              <div class="tail"><a class="link small" href="#/stores">كل المتاجر</a></div></div>
            ${stateOr(() => endingTable(end))}
          </section>
        </div>
        <div class="stack">${versionsPanel()}</div>
      </div>`;
  };

  const FILTERS = [["all", "الكل", ""], ["active", "نشطة", "green"], ["soon", "تنتهي قريبًا", "amber"], ["over", "منتهية", "red"], ["suspended", "مجمّدة", "blue"], ["archived", "مؤرشفة", "grey"]];
  const storesView = () => {
    const c = counts();
    const q = st.q.trim().toLowerCase();
    const rows = D.stores.filter((s) => {
      const k = statusOf(s).key;
      const inFilter = st.filter === "all" ? !s.archived : st.filter === "archived" ? s.archived : k === st.filter;
      return inFilter && (!q || s.name.toLowerCase().includes(q) || s.username.includes(q)) && st.demo !== "empty";
    });
    const val = { days: (s) => s.expiresInDays ?? 1e9, devices: (s) => s.activeDevices, contacts: (s) => s.summary.activeContacts, debts: (s) => s.summary.openDebts, owed: (s) => s.summary.receivableCents }[st.sort.key];
    rows.sort((a, b) => (val(a) - val(b)) * st.sort.dir);
    const sortTh = (k, label, hint) => `<th class="num" aria-sort="${st.sort.key === k ? (st.sort.dir > 0 ? "ascending" : "descending") : "none"}"><button class="th-sort" data-act="sort" data-k="${k}">${label}${st.sort.key === k ? icon(st.sort.dir > 0 ? "arrow-up" : "arrow-down") : ""}</button>${hint ? `<span class="hint">${hint}</span>` : ""}</th>`;
    return `
      ${pageHead("المتاجر", `${write() ? `<a class="btn btn-primary btn-sm" href="#/create">${icon("plus")}إنشاء متجر</a>` : ""}`, "")}
      <div class="toolbar">
        <div class="menu-wrap">
          <button class="btn" data-act="menu" data-menu="filter" aria-haspopup="menu" aria-expanded="${st.menu === "filter"}">${icon("list-filter")}الحالة: ${FILTERS.find(([k]) => k === st.filter)[1]} <span class="faint">${int(c[st.filter])}</span>${icon("chevron-down")}</button>
          ${st.menu === "filter" ? `<div class="menu menu-start" role="menu">${FILTERS.map(([k, l, color]) => `<button role="menuitemradio" aria-checked="${st.filter === k}" data-act="filter" data-f="${k}"><span class="dot ${color || "none"}"></span>${l}<span class="menu-n">${int(c[k])}</span>${st.filter === k ? icon("check") : ""}</button>`).join("")}</div>` : ""}
        </div>
        ${st.q ? `<button class="btn btn-sm" data-act="clear-q">${icon("x")}البحث: ${esc(st.q)}</button>` : ""}
        <span class="muted small">${int(rows.length)} ${rows.length === 1 ? "متجر" : "متاجر"}</span>
      </div>
      <section class="panel wide">
        ${stateOr(() => rows.length ? `<table class="data stackable">
          <thead><tr>
            <th>المتجر</th><th>الحالة</th>${sortTh("days", "الأيام المتبقية")}<th>ينتهي في</th>
            ${sortTh("devices", "الأجهزة", "معتمدة من الحد")}
            ${sortTh("contacts", "الزبائن")}${sortTh("debts", "ديون مفتوحة")}${sortTh("owed", "لنا عند الزبائن")}${write() ? '<th><span class="sr-only">إجراءات</span></th>' : ""}
          </tr></thead>
          <tbody>${rows.map((s) => `<tr class="clickable ${s.archived ? "stamped" : ""}" data-href="#/stores/${s.id}">
            <td class="id-cell"><a class="name" href="#/stores/${s.id}">${esc(s.name)}</a>${uname(s.username)}</td>
            <td class="lead">${status(s)}</td>
            <td class="m num" data-label="الأيام المتبقية">${daysLeft(s)}</td>
            <td class="m hide-phone">${s.expiresInDays == null || s.archived ? '<span class="dash">—</span>' : date(expiresAt(s))}</td>
            <td class="m num" data-label="الأجهزة">${int(s.activeDevices)} <span class="of">من ${int(s.allowedDevices)}</span>${s.pendingDevices ? ` <span class="warn-text small">+${s.pendingDevices} ينتظر</span>` : ""}</td>
            <td class="m num" data-label="الزبائن">${int(s.summary.activeContacts)}</td>
            <td class="m num hide-phone">${int(s.summary.openDebts)}</td>
            <td class="m num" data-label="لنا عند الزبائن">${money(s.summary.receivableCents)}</td>
            ${write() ? `<td class="kebab-cell"><span class="menu-wrap"><button class="btn btn-ghost btn-icon btn-sm" data-act="menu" data-menu="row-${s.id}" aria-label="إجراءات ${esc(s.name)}" aria-haspopup="menu" aria-expanded="${st.menu === `row-${s.id}`}">${icon("ellipsis")}</button>
              ${st.menu === `row-${s.id}` ? `<div class="menu" role="menu">
                <a role="menuitem" href="#/stores/${s.id}">${icon("arrow-up-left")}فتح المتجر</a>
                ${s.expiresInDays == null || s.archived ? "" : `<button role="menuitem" data-act="extend" data-id="${s.id}" data-n="30">${icon("calendar-plus")}تمديد 30 يومًا</button>`}
                <button role="menuitem" data-act="new-code" data-id="${s.id}">${icon("key-round")}كلمة مرور مؤقتة</button>
                <button role="menuitem" data-act="copy" data-text="${esc(s.username)}">${icon("copy")}نسخ اسم المستخدم</button>
              </div>` : ""}</span></td>` : ""}
          </tr>`).join("")}</tbody></table>`
        : st.demo === "empty" ? `<div class="empty"><b>لا متاجر بعد</b><span>أنشئ أول متجر وأرسل بياناته لصاحبه.</span>${write() ? `<a class="btn btn-primary btn-sm" href="#/create">${icon("plus")}إنشاء متجر</a>` : ""}</div>`
        : `<div class="empty"><b>لا متاجر بهذه الحالة</b><span>اختر حالة أخرى من «الحالة».</span></div>`)}
      </section>`;
  };

  const STORE_TABS = [["overview", "نظرة عامة"], ["devices", "الأجهزة"], ["subscription", "الاشتراك"], ["recovery", "الاسترداد"]];
  const RARE = [["permissions", "sliders-horizontal", "الصلاحيات والحدود"], ["ledger", "book-open", "قراءة الدفتر"], ["activity", "history", "سجل نشاط المتجر"]];

  const storeView = (r) => {
    const s = store(r.id);
    if (!s) return `<div class="empty"><b>المتجر غير موجود</b><a class="link" href="#/stores">العودة إلى المتاجر</a></div>`;
    const x = statusOf(s);
    const rare = RARE.find(([k]) => k === r.tab);
    const devs = D.devices[s.id] || [];
    const head = `
      <div class="title-row"><h1>${esc(s.name)}</h1><span class="chip ${x.color}">${x.text}</span>
        <div class="actions">
          ${write() ? `${extendBtn(s)}
          <button class="btn" data-act="new-code" data-id="${s.id}">${icon("key-round")}كلمة مرور مؤقتة</button>` : ""}
          <div class="menu-wrap">
            <button class="btn btn-icon" data-act="menu" data-menu="rare" aria-label="أدوات أخرى" aria-haspopup="menu" aria-expanded="${st.menu === "rare"}">${icon("more-horizontal")}</button>
            ${st.menu === "rare" ? `<div class="menu" role="menu"><div class="menu-label">أدوات أخرى</div>
              ${RARE.map(([k, ic, l]) => `<a role="menuitem" href="#/stores/${s.id}/${k}">${icon(ic)}${l}</a>`).join("")}</div>` : ""}
          </div>
        </div>
        <p class="sub"><span class="ltr copyable">${esc(s.username)}<button class="copy-mini" data-act="copy" data-text="${esc(s.username)}" aria-label="نسخ اسم المستخدم" title="نسخ">${icon("copy")}</button></span> · أُنشئ في ${date(s.createdAt)}</p></div>
      ${st.secret && st.secret.storeId === s.id ? secretCard(st.secret) : ""}
      ${rare ? `<div class="crumb"><a href="#/stores/${s.id}">${icon("arrow-right")}العودة إلى المتجر</a></div>`
             : `<div class="tabs" role="tablist">${STORE_TABS.map(([k, l]) => `<a class="tab" role="tab" href="#/stores/${s.id}/${k}" aria-selected="${r.tab === k}">${l}${k === "devices" ? ` <span class="n">${int(s.activeDevices)} من ${int(s.allowedDevices)}</span>` : ""}</a>`).join("")}</div>`}`;
    const body = {
      overview: () => `
        <section class="panel kpis" aria-label="بيانات الدفتر">
          ${kpi("زبائن نشطون", int(s.summary.activeContacts), `من ${int(s.summary.contacts)} زبونًا مسجّلًا`, `#/stores/${s.id}`, "")}
          ${kpi("ديون مفتوحة", int(s.summary.openDebts), "لم تُسدّد بالكامل", `#/stores/${s.id}`, "")}
          ${kpi("لنا عند الزبائن", money(s.summary.receivableCents), "مجموع ما على الزبائن", `#/stores/${s.id}`, "")}
          ${kpi("علينا للزبائن", money(s.summary.payableCents), "رصيد دائن للزبائن", `#/stores/${s.id}`, "")}
        </section>
        <div class="grid-2">
          <section class="panel"><div class="panel-head"><h2>تفاصيل الحساب</h2></div><div class="panel-body"><dl class="kv">
            <dt>اسم المستخدم</dt><dd class="ltr">${esc(s.username)}</dd>
            <dt>الحالة</dt><dd>${status(s)}</dd>
            <dt>نوع الاشتراك</dt><dd>${s.subscriptionMode === "permanent" ? "دائم" : "بمدة"}</dd>
            <dt>ينتهي في</dt><dd>${s.expiresInDays == null ? '<span class="dash">—</span>' : date(expiresAt(s))}</dd>
            <dt>الأجهزة المعتمدة</dt><dd class="num">${int(s.activeDevices)} من ${int(s.allowedDevices)}</dd>
            <dt>حد الزبائن</dt><dd class="num">${int(s.debtorLimit)}</dd>
            <dt>الحساب موثّق</dt><dd>${s.verified ? "نعم" : '<span class="warn-text">لا</span>'}</dd>
          </dl></div></section>
          <section class="panel" aria-labelledby="act-h"><div class="panel-head"><h2 id="act-h">آخر النشاط</h2>
            <div class="tail"><a class="link small" href="#/stores/${s.id}/activity">السجل الكامل</a></div></div>
            ${feed(storeEvents(s).slice(0, 6))}</section>
        </div>
        ${write() ? dangerZone(s) : ""}`,
      devices: () => `<section class="panel">
        <div class="panel-head"><h2>الأجهزة</h2><span class="count">${int(s.activeDevices)} معتمدة من ${int(s.allowedDevices)} مسموحة</span></div>
        ${devs.length ? `<div class="table-wrap"><table class="data stackable">
          <thead><tr><th>الجهاز</th><th>الموظف</th><th>الحالة</th><th>أُضيف</th><th>آخر اتصال</th>${write() ? "<th></th>" : ""}</tr></thead>
          <tbody>${devs.map((d) => {
            const map = { active: ["green", "معتمد"], pending: ["amber", "ينتظر الاعتماد"], revoked: ["grey", "ملغى"] }[d.status];
            return `<tr class="${d.status === "revoked" ? "stamped" : ""}">
              <td class="id-cell"><span class="name">${esc(d.label)}</span><span class="line2 ltr">${esc(d.installationId)}</span></td>
              <td class="m" data-label="الموظف">${d.staffName ? esc(d.staffName) : '<span class="dash">—</span>'}</td>
              <td class="lead"><span class="status"><span class="dot ${map[0]}"></span>${map[1]}</span></td>
              <td class="m hide-phone">${date(d.createdAt)}</td>
              <td class="m" data-label="آخر اتصال">${d.lastSeen ? ago(d.lastSeen) : '<span class="dash">—</span>'}</td>
              ${write() ? `<td class="actions-cell"><span class="row-actions">${
                d.status === "pending" ? `<button class="btn btn-approve btn-sm" data-act="approve" data-id="${d.installationId}">${icon("check")}اعتماد</button><button class="btn btn-sm" data-act="reject" data-id="${d.installationId}">رفض</button>`
                : d.status === "active" ? `<button class="btn btn-sm" data-act="revoke" data-id="${s.id}">إلغاء الجهاز</button>` : ""}</span></td>` : ""}
            </tr>`;
          }).join("")}</tbody></table></div>`
          : `<div class="empty"><b>لا أجهزة بعد</b><span>أول هاتف يدخل بكلمة المرور المؤقتة يصبح الجهاز المعتمد الأول.</span></div>`}
      </section>`,
      subscription: () => `<div class="grid-main">
        <section class="panel"><div class="panel-head"><h2>الاشتراك الحالي</h2></div><div class="panel-body"><dl class="kv">
          <dt>الحالة</dt><dd>${status(s)}</dd>
          <dt>نوع الاشتراك</dt><dd>${s.subscriptionMode === "permanent" ? "دائم" : "بمدة"}</dd>
          <dt>ينتهي في</dt><dd>${s.expiresInDays == null ? '<span class="dash">—</span>' : date(expiresAt(s))}</dd>
          <dt>الأيام المتبقية</dt><dd class="num">${daysLeft(s)}</dd>
        </dl></div></section>
        ${write() ? `<section class="panel"><div class="panel-head"><h2>تمديد</h2></div><div class="panel-body stack">
          <div class="field"><label>أضف إلى تاريخ الانتهاء</label>
            <div class="seg">${[30, 90, 180, 365].map((n) => `<button type="button" aria-pressed="${st.sub.add === n}" data-act="sub-add" data-n="${n}">${days(n)}</button>`).join("")}</div>
            <span class="help">${s.expiresInDays == null ? "الاشتراك دائم، لا يحتاج تمديدًا." : `ينتهي بعد التمديد في ${date(Math.max(Date.now(), expiresAt(s)) + st.sub.add * 86400000)}.`}</span></div>
          <div><button class="btn btn-primary" data-act="extend-n" data-id="${s.id}" ${s.expiresInDays == null ? "disabled" : ""}>تمديد ${days(st.sub.add)}</button></div>
        </div></section>` : ""}
      </div>`,
      recovery: () => recoveryView(s, devs),
      permissions: () => permissionsView(s),
      ledger: () => ledgerView(s),
      activity: () => `<section class="panel"><div class="panel-head"><h2>سجل نشاط المتجر</h2></div>${auditTable(D.audit.filter((a) => a.store === s.name), true)}</section>`,
    };
    return head + (body[r.tab] || body.overview)();
  };

  const secretCard = (sec) => `
    <section class="panel" aria-labelledby="sec-h">
      <div class="panel-head"><h2 id="sec-h">بيانات الدخول لـ ${esc(sec.name)}</h2>
        <div class="tail"><button class="btn btn-ghost btn-sm" data-act="hide-secret">تم، إخفاء</button></div></div>
      <div class="panel-body stack">
        <div class="secret">
          <div class="secret-row"><span class="label">اسم المستخدم</span><span class="value">${esc(sec.username)}</span><button class="btn btn-sm" data-act="copy" data-text="${esc(sec.username)}">${icon("copy")}نسخ</button></div>
          <div class="secret-row"><span class="label">كلمة المرور المؤقتة</span><span class="value code">${esc(sec.code)}</span><button class="btn btn-sm" data-act="copy" data-text="${esc(sec.code)}">${icon("copy")}نسخ</button></div>
        </div>
        <div class="field"><label>رسالة جاهزة لصاحب المتجر</label><div class="message-preview">${esc(message(sec))}</div></div>
        <div class="toolbar"><button class="btn btn-primary" data-act="copy" data-text="${esc(message(sec))}">${icon("copy")}نسخ الرسالة</button>
          <a class="btn" href="https://wa.me/?text=${encodeURIComponent(message(sec))}" target="_blank" rel="noopener">${icon("message-circle")}إرسال عبر واتساب</a>
          <button class="btn" data-act="copy" data-text="${esc(appLink())}">${icon("link")}نسخ رابط التطبيق${st.versions.client ? ` <span class="faint ltr">${esc(st.versions.client.version)}</span>` : ""}</button>
        </div>
        <div class="toolbar">
          <span class="faint small">تظهر كلمة المرور مرة واحدة فقط. يغيّرها صاحب المتجر عند أول دخول، وأول هاتف يدخل بها يصبح معتمدًا.</span></div>
      </div>
    </section>`;

  const message = (sec) => `السلام عليكم، حساب «${sec.name}» في تطبيق سدد جاهز.
اسم المستخدم: ${sec.username}
كلمة المرور المؤقتة: ${sec.code}
سيطلب منك التطبيق كلمة مرور جديدة عند أول دخول.`;

  // Direct APK of the latest customer release; the releases/latest page until the API answers.
  const appLink = () => (st.versions.client ? st.versions.client.url : `https://github.com/${REPO}/releases/latest`);

  // Admin actions on this store plus its device events, newest first.
  const storeEvents = (s) => [
    ...D.audit.filter((a) => a.store === s.name).map((a) => ({ at: a.at, color: "grey", what: `${a.action} بواسطة ${a.actor}`, why: a.reason })),
    ...(D.devices[s.id] || []).flatMap((d) => [
      { at: d.createdAt, color: d.status === "pending" ? "amber" : "green", what: d.status === "pending" ? `${d.label} يطلب الاعتماد` : `أُضيف ${d.label}${d.staffName ? ` (${d.staffName})` : ""}` },
      ...(d.status === "revoked" ? [{ at: d.lastSeen, color: "red", what: `أُلغي ${d.label}` }] : []),
    ]),
    { at: s.createdAt, color: "green", what: "أُنشئ الحساب" },
  ].sort((a, b) => b.at - a.at);
  const feed = (events) => events.length
    ? `<ol class="feed">${events.map((e) => `<li><span class="dot ${e.color}"></span><span><span class="what">${esc(e.what)}</span>${e.why ? `<span class="why">السبب: ${esc(e.why)}</span>` : ""}</span><time datetime="${new Date(e.at).toISOString()}">${ago(e.at)}</time></li>`).join("")}</ol>`
    : `<div class="empty"><b>لا نشاط بعد</b></div>`;

  const dangerZone = (s) => `<section class="panel danger" aria-labelledby="dz-h">
    <div class="panel-head"><h2 id="dz-h">إجراءات حساسة</h2></div>
    <div class="danger-list">
      ${[
        [s.status === "suspended" ? "تفعيل المتجر" : "تجميد المتجر", s.status === "suspended" ? "يعود التطبيق للعمل على أجهزة المتجر." : "يتوقف التطبيق على كل أجهزة المتجر حتى تفعّله.", s.status === "suspended" ? "unfreeze" : "freeze"],
        ["إنهاء كل الجلسات", "تخرج كل الأجهزة ويدخل صاحب المتجر من جديد.", "end-sessions"],
        ["إلغاء كل الأجهزة", "تُلغى كل الأجهزة المعتمدة، ويحتاج كل هاتف اعتمادًا جديدًا.", "revoke-all"],
        [s.archived ? "إلغاء الأرشفة" : "أرشفة المتجر", "يختفي من القوائم ويبقى سجله كما هو.", "archive"],
      ].map(([t, d, a]) => `<div class="row"><div class="what"><b>${t}</b><span>${d}</span></div><button class="btn btn-sm" data-act="danger" data-what="${a}" data-title="${t}" data-id="${s.id}">${t}</button></div>`).join("")}
    </div></section>`;

  const recoveryView = (s, devs) => {
    const R = st.recovery;
    const step = (n, l) => `<span class="${R.step === n ? "on" : R.step > n ? "done" : ""}"><span class="n">${R.step > n ? icon("check") : n}</span>${l}</span>`;
    const opts = devs.map((d) => `<option value="${d.installationId}">${esc(d.label)} · ${esc(d.installationId)}</option>`).join("");
    return `<section class="panel"><div class="panel-head"><h2>نقل بيانات المتجر إلى هاتف جديد</h2></div>
      <div class="panel-body stack">
        <div class="steps">${step(1, "اختر الهاتفين")}${step(2, "تحقق من البيان")}${step(3, "السبب والتصريح")}</div>
        ${R.step === 1 ? `<div class="form-grid">
            <div class="field"><label for="rf">الهاتف القديم</label><select id="rf" class="select" data-bind="recovery.from"><option value="">اختر</option>${opts}</select></div>
            <div class="field"><label for="rt">الهاتف الجديد</label><select id="rt" class="select" data-bind="recovery.to"><option value="">اختر</option>${opts}</select></div></div>
          <div><button class="btn btn-primary" data-act="rec-next" ${write() ? "" : "disabled"}>التالي</button></div>`
        : R.step === 2 ? `<div class="field"><label for="rm">بيان الاسترداد من الهاتف القديم</label><textarea id="rm" class="textarea" dir="ltr" placeholder="الصق النص الذي يعرضه التطبيق" data-bind="recovery.manifest"></textarea><span class="help">يتحقق البيان من أن البيانات تخص هذا المتجر.</span></div>
          <div class="toolbar"><button class="btn" data-act="rec-back">رجوع</button><button class="btn btn-primary" data-act="rec-next">تحقق ومتابعة</button></div>`
        : `<div class="field"><label for="rr">سبب الاسترداد</label><input id="rr" class="input" placeholder="مثال: الهاتف القديم تعطل" data-bind="recovery.reason"><span class="help">يُسجَّل في سجل الإدارة.</span></div>
          <div class="toolbar"><button class="btn" data-act="rec-back">رجوع</button><button class="btn btn-primary" data-act="rec-done">إصدار تصريح الاسترداد</button></div>`}
      </div></section>`;
  };

  const permissionsView = (s) => {
    const perms = [["إضافة دين", "تسجيل دين جديد على زبون"], ["تسجيل دفعة", "قبض مبلغ من زبون"], ["تعديل وإلغاء", "تصحيح قيد أو إلغاؤه مع سبب"], ["إضافة زبون", "تسجيل زبون جديد"], ["التقارير", "عرض كشف الحساب ومشاركته"]];
    return `<div class="grid-main">
      <section class="panel"><div class="panel-head"><h2>صلاحيات أجهزة المتجر</h2></div>
        ${perms.map(([t, d], i) => `<div class="switch-row"><div class="what"><b>${t}</b><span>${d}</span></div><button class="switch" role="switch" aria-checked="true" aria-label="${t}" data-act="toggle" ${write() ? "" : "disabled"}></button></div>`).join("")}
      </section>
      <section class="panel"><div class="panel-head"><h2>الحدود</h2></div><div class="panel-body stack">
        <div class="field"><label for="pd">عدد الأجهزة المسموحة</label><input id="pd" class="input num" type="number" min="1" value="${s.allowedDevices}" ${write() ? "" : "disabled"}></div>
        <div class="field"><label for="pl">حد الزبائن</label><input id="pl" class="input num" type="number" min="1" value="${s.debtorLimit}" ${write() ? "" : "disabled"}><span class="help">أقصى عدد زبائن يمكن تسجيلهم.</span></div>
        ${write() ? `<div><button class="btn btn-primary" data-act="saved">حفظ</button></div>` : ""}
      </div></section></div>`;
  };

  const ledgerView = (s) => {
    if (!st.ledgerOpen) return `<section class="panel"><div class="panel-head"><h2>قراءة الدفتر</h2></div><div class="panel-body stack">
      <p class="muted">قراءة دفتر متجر تُسجَّل باسمك مع السبب، ويراها المالك في سجل الإدارة.</p>
      <div class="field"><label for="lr">لماذا تقرأ الدفتر؟</label><input id="lr" class="input" placeholder="مثال: صاحب المتجر يسأل عن دين مكرر" data-bind="ledgerReason" value="${esc(st.ledgerReason)}"></div>
      <div><button class="btn btn-primary" data-act="ledger-open" ${st.ledgerReason.trim().length < 4 ? "disabled" : ""}>عرض الدفتر</button></div></div></section>`;
    return `<section class="panel"><div class="panel-head"><h2>الديون</h2><span class="count">السبب: ${esc(st.ledgerReason)}</span></div>
      <div class="table-wrap"><table class="data stackable"><thead><tr><th>الزبون</th><th class="num">المبلغ</th><th class="num">المدفوع</th><th class="num">المتبقي</th><th>التاريخ</th><th>الحالة</th></tr></thead>
      <tbody>${D.ledgerDebts.map((d) => `<tr><td class="id-cell"><span class="name">${esc(d.contact)}</span></td><td class="m num" data-label="المبلغ">${money(d.amountCents)}</td><td class="m num hide-phone">${money(d.paidCents)}</td><td class="lead num">${money(d.amountCents - d.paidCents)}</td><td class="m" data-label="التاريخ">${date(d.createdAt)}</td><td class="m" data-label="الحالة">${d.status}</td></tr>`).join("")}</tbody></table></div></section>`;
  };

  const auditTable = (rows, noStore) => rows.length ? `<div class="table-wrap"><table class="data stackable">
    <thead><tr><th>الوقت</th><th>من</th><th>الإجراء</th>${noStore ? "" : "<th>المتجر</th>"}<th>السبب</th></tr></thead>
    <tbody>${rows.map((a) => `<tr><td class="lead faint">${ago(a.at)}</td><td class="m hide-phone">${esc(a.actor)}</td><td class="id-cell"><span class="name">${esc(a.action)}</span><span class="line2">${dateTimeFmt.format(new Date(a.at))}</span></td>${noStore ? "" : `<td class="m" data-label="المتجر">${esc(a.store)}</td>`}<td class="m" data-label="السبب">${a.reason ? esc(a.reason) : '<span class="dash">—</span>'}</td></tr>`).join("")}</tbody></table></div>`
    : `<div class="empty"><b>لا نشاط بعد</b></div>`;

  // Two sources, as the API keeps them: changes (control) and ledger reads (access).
  const AUDIT_KINDS = [["all", "كل الأحداث"], ["control", "تعديلات"], ["access", "قراءات الدفتر"]];
  const kindOf = (a) => (a.action.startsWith("قراءة") ? "access" : "control");
  const auditView = () => {
    const n = (k) => (k === "all" ? D.audit.length : D.audit.filter((a) => kindOf(a) === k).length);
    const rows = D.audit.filter((a) => st.auditKind === "all" || kindOf(a) === st.auditKind);
    const cur = AUDIT_KINDS.find(([k]) => k === st.auditKind);
    return `
    ${pageHead("سجل الإدارة", "", "كل تعديل وكل قراءة دفتر قام بها حساب إدارة أو دعم.")}
    <div class="toolbar">
      <div class="menu-wrap">
        <button class="btn" data-act="menu" data-menu="audit-kind" aria-haspopup="menu" aria-expanded="${st.menu === "audit-kind"}">${icon("list-filter")}النوع: ${cur[1]} <span class="faint">${int(n(st.auditKind))}</span>${icon("chevron-down")}</button>
        ${st.menu === "audit-kind" ? `<div class="menu menu-start" role="menu">${AUDIT_KINDS.map(([k, l]) => `<button role="menuitemradio" aria-checked="${st.auditKind === k}" data-act="audit-kind" data-k="${k}">${l}<span class="menu-n">${int(n(k))}</span>${st.auditKind === k ? icon("check") : ""}</button>`).join("")}</div>` : ""}
      </div>
    </div>
    <section class="panel">${stateOr(() => auditTable(st.demo === "empty" ? [] : rows))}</section>`;
  };

  const USER_RE = /^[a-z0-9](?:[a-z0-9-]{1,28}[a-z0-9])$/;
  const PLANS = [[30, "30 يومًا"], [90, "90 يومًا"], [365, "سنة"], ["permanent", "دائم"]];
  const createView = () => {
    const c = st.create;
    if (c.done) return `${pageHead(`تم إنشاء ${esc(c.done.name)}`, "", "")}
      <div class="narrow stack">
        ${secretCard(c.done, true)}
        <div class="toolbar"><a class="btn" href="#/stores/${c.done.storeId}">فتح صفحة المتجر</a><button class="btn btn-ghost" data-act="create-again">${icon("plus")}إنشاء متجر آخر</button></div>
      </div>`;
    const u = c.username;
    const taken = D.stores.some((s) => s.username === u);
    const uHelp = !u ? ["", "يكتبه صاحب المتجر عند الدخول، مثل amal-grocery. حروف إنجليزية صغيرة وأرقام وشرطة."]
      : taken ? ["err", "مستخدم من متجر آخر."] : USER_RE.test(u) ? ["ok", "متاح."] : ["err", "حروف إنجليزية صغيرة وأرقام وشرطة فقط، من 3 إلى 30، بلا شرطة في الطرفين."];
    const ok = c.name.trim().length >= 2 && USER_RE.test(u) && !taken;
    const ends = c.paused ? "موقوف حتى تمدده" : c.plan === "permanent" ? "اشتراك دائم" : `ينتهي في ${date(Date.now() + c.plan * 86400000)}`;
    return `
      ${pageHead("إنشاء متجر", "", "")}
      <form class="narrow panel" data-act="create-form" novalidate>
        <div class="panel-body stack">
          <div class="field"><label for="cn">اسم المتجر</label><input id="cn" class="input" placeholder="مثال: بقالة الأمل" value="${esc(c.name)}" data-bind="create.name" autocomplete="off" required></div>
          <div class="field"><label for="cu">اسم المستخدم</label><input id="cu" class="input" dir="ltr" autocomplete="off" spellcheck="false" value="${esc(u)}" data-bind="create.username" aria-describedby="cu-help" required><span class="help ${uHelp[0]}" id="cu-help">${uHelp[1]}</span></div>
          <div class="field"><span class="label" id="plan-l">الاشتراك</span>
            <div class="seg" role="radiogroup" aria-labelledby="plan-l">${PLANS.map(([k, l]) => `<button type="button" role="radio" aria-checked="${c.plan === k}" aria-pressed="${c.plan === k}" data-act="create-plan" data-k="${k}">${l}</button>`).join("")}</div>
            <span class="help">${ends}.</span></div>
          <details class="more" ${c.more ? "open" : ""}>
            <summary>${icon("chevron-left")}إعدادات إضافية <span class="faint">· ${days2(c.devices)}، ${int(c.debtors)} زبون، كل الصلاحيات${c.paused ? "، موقوف" : ""}</span></summary>
            <div class="stack more-body">
              <div class="form-grid">
                <div class="field"><label for="cd">الأجهزة المسموحة</label><input id="cd" class="input num" type="number" min="1" max="20" value="${c.devices}" data-bind="create.devices"></div>
                <div class="field"><label for="cl">حد الزبائن</label><input id="cl" class="input num" type="number" min="1" value="${c.debtors}" data-bind="create.debtors"></div>
              </div>
              <div class="switch-line"><div class="what"><b>إنشاء الحساب موقوفًا</b><span>لا يعمل التطبيق حتى تمدد الاشتراك، مثلًا قبل الدفع.</span></div><button type="button" class="switch" role="switch" aria-checked="${c.paused}" aria-label="إنشاء الحساب موقوفًا" data-act="create-paused"></button></div>
              <p class="faint small">الصلاحيات تُعدّل لاحقًا من «الصلاحيات والحدود» في صفحة المتجر.</p>
            </div>
          </details>
        </div>
        <div class="form-foot">
          <span class="faint small">بعد الإنشاء تحصل على كلمة مرور من 6 خانات ورسالة جاهزة.</span>
          <button class="btn btn-primary" type="submit" ${ok ? "" : "disabled"}>إنشاء المتجر</button>
        </div>
      </form>`;
  };


  const signinView = () => `<main class="signin"><div class="signin-box">
      <img class="brand-mark" src="logo.svg" alt="" width="44" height="44">
      <div><h1>دخول لوحة إدارة سدد</h1><p>للمسؤولين والدعم الفني فقط.</p></div>
      <form class="panel signin-form" data-act="signin">
        <div class="field"><label for="se">البريد الإلكتروني</label><input id="se" class="input" type="email" dir="ltr" autocomplete="username" required></div>
        <div class="field"><label for="sp">كلمة المرور</label>
          <div class="pw"><input id="sp" class="input" type="password" dir="ltr" autocomplete="current-password" required><button type="button" class="btn btn-ghost btn-icon btn-sm" data-act="toggle-pw" aria-label="إظهار كلمة المرور" aria-pressed="false">${icon("eye")}</button></div></div>
        <button class="btn btn-primary btn-block" type="submit">دخول</button>
      </form>
      <p class="proto-note">نموذج: أي بيانات تدخلك. أصحاب المتاجر يدخلون من التطبيق، لا من هنا.</p>
    </div></main>`;

  // ---------- render ----------
  function render() {
    const r = routes();
    const params = new URLSearchParams(ROUTE.split("?")[1] || "");
    if (params.get("f")) st.filter = params.get("f");
    const active = document.activeElement;
    const focusId = active && active.id;
    const sel = focusId && "selectionStart" in active ? [active.selectionStart, active.selectionEnd] : null;
    const app = document.getElementById("app");
    if (r.name === "signin") app.innerHTML = signinView();
    else {
      head = { title: "", actions: "" };
      const view = { tasks: tasksView, stores: () => (r.id ? storeView(r) : storesView()), create: createView, audit: auditView }[r.name] || tasksView;
      app.innerHTML = shell(r, view());
    }
    for (const el of document.querySelectorAll("[data-bind]")) {
      const v = el.dataset.bind.split(".").reduce((o, k) => o?.[k], st);
      if (el.tagName === "SELECT") el.value = v ?? "";
    }
    if (window.lucide) lucide.createIcons();
    if (focusId) {
      const el = document.getElementById(focusId);
      if (el) { el.focus(); if (sel && el.setSelectionRange) try { el.setSelectionRange(...sel); } catch {} }
    }
  }

  const toast = (text, action) => {
    const t = document.getElementById("toast");
    t.innerHTML = `${icon("check")}${esc(text)}${action ? `<a href="${action.href}">${esc(action.label)}</a>` : ""}`;
    t.hidden = false;
    if (window.lucide) lucide.createIcons();
    clearTimeout(toast.t);
    toast.t = setTimeout(() => (t.hidden = true), action ? 6000 : 2600);
  };

  const code = () => {
    const A = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    return Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => A[b & 31]).join("");
  };

  const dialog = ({ title, text, confirm, danger, username, reason }) => new Promise((resolve) => {
    const d = document.getElementById("dialog");
    d.innerHTML = `<form method="dialog">
      <h2>${esc(title)}</h2><p>${esc(text)}</p>
      ${reason ? `<div class="field"><label for="dr">السبب</label><input id="dr" class="input" required placeholder="يُسجَّل في سجل الإدارة"></div>` : ""}
      ${username ? `<div class="field"><label for="du">اكتب اسم المستخدم للتأكيد: <span class="ltr">${esc(username)}</span></label><input id="du" class="input" dir="ltr" autocomplete="off" required></div>` : ""}
      <div class="foot"><button class="btn" value="cancel" formnovalidate>إلغاء</button><button class="btn ${danger ? "btn-danger" : "btn-primary"}" value="ok" id="dok" ${username ? "disabled" : ""}>${esc(confirm)}</button></div>
    </form>`;
    const ok = d.querySelector("#dok");
    const check = () => {
      if (!username) return;
      const u = d.querySelector("#du").value.trim() === username;
      const rs = !reason || d.querySelector("#dr").value.trim().length > 2;
      ok.disabled = !(u && rs);
    };
    d.querySelectorAll("input").forEach((i) => i.addEventListener("input", check));
    d.onclose = () => resolve(d.returnValue === "ok");
    d.showModal();
  });

  // ---------- events ----------
  document.addEventListener("click", async (e) => {
    const row = e.target.closest("tr[data-href]");
    const el = e.target.closest("[data-act]");
    const link = e.target.closest('a[href^="#/"]');
    if (link && !e.defaultPrevented) { e.preventDefault(); go(link.getAttribute("href")); return; }
    if (!el && row && !e.target.closest("a,button")) { go(row.dataset.href); return; }
    if (!el) { if (st.menu && !e.target.closest(".menu")) { st.menu = null; render(); } return; }
    if (el.tagName === "FORM") return;
    const a = el.dataset.act;
    const id = el.dataset.id;
    const pend = (pid) => D.pending.find((d) => d.installationId === pid);
    const drop = (pid) => {
      const p = pend(pid);
      D.pending = D.pending.filter((d) => d.installationId !== pid);
      if (p) store(p.storeId).pendingDevices = Math.max(0, store(p.storeId).pendingDevices - 1);
      for (const list of Object.values(D.devices)) for (const d of list) if (d.installationId === pid) d.status = "_";
      return p;
    };
    switch (a) {
      case "nav": st.navOpen = !st.navOpen; break;
      case "menu": st.menu = st.menu === el.dataset.menu ? null : el.dataset.menu; break;
      case "role": st.role = write() ? "support" : "admin"; st.menu = null; break;
      case "filter": st.filter = el.dataset.f; st.menu = null; ROUTE = "#/stores"; break;
      case "approve": {
        const p = drop(id);
        for (const list of Object.values(D.devices)) for (const d of list) if (d.installationId === id) d.status = "active";
        if (p) store(p.storeId).activeDevices++;
        if (p) toast(`اعتُمد ${p.label} في ${store(p.storeId).name}`, { href: `#/stores/${p.storeId}/devices`, label: "عرض الأجهزة" });
        else toast("اعتُمد الجهاز");
        break;
      }
      case "approve-raise": {
        const p = drop(id); const s = store(p.storeId); s.allowedDevices++; s.activeDevices++;
        toast(`رُفع حد ${s.name} إلى ${s.allowedDevices} واعتُمد ${p.label}`, { href: `#/stores/${s.id}/devices`, label: "عرض الأجهزة" }); break;
      }
      case "reject": {
        if (!(await dialog({ title: "رفض الجهاز؟", text: "لن يتمكن هذا الهاتف من الدخول. يستطيع صاحبه الطلب مرة أخرى.", confirm: "رفض", danger: true }))) return;
        { const p = drop(id); toast(p ? `رُفض ${p.label} في ${store(p.storeId).name}` : "رُفض الجهاز"); } break;
      }
      case "extend": case "extend-n": {
        const s = store(id); const n = a === "extend" ? Number(el.dataset.n || 30) : st.sub.add; st.menu = null;
        s.expiresInDays = Math.max(0, s.expiresInDays ?? 0) + n; s.subscriptionActive = true;
        toast(`مُدّد اشتراك ${s.name} حتى ${date(expiresAt(s))}`, { href: `#/stores/${s.id}/subscription`, label: "الاشتراك" }); break;
      }
      case "sub-add": st.sub.add = Number(el.dataset.n); break;
      case "new-code": {
        const s = store(id);
        if (!(await dialog({ title: "كلمة مرور مؤقتة جديدة؟", text: "تخرج كل أجهزة المتجر، ويدخل صاحب المتجر بالكلمة الجديدة ثم يغيّرها.", confirm: "إنشاء كلمة مرور" }))) return;
        st.secret = { storeId: s.id, name: s.name, username: s.username, code: code() }; break;
      }
      case "hide-secret": st.secret = null; if (st.create.done) st.create.done = null; break;
      case "copy": try { await navigator.clipboard.writeText(el.dataset.text); } catch {} toast("نُسخ"); return;
      case "danger": {
        const s = store(id);
        if (!(await dialog({ title: `${el.dataset.title}: ${s.name}`, text: "هذا الإجراء يؤثر على صاحب المتجر مباشرة.", confirm: el.dataset.title, danger: true, username: s.username, reason: true }))) return;
        if (el.dataset.what === "freeze") s.status = "suspended";
        if (el.dataset.what === "unfreeze") s.status = "active";
        if (el.dataset.what === "archive") s.archived = !s.archived;
        toast("تم"); break;
      }
      case "revoke": {
        if (!(await dialog({ title: "إلغاء هذا الجهاز؟", text: "يخرج الهاتف فورًا ويحتاج اعتمادًا جديدًا ليعود.", confirm: "إلغاء الجهاز", danger: true }))) return;
        toast("أُلغي الجهاز"); break;
      }
      case "create-plan": st.create.plan = el.dataset.k === "permanent" ? "permanent" : Number(el.dataset.k); break;
      case "create-paused": st.create.paused = !st.create.paused; break;
      case "create": {
        const c = st.create; const nid = Math.max(...D.stores.map((s) => s.id)) + 1;
        D.stores.push({ ...D.stores[7], id: nid, name: c.name.trim(), username: c.username, allowedDevices: c.devices, debtorLimit: c.debtors, activeDevices: 0, pendingDevices: 0, verified: true, createdAt: Date.now(),
          subscriptionMode: c.plan === "permanent" ? "permanent" : "timed", expiresInDays: c.paused ? -1 : c.plan === "permanent" ? null : c.plan, summary: { contacts: 0, activeContacts: 0, openDebts: 0, receivableCents: 0, payableCents: 0, payments: 0 } });
        c.done = { storeId: nid, name: c.name.trim(), username: c.username, code: code() }; break;
      }
      case "create-again": st.create = { name: "", username: "", plan: 30, paused: false, devices: 2, debtors: 300, done: null }; break;
      case "ledger-open": st.ledgerOpen = true; break;
      case "rec-next": st.recovery.step = Math.min(3, st.recovery.step + 1); break;
      case "rec-back": st.recovery.step = Math.max(1, st.recovery.step - 1); break;
      case "rec-done": st.recovery.step = 1; toast("صدر تصريح الاسترداد، صالح 24 ساعة"); break;
      case "toggle": if (write()) el.setAttribute("aria-checked", el.getAttribute("aria-checked") !== "true"); return;
      case "saved": toast("حُفظ"); return;
      case "versions": loadVersions(true); return;
      case "demo": st.demo = el.dataset.k; st.menu = null; break;
      case "retry": st.demo = "normal"; break;
      case "audit-kind": st.auditKind = el.dataset.k; st.menu = null; break;
      case "toggle-pw": { const i = document.getElementById("sp"); const show = i.type === "password"; i.type = show ? "text" : "password"; el.setAttribute("aria-pressed", show); el.setAttribute("aria-label", show ? "إخفاء كلمة المرور" : "إظهار كلمة المرور"); return; }
      case "clear-q": st.q = ""; break;
      case "sort": { const k = el.dataset.k; st.sort = st.sort.key === k ? { key: k, dir: -st.sort.dir } : { key: k, dir: -1 }; break; }
      case "focus-search": openPalette(); return;
      case "palette": openPalette(); return;
      default: return;
    }
    render();
  });

  document.addEventListener("input", (e) => {
    const b = e.target.dataset.bind;
    if (!b) return;
    const keys = b.split(".");
    const last = keys.pop();
    const obj = keys.reduce((o, k) => o[k], st);
    obj[last] = e.target.type === "number" ? Number(e.target.value) : e.target.value;
    if ("global" in e.target.dataset && !ROUTE.startsWith("#/stores")) { st.filter = "all"; go("#/stores"); return; }
    if (e.target.tagName !== "SELECT") render();
  });

  document.addEventListener("toggle", (e) => { if (e.target.matches?.("details.more")) st.create.more = e.target.open; }, true);
  document.addEventListener("submit", (e) => {
    if (e.target.dataset.act === "signin") { e.preventDefault(); go("#/tasks"); }
    if (e.target.dataset.act === "create-form") { e.preventDefault(); const b = e.target.querySelector("[type=submit]"); if (!b.disabled) { b.dataset.act = "create"; b.click(); } }
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && !document.querySelector("dialog[open]") && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); openPalette(); }
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && (st.menu || st.navOpen)) { st.menu = null; st.navOpen = false; render(); } });
  window.addEventListener("hashchange", () => { if (location.hash.startsWith("#/")) go(location.hash); });


  // ---------- command palette (Ctrl/Cmd+K) and go-to shortcuts, as in Linear and Vercel ----------
  const COMMANDS = () => [
    { icon: "list-checks", label: "المهام", hint: "G ثم T", go: "#/tasks" },
    { icon: "store", label: "المتاجر", hint: "G ثم S", go: "#/stores" },
    ...(write() ? [{ icon: "plus", label: "إنشاء متجر", hint: "C", go: "#/create" }] : []),
    { icon: "scroll-text", label: "سجل الإدارة", hint: "G ثم A", go: "#/audit" },
    { icon: "clock", label: "المتاجر التي تنتهي قريبًا", go: "#/stores?f=soon" },
    { icon: "circle-alert", label: "المتاجر المنتهية", go: "#/stores?f=over" },
  ];
  const pal = { q: "", idx: 0, items: [] };
  const palItems = () => {
    const q = pal.q.trim().toLowerCase();
    const cmds = COMMANDS().filter((c) => !q || c.label.includes(q));
    const stores = D.stores.filter((s) => q && (s.name.toLowerCase().includes(q) || s.username.includes(q))).slice(0, 6)
      .map((s) => ({ icon: "store", label: s.name, sub: s.username, status: statusOf(s), go: `#/stores/${s.id}` }));
    return [...stores.map((x) => ({ ...x, group: "المتاجر" })), ...cmds.map((x) => ({ ...x, group: "انتقال" }))];
  };
  const renderPalette = () => {
    const d = document.getElementById("palette");
    pal.items = palItems();
    pal.idx = Math.min(pal.idx, Math.max(0, pal.items.length - 1));
    let group = "";
    d.innerHTML = `<div class="pal-search">${icon("search")}<input id="pq" class="pal-input" placeholder="ابحث عن متجر أو اكتب أمرًا" value="${esc(pal.q)}" role="combobox" aria-expanded="true" aria-controls="pl" aria-activedescendant="pi-${pal.idx}" autocomplete="off"><kbd>Esc</kbd></div>
      <div id="pl" class="pal-list" role="listbox">${pal.items.length ? pal.items.map((it, i) => {
        const head = it.group !== group ? `<div class="pal-group">${(group = it.group)}</div>` : "";
        return `${head}<a id="pi-${i}" role="option" class="pal-item" aria-selected="${i === pal.idx}" href="${it.go}" data-pal="${i}">${icon(it.icon)}<span class="pal-label">${esc(it.label)}${it.sub ? `<span class="ltr faint">${esc(it.sub)}</span>` : ""}</span>${it.status ? `<span class="status small"><span class="dot ${it.status.color}"></span>${it.status.text}</span>` : ""}${it.hint ? `<kbd>${it.hint}</kbd>` : ""}</a>`;
      }).join("") : `<div class="empty"><b>لا نتائج</b><span>جرّب اسم متجر أو اسم المستخدم.</span></div>`}</div>
      <div class="pal-foot"><span><kbd>↑</kbd><kbd>↓</kbd> تنقّل</span><span><kbd>Enter</kbd> فتح</span></div>`;
    if (window.lucide) lucide.createIcons();
    const inp = d.querySelector("#pq");
    inp.focus();
    inp.setSelectionRange(inp.value.length, inp.value.length);
    d.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
  };
  const openPalette = (q = "") => {
    const d = document.getElementById("palette");
    pal.q = q; pal.idx = 0;
    if (!d.open) d.showModal();
    renderPalette();
  };
  const runPal = (i) => {
    const it = pal.items[i];
    if (!it) return;
    document.getElementById("palette").close();
    go(it.go);
  };
  const palette = document.getElementById("palette");
  palette.addEventListener("input", (e) => { pal.q = e.target.value; pal.idx = 0; renderPalette(); });
  palette.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); pal.idx = (pal.idx + (e.key === "ArrowDown" ? 1 : -1) + pal.items.length) % Math.max(1, pal.items.length); renderPalette(); }
    if (e.key === "Enter") { e.preventDefault(); runPal(pal.idx); }
  });
  palette.addEventListener("click", (e) => {
    const a = e.target.closest("[data-pal]");
    if (a) { e.preventDefault(); runPal(Number(a.dataset.pal)); }
    else if (e.target === palette) palette.close(); // click on the backdrop
  });

  let pendingG = 0;
  document.addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); openPalette(); return; }
    if (document.querySelector("dialog[open]") || /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) || e.metaKey || e.ctrlKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (pendingG && Date.now() - pendingG < 1200) {
      const to = { t: "#/tasks", s: "#/stores", a: "#/audit" }[k];
      pendingG = 0;
      if (to) { e.preventDefault(); go(to); }
      return;
    }
    if (k === "g") pendingG = Date.now();
    if (k === "c" && write()) go("#/create");
  });
  render();
  loadVersions();
})();
