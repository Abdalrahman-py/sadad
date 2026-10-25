
===== Main =====
Sadad UX map
SADAD · UX MAP
<h1>One home per intent. One way to record.</h1>
Every screen answers one question a shop owner or cashier has. Nothing is shown in two places; exports and logs sit next to the data they describe.
الرئيسية
Where do I stand? Who do I chase?
«لك عند الناس» total, «عليك للناس», this month's debts and collection, overdue and due-today people with a WhatsApp reminder.
Removed: record buttons, recent strip, movements list.
الأشخاص
Find someone. What happened with them?
Search, filters (عليهم · لهم · متأخرون · موردون), sort. The contact page holds the timeline, statement PDF, corrections.
Removed: per-person statements in Reports and History.
«+» تسجيل
Record it now.
Person first, then amount. Debt or payment, customer or supplier. The only way to record anywhere in the app.
Was: «+» opened “new person”.
الحركات
What happened in the store?
Period (today by default), totals ديون · تحصيل · صافي, every entry by day with who recorded it, PDF / Excel export.
Merges History + Reports.
المتجر
Is everything working? Which store?
Store switcher, sync and unsent entries, operations log, recovery, lock, data, appearance, support. Its tab icon carries the sync dot.
Replaces Settings tab and avatar menu.
Money language — always from the owner's side
عليه ₪ 250
They owe you.
له ₪ 250
You hold their money: credit, or what you owe a supplier.
مسدد
Nothing open.
Word + arrow + colour, never colour alone. Numbers always LTR with ₪ in front. Screen readers hear «عليه مئتان وخمسون شيكل», never “minus”.
Credit, step by step (contact timeline shows the balance after each row)
دين ₪250 → عليه 250
دفعة ₪500 → له 250
دين ₪100 → له 150
Needs a ledger change (own ticket). Today the server rejects overpayment (overpayment) and every payment must point at a debt. Until that ships, the app caps payments at the open balance.
Core loop — 3 taps at the counter
«+»
اختيار خالد من الأحدث
كتابة 85
سجّل ₪85 على خالد
Person first. Search with the keyboard up; recent and frequent below; rows show phone tail so two «أحمد» never look alike; no match → create inline.
The button is the review. It says the whole sentence. A dialog appears only above 5,000 ₪. «تراجع» for 8 s, then «تصحيح» on the contact page; entries are corrected, never deleted.
Labels follow the person. Customer: «دين جديد / استلام دفعة». Supplier: «بضاعة بالدين / دفع للمورد». Red adds to a debt, green settles it.
Bad news before typing. If this device cannot save, Quick add says so first. Actions this device may not use are hidden from fast flows and shown locked on the contact page.
PROPS: {"$preview":{"width":1440,"height":1100}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== Nav =====
الشريط السفلي
<sc-for list="{{left}}" as="t" hint-placeholder-count="2">
<a href="{{t.href}}" aria-current="{{t.current}}">
{{t.label}}
</a>
</sc-for>
<a href="QuickAdd.dc.html" aria-label="تسجيل دين أو دفعة">
تسجيل
</a>
<sc-for list="{{right}}" as="t" hint-placeholder-count="2">
<a href="{{t.href}}" aria-current="{{t.current}}" aria-label="{{t.aria}}">
<sc-if value="{{t.dot}}" hint-placeholder-val="{{ false }}">
</sc-if>
{{t.label}}
</a>
</sc-for>
PROPS: {"active":{"editor":"enum","options":["home","people","activity","store"],"default":"home"},"sync":{"editor":"enum","options":["ok","pending","offline"],"default":"ok"},"dark":{"editor":"boolean","default":false},"$preview":{"width":390,"height":96}}
SCRIPT: class Component extends DCLogic {
theme(d) {
const t = d
? { bg: '#102721', bar: '#1C3931', primary: '#256C5A', link: '#78DEC0', text: '#F0F7EF', muted: '#B5CEC0', halo: 'rgba(120, 222, 192, 0.20)' }
: { bg: '#F5F8F3', bar: '#E7F3ED', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', halo: 'rgba(21, 157, 137, 0.22)' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
renderVals() {
const active = this.props.active ?? 'home';
const sync = this.props.sync ?? 'ok';
const tab = (id, label, href, icon) => {
const on = id === active;
return {
label, href, icon,
current: on ? 'page' : 'false',
ink: on ? 'var(--link)' : 'var(--muted)',
halo: on ? 'var(--halo)' : 'transparent',
weight: on ? 700 : 500,
dot: false, dotColor: 'transparent', aria: label,
};
};
const store = tab('store', 'المتجر', 'Store.dc.html', 'M4 10h16l-1.5-5h-13zM5 10v10h14V10M9 20v-5h6v5');
if (sync !== 'ok') {
store.dot = true;
store.dotColor = sync === 'offline' ? '#C13634' : '#E0A23A';
store.aria = sync === 'offline' ? 'المتجر، لا يوجد اتصال' : 'المتجر، إدخالات بانتظار الإرسال';
}
return {
vars: this.theme(this.props.dark ?? false),
left: [
tab('home', 'الرئيسية', 'Home.dc.html', 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z'),
tab('people', 'الأشخاص', 'People.dc.html', 'M9 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c.8-3.6 3.3-5.5 6.5-5.5s5.7 1.9 6.5 5.5M16 4.6a3.5 3.5 0 0 1 0 6.8M18 14.8c1.9.7 3.1 2.4 3.5 5.2'),
],
right: [
tab('activity', '

===== Auth =====
تسجيل الدخول
سدد
حسابات واضحة، وسجل مرتب، ومتابعة أسرع
<h1>تسجيل الدخول</h1>
أدخل حساب المتجر الذي أنشأه الأدمن
<label for="auth-user">اسم المستخدم</label>
<input id="auth-user" dir="auto" autocomplete="username" placeholder="store.owner" value="{{user}}" onChange="{{onUser}}">
<label for="auth-pass">كلمة المرور</label>
<input id="auth-pass" dir="auto" autocomplete="current-password" type="{{passType}}" placeholder="••••••••" value="{{pass}}" onChange="{{onPass}}">
<button type="button" aria-label="{{eyeLabel}}" onClick="{{toggleShow}}">
<sc-if value="{{show}}" hint-placeholder-val="{{ false }}">
</sc-if>
<sc-if value="{{hidden}}" hint-placeholder-val="{{ true }}">
</sc-if>
</button>
<a href="Home.dc.html">
دخول آمن
</a>
ليس لديك حساب بعد؟
<a href="#support">تواصل معنا لتفعيل حسابك</a>
يُسجَّل هذا الجهاز بعد الدخول، ويبدأ العمل حين يوافق صاحب المتجر عليه.
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { user: '', pass: '', show: false };
}
renderVals() {
const s = this.state;
return {
user: s.user,
pass: s.pass,
show: s.show,
hidden: !s.show,
passType: s.show ? 'text' : 'password',
eyeLabel: s.show ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور',
onUser: (e) => this.setState({ user: e.target.value }),
onPass: (e) => this.setState({ pass: e.target.value }),
toggleShow: () => this.setState({ show: !s.show }),
};
}
}

===== Home =====
الرئيسية
السبت، 25 أكتوبر
متجري
<a href="Notifications.dc.html" aria-label="{{bellLabel}}">
<sc-if value="{{hasBadge}}" hint-placeholder-val="{{ true }}">
2
</sc-if>
</a>
<sc-if value="{{offline}}" hint-placeholder-val="{{ false }}">
لا يوجد اتصال · 3 إدخالات بانتظار الإرسال
يمكنك متابعة التسجيل حتى 8:00 م؛ تُرسل تلقائيًا عند عودة الاتصال.
</sc-if>
<sc-if value="{{hasData}}" hint-placeholder-val="{{ true }}">
لك عند الناس
₪ 12,480.50
عليك للناس ₪ 1,240.00
<a href="Activity.dc.html">
هذا الشهر · ديون ₪ 3,200
تحصيل ₪ 1,950 · 14 دفعة
61٪ من ديون الشهر
</a>
<h2>تحتاج متابعة
3
</h2>
<a href="People.dc.html">عرض الكل</a>
<sc-for list="{{follow}}" as="f" hint-placeholder-count="3">
<a href="Contact.dc.html">
{{f.initials}}
{{f.name}}
{{f.status}}
عليه
₪ {{f.amount}}
</a>
<button type="button" aria-label="{{f.remindLabel}}">
</button>
</sc-for>
زر التذكير يفتح واتساب برسالة جاهزة فيها الرصيد، تراجعها قبل الإرسال.
</sc-if>
<sc-if value="{{empty}}" hint-placeholder-val="{{ false }}">
<h2>دفترك جاهز</h2>
سجّل أول دين خلال ثوانٍ: اختر الشخص، اكتب المبلغ، احفظ. يظهر هنا ما لك عند الناس ومن يحتاج متابعة.
<a href="QuickAdd.dc.html">
سجّل أول دين
</a>
<h2>بعد أول تسجيل</h2>
1
يظهر رصيد المتجر هنا: ما لك وما عليك.
2
من يتأخر عن موعده يظهر في «تحتاج متابعة» مع زر تذكير.
3
كل حركة تجدها في «الحركات»، وكشف كل شخص في صفحته.
</sc-if>
<dc-import name="Nav" active="home" dark="{{dark}}" sync="{{sync}}" hint-size="390px,96px"></dc-import>
PROPS: {"state":{"editor":"enum","options":["normal","empty","offline"],"default":"normal"},"dark":{"editor":"boolean","default":false},"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
theme(d) {
const t = d
? { bg: '#102721', bg2: '#142E25', surface: '#1C3931', soft: '#2A4E42', primary: '#256C5A', link: '#78DEC0', text: '#F0F7EF', muted: '#B5CEC0', line: '#375A4B', card: '#1C3931', owes: '#F59A9F', owesSoft: '#583B3D', credit: '#FFD08A', creditSoft: '#4A3A22', heroA: '#1F5E4F', heroB: '#02302B' }
: { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', owesSoft: '#FFE1E1', credit: '#9A5A13', creditSoft: '#FFF1DD', heroA: '#064B42', heroB: '#02302B' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
renderVals() {
const state = this.props.state ?? 'normal';
const dark = this.props.dark ?? false;
const empty = state === 'empty';
const follow = [
{ name: 'مريم حسن', initials: 'مح', status: 'متأخر 3 أيام', late: true, amount: '1,120.00' },
{ name: 'أحمد سالم', initials: 'أس', status: 'يستحق اليوم', late: false, amount: '250.00' },
{ name: 'خالد يوسف', initials: 'خي', status: 'يستحق اليوم', late: false, amount: '85.00' },
];
return {
vars: this.theme(dark),
dark: dark,
sync: state === 'offline' ? 'offline' : 'ok',
offline: state === 'offline',
hasData: !empty,
empty: empty,
hasBadge: !empty,
bellLabel: empty ? 'الإشعارات' : 'الإشعارات، 2 جديدة',
follow: follow.map((f, i) => ({
...f,
statusColor: f.late ? 'var(--credit)' : 'var(--muted)',
divider: i === 0 ? '0' : '1p

===== HomeEmpty =====
الرئيسية — متجر جديد
<dc-import name="Home" state="empty" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== HomeOffline =====
الرئيسية — بلا اتصال
<dc-import name="Home" state="offline" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== HomeDark =====
الرئيسية — الوضع الداكن
<dc-import name="Home" dark="{{ true }}" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== QuickAdd =====
تسجيل سريع
<sc-if value="{{showPicker}}" hint-placeholder-val="{{ true }}">
<a href="Home.dc.html" aria-label="إغلاق">
</a>
<h1>تسجيل لمن؟</h1>
<label for="qa-search">
<input id="qa-search" aria-label="ابحث بالاسم أو رقم الهاتف" placeholder="ابحث بالاسم أو رقم الهاتف" value="{{query}}" onChange="{{onQuery}}">
<sc-if value="{{hasQuery}}" hint-placeholder-val="{{ false }}">
<button type="button" aria-label="مسح البحث" onClick="{{clearQuery}}">
</button>
</sc-if>
</label>
<sc-if value="{{blocked}}" hint-placeholder-val="{{ false }}">
لا يمكن الحفظ الآن
انتهى تصريح الحفظ دون اتصال. اتصل بالإنترنت ثم سجّل.
<button type="button">إعادة الاتصال</button>
</sc-if>
{{listLabel}}
<sc-for list="{{results}}" as="p" hint-placeholder-count="6">
<button type="button" role="listitem" onClick="{{p.pick}}">
{{p.initials}}
{{p.name}}<sc-if value="{{p.supplier}}" hint-placeholder-val="{{ false }}">
مورد
</sc-if>
…{{p.tail}} · {{p.last}}
{{p.word}}
{{p.amountText}}
</button>
</sc-for>
<sc-if value="{{hasQuery}}" hint-placeholder-val="{{ false }}">
<button type="button" onClick="{{startNew}}">
إضافة «{{query}}» كشخص جديد
</button>
</sc-if>
<sc-if value="{{noResults}}" hint-placeholder-val="{{ false }}">
لا يوجد شخص بهذا الاسم أو الرقم. البحث يتجاهل الفرق بين أ وا وإ، وة وه، وى وي.
</sc-if>
</sc-if>
<sc-if value="{{isNew}}" hint-placeholder-val="{{ false }}">
<h2 id="qa-new-title">شخص جديد</h2>
<label for="qa-new-name">الاسم</label>
<input id="qa-new-name" value="{{newName}}" onChange="{{onNewName}}">
<label for="qa-new-phone">رقم الهاتف
(اختياري، للتذكير على واتساب)
</label>
<input id="qa-new-phone" dir="ltr" inputmode="tel" placeholder="05xxxxxxxx" value="{{newPhone}}" onChange="{{onNewPhone}}">
<button type="button" aria-pressed="{{newIsCustomer}}" onClick="{{setCustomer}}">زبون</button>
<button type="button" aria-pressed="{{newIsSupplier}}" onClick="{{setSupplier}}">مورد</button>
<sc-if value="{{duplicate}}" hint-placeholder-val="{{ false }}">
هذا الرقم مسجل باسم {{dupName}}.
<button type="button" onClick="{{useDuplicate}}">التسجيل لـ{{dupName}} بدلًا من ذلك</button>
</sc-if>
<button type="button" disabled="{{newDisabled}}" onClick="{{createNew}}">متابعة إلى المبلغ</button>
<button type="button" onClick="{{cancelNew}}">إلغاء</button>
التصنيف والملاحظات تُضاف لاحقًا من صفحة الشخص.
</sc-if>
<sc-if value="{{isAmount}}" hint-placeholder-val="{{ false }}">
<button type="button" aria-label="تغيير الشخص" onClick="{{backToPicker}}">
</button>
{{sel.initials}}
{{sel.name}}
{{sel.typeLabel}} · الآن {{sel.word}} {{sel.amountText}}
<button type="button" aria-pressed="{{isDebt}}" onClick="{{toDebt}}">{{debtLabel}}</button>
<button type="button" aria-pressed="{{isPay}}" onClick="{{toPay}}">{{payLabel}}</button>
₪
{{amountText}}
{{preview}}
<sc-if value="{{isDebt}}" hint-placeholder-val="{{ true }}">
<button type="button" aria-pressed="{{dueOn}}" onClick="{{toggleDue}}">
{{dueText}}
</button>
<button type="button" aria-pressed="{{noteOn}}" onClick="{{toggleNote}}">
{{noteText}}
</button>
</sc-if>
<sc-if value="{{isPay}}" hint-placeholder-val="{{ false }}">
<sc-for list="{{methods}}" as="m" hint-placeholder-count="3">
<button type="button" aria-pressed="{{m.selected}}" onClick="{{m.pick}}">{{m.label}}</button>
</sc-for>
</sc-if>
<button type="button" aria-label="إملاء صوتي">
</button>
<sc-for list="{{keys}}" as="k" hint-placeholder-count="12">
<button type="button" aria-label="{{k.aria}}" onClick="{{k.press}}">
<sc-if value="{{k.isDel}}" hint-placeholder-val="{{ false }}">
</sc-if>
<sc-if value="{{k.isChar}}" hint-placeholder-val="{{ true }}">{{k.label}}</sc-if>
</button>
</sc-for>
<button type="button" disabled="{{saveDisabled}}" onClick="{{save}}">{{saveText}}</button>
</sc-if>
<sc-if value="{{confirming}}" hint-placeholder-val="{{ false }}">
<h2 id="qa-anomaly">مبلغ أكبر من المعتاد</h2>
راجع القيمة قبل الحفظ: ₪ {{amountText}} لـ{{sel.name}}.
<button type="button" onClick="{{confirm}}">المبلغ صحيح، احفظ</button>
<button type="button" onClick="{{cancelConfirm}}">تعديل المبلغ</button>
</sc-if>
<sc-if value="{{isDone}}" hint-placeholder-val="{{ false }}">
<h2>{{doneTitle}}</h2>
{{doneLine}}
{{sel.name}} الآن: {{after.word}} {{after.amountText}}
<button type="button" onClick="{{undo}}">
تراجع
· 8 ث
</button>
<button type="button" onClick="{{again}}">تسجيل آخر</button>
<a href="Contact.dc.html">صفحة {{sel.first}}</a>
<a href="Home.dc.html">الرئيسية</a>
</sc-if>
PROPS: {"start":{"editor":"enum","options":["picker","new","amount","done"],"default":"picker"},"preset":{"editor":"enum","options":["debt","overpay","supplier"],"default":"debt"},"blocked":{"editor":"boolean","default":false},"dark":{"editor":"boolean","default":false},"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
const start = props.start ?? 'picker';
const preset = props.preset ?? 'debt';
const pre = { debt: ['k', 'debt', '85'], overpay: ['as', 'pay', '500'], supplier: ['b', 'debt', '300'] }[preset] || ['k', 'debt', '85'];
const picked = start === 'amount' || start === 'done';
this.state = {
step: start,
query: start === 'new' ? 'سامي' : '',
person: picked ? pre[0] : null,
mode: picked ? pre[1] : 'debt',
amount: picked ? pre[2] : '',
due: false, note: false, method: 'cash', confirming: false,
newName: 'سامي', newPhone: '', newType: 'c', extra: [],
};
}
theme(d) {
const t = d
? { bg: '#102721', bg2: '#142E25', surface: '#1C3931', soft: '#2A4E42', primary: '#256C5A', link: '#78DEC0', text: '#F0F7EF', muted: '#B5CEC0', line: '#375A4B', card: '#1C3931', owes: '#F59A9F', owesSoft: '#583B3D', owesBtn: '#F59A9F', owesInk: '#102721', credit: '#FFD08A', creditSoft: '#4A3A22', ghost: '#5E7A6E' }
: { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', owesSoft: '#FFE1E1', owesBtn: '#AB323A', owesInk: '#FFFFFF', credit: '#9A5A13', creditSoft: '#FFF1DD', ghost: '#8FA89B' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
fmt(n) {
return Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
norm(s) {
return s.replace(/[٠-٩]/g,

===== QuickAddNew =====
تسجيل سريع — شخص جديد
<dc-import name="QuickAdd" start="new" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== QuickAddAmount =====
تسجيل سريع — المبلغ
<dc-import name="QuickAdd" start="amount" preset="debt" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== QuickAddOverpay =====
تسجيل سريع — دفعة أكبر من الرصيد
<dc-import name="QuickAdd" start="amount" preset="overpay" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== QuickAddSupplier =====
تسجيل سريع — مورد
<dc-import name="QuickAdd" start="amount" preset="supplier" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== QuickAddDone =====
تسجيل سريع — تم الحفظ
<dc-import name="QuickAdd" start="done" preset="debt" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== People =====
الأشخاص
<h1>الأشخاص
7
</h1>
<button type="button" onClick="{{cycleSort}}" aria-label="{{sortAria}}">
{{sortLabel}}
</button>
<label for="people-search">
<input id="people-search" aria-label="ابحث بالاسم أو رقم الهاتف" placeholder="ابحث بالاسم أو رقم الهاتف" value="{{query}}" onChange="{{onQuery}}">
</label>
<sc-for list="{{filters}}" as="f" hint-placeholder-count="5">
<button type="button" aria-pressed="{{f.on}}" onClick="{{f.pick}}">{{f.label}}
{{f.count}}
</button>
</sc-for>
<sc-if value="{{hasTotal}}" hint-placeholder-val="{{ false }}">
{{totalLine}}
</sc-if>
<sc-for list="{{rows}}" as="p" hint-placeholder-count="7">
<a href="Contact.dc.html">
{{p.initials}}
{{p.name}}<sc-if value="{{p.supplier}}" hint-placeholder-val="{{ false }}">
مورد
</sc-if>
…{{p.tail}} · {{p.last}}
{{p.word}}
{{p.amountText}}
</a>
</sc-for>
<sc-if value="{{none}}" hint-placeholder-val="{{ false }}">
لا توجد نتائج
جرّب اسمًا أو رقمًا آخر، أو أضف الشخص من زر «تسجيل».
</sc-if>
<dc-import name="Nav" active="people" hint-size="390px,96px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { filter: 'all', sort: 'recent', query: '' };
}
theme() {
const t = { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', credit: '#9A5A13' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
fmt(n) {
return Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
norm(s) {
return s.replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x0660)).replace(/[أإآ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي').trim();
}
renderVals() {
const s = this.state;
const all = [
{ name: 'خالد يوسف', phone: '0599123482', type: 'c', bal: 85, last: 'اليوم 10:24', rank: 1 },
{ name: 'أحمد سالم', phone: '0598440905', type: 'c', bal: 250, last: 'أمس', rank: 2 },
{ name: 'مريم حسن', phone: '0599871117', type: 'c', bal: 1120, last: 'متأخر 3 أيام', late: true, rank: 3 },
{ name: 'أحمد ناصر', phone: '0597220331', type: 'c', bal: -150, last: 'قبل 5 أيام', rank: 4 },
{ name: 'مخبز الأمل', phone: '0592300208', type: 's', bal: -1240, last: 'قبل أسبوع', rank: 5 },
{ name: 'سارة محمود', phone: '0599335640', type: 'c', bal: 0, last: 'قبل أسبوعين', rank: 6 },
{ name: 'أبو رامي للخضار', phone: '0595118076', type: 's', bal: 0, last: 'الشهر الماضي', rank: 7 },
];
const tests = {
all: () => true,
owe: (p) => p.bal > 0,
credit: (p) => p.

===== Contact =====
صفحة الشخص
<a href="People.dc.html" aria-label="رجوع إلى الأشخاص">
</a>
<button type="button" aria-label="خيارات أخرى" aria-expanded="{{menuOpen}}" onClick="{{toggleMenu}}">
</button>
{{p.initials}}
<h1>{{p.name}}</h1>
{{p.typeLabel}} · {{p.phone}}
{{p.word}}
{{p.amountText}}
{{p.explain}}
<h2>الحركات</h2>
الرصيد بعد كل حركة
<sc-for list="{{items}}" as="it" hint-placeholder-count="7">
<sc-if value="{{it.isHead}}" hint-placeholder-val="{{ false }}">
{{it.label}}
</sc-if>
<sc-if value="{{it.isRow}}" hint-placeholder-val="{{ true }}">
<button type="button" aria-expanded="{{it.open}}" onClick="{{it.toggle}}">
{{it.title}}
{{it.meta}}
₪ {{it.amount}}
{{it.after}}
</button>
<sc-if value="{{it.open}}" hint-placeholder-val="{{ false }}">
{{it.who}}
<button type="button">{{it.fix}}</button>
</sc-if>
</sc-if>
</sc-for>
<a href="{{p.debtHref}}">
{{p.debtLabel}}
</a>
<sc-if value="{{payOpen}}" hint-placeholder-val="{{ true }}">
<a href="{{p.payHref}}">
{{p.payLabel}}
</a>
</sc-if>
<sc-if value="{{payLocked}}" hint-placeholder-val="{{ false }}">
اطلب من صاحب المتجر تفعيل الدفعات
</sc-if>
<sc-if value="{{menuOpen}}" hint-placeholder-val="{{ false }}">
<sc-for list="{{menu}}" as="m" hint-placeholder-count="4">
<button type="button" role="menuitem">
{{m.label}}
</button>
</sc-for>
</sc-if>
PROPS: {"who":{"editor":"enum","options":["credit","supplier"],"default":"credit"},"paymentsLocked":{"editor":"boolean","default":false},"dark":{"editor":"boolean","default":false},"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { open: 1, menu: false };
}
theme(d) {
const t = d
? { bg: '#102721', bg2: '#142E25', surface: '#1C3931', soft: '#2A4E42', primary: '#256C5A', link: '#78DEC0', text: '#F0F7EF', muted: '#B5CEC0', line: '#375A4B', card: '#1C3931', owes: '#F59A9F', owesSoft: '#583B3D', credit: '#FFD08A', creditSoft: '#4A3A22' }
: { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', owesSoft: '#FFE1E1', credit: '#9A5A13', creditSoft: '#FFF1DD' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
fmt(n) {
return Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
balWord(b) {
if (b > 0) return ['عليه ₪ ' + this.fmt(b), 'var(--owes)'];
if (b  0 ? ['عليه', 'var(--owes)', 'M12 19V5M5 12l7-7 7 7'] : (p.bal  this.setState({ menu: !this.state.menu }),
menu: menu,
payOpen: !locked,
payLocked: locked,
items: raw.map((r, i) => {
if (r.head) return { isHead: true, isRow: false, label: r.head };
const aw = this.balWord(r.after);
const open = this.state.open === i;
return {
isHead: false, isRow: true, open,
title: r.title, meta: r.meta, who: r.who,
amount: this.fmt(r.amount),
amountColor: r.debt ? 'var(--owes)' : 'var(--link)',
icon: r.debt ? PLUS : DOWN,
iconBg: r.debt ? 'var(--owesSoft)' : 'var(--soft)',
iconFg: r.debt ? '

===== ContactSupplier =====
صفحة مورد
<dc-import name="Contact" who="supplier" hint-size="390px,844px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}

===== Activity =====
الحركات
<h1>الحركات</h1>
<button type="button">
تصدير PDF / Excel
</button>
<sc-for list="{{periods}}" as="f" hint-placeholder-count="4">
<button type="button" aria-pressed="{{f.on}}" onClick="{{f.pick}}">{{f.label}}</button>
</sc-for>
ديون
₪ {{t.debts}}
{{t.debtsN}}
تحصيل
₪ {{t.paid}}
{{t.paidN}}
صافي
₪ {{t.net}}
{{t.netWord}}
للموردين في الفترة: بضاعة بالدين ₪ {{t.suppIn}} · دُفع ₪ {{t.suppOut}}
<button type="button">كل الأنواع </button>
<button type="button">كل الأجهزة والموظفين </button>
<sc-for list="{{feed}}" as="it" hint-placeholder-count="8">
<sc-if value="{{it.isHead}}" hint-placeholder-val="{{ false }}">
{{it.label}}
{{it.sum}}
</sc-if>
<sc-if value="{{it.isRow}}" hint-placeholder-val="{{ true }}">
<a href="Contact.dc.html">
{{it.name}}
{{it.kind}} · {{it.time}} · {{it.who}}
₪ {{it.amount}}
<sc-if value="{{it.pending}}" hint-placeholder-val="{{ false }}">
بانتظار الإرسال
</sc-if>
</a>
</sc-if>
</sc-for>
<dc-import name="Nav" active="activity" sync="pending" hint-size="390px,96px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { period: 'today' };
}
theme() {
const t = { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', link: '#064B42', primary: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', owesSoft: '#FFE1E1', credit: '#9A5A13' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
renderVals() {
const per = this.state.period;
const totals = {
today: { debts: '205.00', debtsN: 'عمليتان', paid: '500.00', paidN: 'دفعتان', net: '295.00', netWord: 'انخفض ما لك', suppIn: '0.00', suppOut: '450.00' },
week: { debts: '1,480.00', debtsN: '11 عملية', paid: '1,920.00', paidN: '9 دفعات', net: '440.00', netWord: 'انخفض ما لك', suppIn: '940.00', suppOut: '1,050.00' },
month: { debts: '3,200.00', debtsN: '28 عملية', paid: '1,950.00', paidN: '14 دفعة', net: '1,250.00', netWord: 'زاد ما لك', suppIn: '1,840.00', suppOut: '1,050.00' },
custom: { debts: '3,200.00', debtsN: '28 عملية', paid: '1,950.00', paidN: '14 دفعة', net: '1,250.00', netWord: 'زاد ما لك', suppIn: '1,840.00', suppOut: '1,050.00' },
};
const defs = [['today', 'اليوم'], ['week', 'هذا الأسبوع'], ['month', 'هذا الشهر'], ['custom', 'مخصص…']];
const PLUS = 'M12 5v14M5 12h14';
const DOWN = 'M12 4v11M7 10l5 5 5-5';
const UP = 'M12 20V9M7 14l5-5 5 5';
const raw = [
{ head: 'اليوم · السبت 25 أكتوبر', sum: '6 حركات' },
{ name: 'خالد يوسف', kind: 'دين', time: '10:24', who: 'سامر · جهاز الك

===== Store =====
المتجر
<h1>المتجر</h1>
متجري
صاحب المتجر: أبو خالد · هذا الهاتف
<button type="button" aria-expanded="{{switchOpen}}" onClick="{{toggleSwitch}}">تبديل</button>
<sc-if value="{{switchOpen}}" hint-placeholder-val="{{ false }}">
<button type="button">
سح
سوبر ماركت الحي
موظف
</button>
<button type="button">
إضافة متجر
</button>
</sc-if>
<h2>الأجهزة والمزامنة</h2>
إدخال واحد بانتظار الإرسال
دين سارة محمود ₪120 · يُرسل تلقائيًا · آخر مزامنة 10:24
<button type="button">مزامنة الآن</button>
<a href="#ops-log">
سجل عمليات الدفتر
من سجّل ماذا ومن أي جهاز، والمرفوض
</a>
<a href="Recovery.dc.html">
تغيير الهاتف والاسترداد
انقل الإدخالات غير المرسلة بأمان
</a>
<h2>الأمان</h2>
<sc-for list="{{switches}}" as="s" hint-placeholder-count="2">
{{s.label}}
{{s.sub}}
<button type="button" role="switch" aria-checked="{{s.on}}" aria-labelledby="{{s.id}}" onClick="{{s.flip}}">
</button>
</sc-for>
<a href="Lock.dc.html">تغيير رمز القفل</a>
<a href="PasswordChange.dc.html">تغيير كلمة المرور</a>
<h2>البيانات</h2>
<a href="Trash.dc.html">
سلة المحذوفات
عنصران · الكشوفات تُصدَّر من «الحركات»
</a>
<h2 id="theme-label">المظهر</h2>
<sc-for list="{{themes}}" as="t" hint-placeholder-count="3">
<button type="button" aria-pressed="{{t.on}}" onClick="{{t.pick}}">{{t.label}}</button>
</sc-for>
<a href="https://wa.me/">
تواصل معنا على واتساب
</a>
سدد · الإصدار 3.0
<dc-import name="Nav" active="store" sync="pending" hint-size="390px,96px"></dc-import>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { lock: true, bio: true, theme: 'system', switchOpen: false };
}
theme() {
const t = { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', soft: '#D6EFE4', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', creditSoft: '#FFF1DD' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
renderVals() {
const s = this.state;
const sw = (id, label, sub, key, first) => ({
id, label, sub, on: s[key],
divider: first ? '0' : '1px solid var(--line)',
track: s[key] ? 'var(--primary)' : '#B9C9BF',
// RTL switch: the thumb sits on the left when on, as Material mirrors it.
justify: s[key] ? 'flex-end' : 'flex-start',
flip: () => this.setState({ [key]: !s[key] }),
});
return {
vars: this.theme(),
switchOpen: s.switchOpen,
toggleSwitch: () => this.setState({ switchOpen: !s.switchOpen }),
switches: [
sw('sw-lock', 'قفل التطبيق', 'يُطلب الرمز عند العودة للتطبيق', 'lock', true),
sw('sw-bio', 'فتح بالبصمة أو الوجه', 'أسرع من كتابة الرمز', 'bio', false),
],
themes: [['system', 'حسب النظام'], ['light', 'فاتح'], ['dark', 'داكن']].map(([id, label]) => ({
label, on: s.theme === id,
bg: s.theme === id ? 'var(--primary)' : 'transparent',
fg: s.theme === id ? '#FFFFFF' : 'var(--muted)',
pick: () => this.setState({ theme: id }),
})),
};
}
}

===== Notifications =====
الإشعارات
<a href="Home.dc.html" aria-label="رجوع إلى الرئيسية">
</a>
<h1>الإشعارات</h1>
<sc-if value="{{showRejected}}" hint-placeholder-val="{{ true }}">
<h2>تحتاج إجراء</h2>
لم تُقبل: دفعة ₪ 120.00 من سارة محمود
الدفعة تتجاوز المتبقي على الدين. سُجّلت من جهاز الكاشير أمس 6:40 م، والرصيد لم يتغير.
<a href="Contact.dc.html">مراجعة سجل سارة</a>
<button type="button" onClick="{{dismiss}}">تجاهل</button>
</sc-if>
<h2>مواعيد الاستحقاق</h2>
<sc-for list="{{reminders}}" as="r" hint-placeholder-count="4">
{{r.name}} ·
عليه ₪ {{r.amount}}
{{r.when}}
<button type="button" aria-label="{{r.aria}}" onClick="{{r.remind}}">
{{r.btn}}
</button>
</sc-for>
تصلك تذكيرات الاستحقاق قبل الموعد بيوم وفي يومه. يمكن تغييرها من «المتجر».
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { rejected: true, sent: { 3: true } };
}
theme() {
const t = { bg: '#F5F8F3', bg2: '#EDF6EF', surface: '#E7F3ED', primary: '#064B42', link: '#064B42', text: '#142C24', muted: '#5B6F63', line: '#D5E5D9', card: '#FFFFFF', owes: '#AB323A', owesSoft: '#FFE1E1', credit: '#9A5A13' };
return Object.keys(t).map((k) => '--' + k + ':' + t[k]).join(';') + ';';
}
renderVals() {
const s = this.state;
const raw = [
{ name: 'مريم حسن', amount: '1,120.00', when: 'متأخر 3 أيام · كان يستحق 22 أكتوبر', late: true },
{ name: 'أحمد سالم', amount: '250.00', when: 'يستحق اليوم' },
{ name: 'خالد يوسف', amount: '85.00', when: 'يستحق اليوم' },
{ name: 'سارة محمود', amount: '120.00', when: 'يستحق غدًا · أُرسل التذكير 9:00 ص' },
];
return {
vars: this.theme(),
showRejected: s.rejected,
dismiss: () => this.setState({ rejected: false }),
reminders: raw.map((r, i) => {
const done = !!s.sent[i];
return {
...r,
opacity: done ? 0.6 : 1,
dot: done ? 'transparent' : (r.late ? 'var(--credit)' : 'var(--link)'),
whenColor: r.late ? 'var(--credit)' : 'var(--muted)',
btn: done ? 'أُرسل' : 'تذكير',
aria: 'تذكير ' + r.name + ' على واتساب',
remind: () => this.setState({ sent: { ...s.sent, [i]: true } }),
};
}),
};
}
}

===== Lock =====
التطبيق مقفل
<h1>متجري مقفل</h1>
أدخل رمز القفل، أو استخدم البصمة.
<sc-for list="{{dots}}" as="d" hint-placeholder-count="4">
</sc-for>
{{hint}}
<sc-for list="{{keys}}" as="k" hint-placeholder-count="12">
<button type="button" aria-label="{{k.aria}}" onClick="{{k.press}}">
<sc-if value="{{k.isBio}}" hint-placeholder-val="{{ false }}"></sc-if>
<sc-if value="{{k.isDel}}" hint-placeholder-val="{{ false }}"></sc-if>
<sc-if value="{{k.isDigit}}" hint-placeholder-val="{{ true }}">{{k.label}}</sc-if>
</button>
</sc-for>
<a href="{{openHref}}" aria-disabled="{{notReady}}">فتح التطبيق</a>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { pin: '' };
}
renderVals() {
const pin = this.state.pin;
const n = Math.max(4, pin.length);
const ready = pin.length >= 4;
const press = (k) => {
if (k === 'del') this.setState({ pin: pin.slice(0, -1) });
else if (k !== 'bio' && pin.length  ({ fill: i  ({
label: k,
isBio: k === 'bio', isDel: k === 'del', isDigit: k !== 'bio' && k !== 'del',
aria: k === 'bio' ? 'استخدام البصمة أو الوجه' : (k === 'del' ? 'حذف' : k),
bg: k === 'bio' || k === 'del' ? 'transparent' : '#FFFFFF',
shadow: k === 'bio' || k === 'del' ? 'none' : '0 1px 0 #D5E5D9',
press: () => press(k),
})),
notReady: !ready,
openHref: ready ? 'Home.dc.html' : '#',
openBg: ready ? '#064B42' : '#D6EFE4',
openFg: ready ? '#FFFFFF' : '#5B6F63',
};
}
}

===== PasswordChange =====
تغيير كلمة المرور المؤقتة
الخطوة 1 من 2 · أول دخول
<h1>اختر كلمة مرور خاصة بك</h1>
كلمة المرور الحالية مؤقتة من الأدمن. غيّرها مرة واحدة لتتابع.
<sc-for list="{{fields}}" as="f" hint-placeholder-count="3">
<label for="{{f.id}}">{{f.label}}</label>
<input id="{{f.id}}" dir="ltr" type="{{passType}}" autocomplete="{{f.auto}}" value="{{f.value}}" onChange="{{f.onChange}}">
</sc-for>
<label>
<input type="checkbox" checked="{{show}}" onChange="{{toggleShow}}">
إظهار كلمات المرور
</label>
<sc-for list="{{rules}}" as="r" hint-placeholder-count="3">
<sc-if value="{{r.ok}}" hint-placeholder-val="{{ false }}"></sc-if>
<sc-if value="{{r.notOk}}" hint-placeholder-val="{{ true }}"></sc-if>
{{r.label}}
{{r.sr}}
</sc-for>
<a href="{{nextHref}}" aria-disabled="{{notReady}}">حفظ ومتابعة</a>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { cur: 'Temp-2026', next: '', again: '', show: false };
}
renderVals() {
const s = this.state;
// Rules from the API contract: 12 to 128 characters, not the current one.
const rules = [
{ label: 'من 12 إلى 128 حرفًا', ok: s.next.length >= 12 && s.next.length  r.ok);
return {
passType: s.show ? 'text' : 'password',
show: s.show,
toggleShow: () => this.setState({ show: !s.show }),
fields: [
{ id: 'pw-cur', label: 'كلمة المرور المؤقتة', auto: 'current-password', value: s.cur, onChange: (e) => this.setState({ cur: e.target.value }) },
{ id: 'pw-new', label: 'كلمة المرور الجديدة', auto: 'new-password', value: s.next, onChange: (e) => this.setState({ next: e.target.value }) },
{ id: 'pw-again', label: 'أعد كتابتها', auto: 'new-password', value: s.again, onChange: (e) => this.setState({ again: e.target.value }) },
],
rules: rules.map((r) => ({ ...r, notOk: !r.ok, color: r.ok ? '#064B42' : '#5B6F63', sr: r.ok ? ' — متحقق' : ' — غير متحقق' })),
notReady: !ready,
nextHref: ready ? 'Approval.dc.html' : '#',
btnBg: ready ? '#064B42' : '#D6EFE4',
btnFg: ready ? '#FFFFFF' : '#5B6F63',
};
}
}

===== Approval =====
بانتظار موافقة الجهاز
الخطوة 2 من 2 · أول دخول
<h1>{{title}}</h1>
{{subtitle}}
<sc-for list="{{steps}}" as="st" hint-placeholder-count="3">
<sc-if value="{{st.done}}" hint-placeholder-val="{{ true }}"></sc-if>
<sc-if value="{{st.line}}" hint-placeholder-val="{{ true }}">
</sc-if>
{{st.label}}
{{st.sub}}
</sc-for>
<sc-if value="{{waiting}}" hint-placeholder-val="{{ true }}">
<label for="ap-staff">من يستخدم هذا الجهاز؟</label>
<input id="ap-staff" value="سامر">
<label for="ap-device">اسم الجهاز</label>
<input id="ap-device" value="جهاز الكاشير">
يظهر هذا الاسم لصاحب المتجر عند الموافقة، وبجانب كل حركة تسجلها.
نتحقق تلقائيًا كل 30 ثانية · آخر تحقق قبل 12 ث
<button type="button" onClick="{{check}}">تحقق الآن</button>
<a href="https://wa.me/">اطلب الموافقة من صاحب المتجر</a>
</sc-if>
<sc-if value="{{approved}}" hint-placeholder-val="{{ false }}">
<a href="Home.dc.html">ابدأ العمل</a>
</sc-if>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { approved: false };
}
renderVals() {
const ok = this.state.approved;
const step = (label, sub, done, current, line) => ({
label, sub, done, line,
bg: done ? '#064B42' : '#FFFFFF',
border: done ? '#064B42' : (current ? '#9A5A13' : '#D5E5D9'),
ink: done || current ? '#142C24' : '#5B6F63',
});
return {
title: ok ? 'تمت الموافقة على الجهاز' : 'بانتظار موافقة صاحب المتجر',
subtitle: ok ? 'يمكنك الآن التسجيل من هذا الجهاز.' : 'لحماية الدفتر، يعمل كل جهاز جديد بعد أن يوافق عليه صاحب المتجر.',
steps: [
step('دخلت إلى حساب المتجر', 'متجري', true, false, true),
step('سُجّل هذا الجهاز', 'مفتاح أمان خاص بالجهاز، لا يغادره', true, false, true),
step('موافقة صاحب المتجر', ok ? 'تمت الموافقة' : 'بانتظار الموافقة من لوحة الإدارة', ok, !ok, false),
],
waiting: !ok,
approved: ok,
check: () => this.setState({ approved: true }),
};
}
}

===== Trash =====
سلة المحذوفات
<a href="Store.dc.html" aria-label="رجوع إلى المتجر">
</a>
<h1>سلة المحذوفات</h1>
الأشخاص المؤرشفون يبقى سجلهم كاملًا. الاستعادة تعيدهم كما كانوا.
<sc-for list="{{items}}" as="it" hint-placeholder-count="2">
{{it.initials}}
{{it.name}}
{{it.kind}} · {{it.when}}
<button type="button" onClick="{{it.restore}}">استعادة</button>
<button type="button" onClick="{{it.ask}}">حذف نهائي</button>
</sc-for>
<sc-if value="{{empty}}" hint-placeholder-val="{{ false }}">
السلة فارغة
ما تؤرشفه من أشخاص أو إشعارات يظهر هنا.
</sc-if>
<sc-if value="{{toast}}" hint-placeholder-val="{{ false }}">
{{toast}}<a href="Contact.dc.html">فتح السجل</a>
</sc-if>
<sc-if value="{{asking}}" hint-placeholder-val="{{ false }}">
<h2 id="trash-confirm">حذف {{askName}} نهائيًا؟</h2>
لن يمكن استعادته بعد ذلك، ويختفي من الكشوفات القادمة.
<button type="button" onClick="{{confirmDelete}}">حذف نهائي</button>
<button type="button" onClick="{{cancel}}">إلغاء</button>
</sc-if>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
constructor(props) {
super(props);
this.state = { gone: {}, asking: null, toast: '' };
}
renderVals() {
const s = this.state;
const all = [
{ id: 'a', name: 'وليد عمر', initials: 'وع', kind: 'زبون وسجله · كان مسددًا', when: 'أُرشف قبل 4 أيام' },
{ id: 'b', name: 'تذكير: استحقاق مريم حسن', initials: 'تذ', kind: 'إشعار', when: 'حُذف أمس' },
];
const left = all.filter((x) => !s.gone[x.id]);
const asked = all.find((x) => x.id === s.asking);
return {
items: left.map((x) => ({
...x,
restore: () => this.setState({ gone: { ...s.gone, [x.id]: true }, toast: 'تمت استعادة ' + x.name }),
ask: () => this.setState({ asking: x.id }),
})),
empty: left.length === 0,
toast: s.toast,
asking: !!asked,
askName: asked ? asked.name : '',
confirmDelete: () => this.setState({ gone: { ...s.gone, [s.asking]: true }, asking: null, toast: '' }),
cancel: () => this.setState({ asking: null }),
};
}
}

===== Recovery =====
تغيير الهاتف والاسترداد
<a href="Store.dc.html" aria-label="رجوع إلى المتجر">
</a>
<h1>تغيير الهاتف والاسترداد</h1>
كل إدخالاتك مرسلة. ما على الخادم محفوظ، ولا تحتاج نسخة إلا إن كان لديك إدخالات بانتظار الإرسال.
<h2>أنتقل إلى هاتف جديد</h2>
1
على هذا الهاتف: احفظ نسخة مشفرة
تحمل الإدخالات غير المرسلة فقط، بعبارة مرور من 12 حرفًا على الأقل. احفظ الملف والعبارة خارج الهاتف.
<button type="button">حفظ نسخة مشفرة</button>
2
على الهاتف الجديد: استورد النسخة
بعد الدخول وموافقة صاحب المتجر على الهاتف الجديد.
<button type="button">استيراد نسخة استرداد</button>
خطوات يطلبها الأدمن
استخدمها فقط حين يطلبها منك الأدمن لاعتماد إدخالات من هاتف قديم.
<button type="button">تصدير قائمة اعتماد للإدارة</button>
<button type="button">إدخال تصريح الاسترداد</button>
تسجيل هذا الهاتف كجهاز جديد
متاح فقط بعد إلغاء اعتماد الجهاز. تبقى السجلات والإدخالات المعلقة محفوظة، ويحتاج الهاتف موافقة جديدة.
<button type="button" disabled="{{ true }}">الجهاز معتمد — لا حاجة لذلك</button>
PROPS: {"$preview":{"width":390,"height":844}}
SCRIPT: class Component extends DCLogic {
renderVals() {
return {};
}
}