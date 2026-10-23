'use strict';
// واجهة وصال لسطح المكتب. بتنادي العقل عبر window.wisal.invoke بس.

async function call(ch, payload) {
  const r = await window.wisal.invoke(ch, payload);
  if (!r || !r.ok) throw new Error((r && r.error) || 'خطأ');
  return r.data;
}
const $ = (s, r = document) => r.querySelector(s);
const el = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstChild; };
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

let S = {}; // settings
let META = { relations: [], dialects: [], intents: [], groupKinds: [] };
let PEOPLE = [];
let GROUPS = [];
let currentSuggestions = null; // {items, themes, slot}
let selIntent = null;

function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), 2600);
}
function relLabel(id) { const r = META.relations.find((x) => x.id === id); return r ? r.label : id; }
function relEmoji(id) { const r = META.relations.find((x) => x.id === id); return r ? r.emoji : '👤'; }
function currentRecipient() { return PEOPLE.find((p) => p.id === S.selectedRecipientId) || PEOPLE[0] || null; }
function whoName(p) { return p ? (p.name || relLabel(p.relation)) : ''; }

function daysUntil(mmdd) {
  const m = /^(\d{2})-(\d{2})$/.exec(mmdd || ''); if (!m) return null;
  const now = new Date(); const y = now.getFullYear();
  let next = new Date(y, +m[1] - 1, +m[2]);
  const t0 = new Date(y, now.getMonth(), now.getDate());
  if (next < t0) next = new Date(y + 1, +m[1] - 1, +m[2]);
  return Math.round((next - t0) / 86400000);
}

// ---------- التنقّل ----------
function show(view) {
  ['home', 'skills', 'tools', 'broadcast', 'people', 'history', 'settings', 'onboard'].forEach((v) => {
    $('#view-' + v).classList.toggle('hidden', v !== view);
  });
  document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  if (view === 'home') renderHome();
  if (view === 'skills') renderSkills();
  if (view === 'tools') renderTools();
  if (view === 'broadcast') renderBroadcast();
  if (view === 'people') renderPeople();
  if (view === 'history') renderHistory();
  if (view === 'settings') renderSettings();
  updateStatus();
}

// شريط حالة النظام (LLM / Brain / Memory).
async function updateStatus() {
  const bar = $('#statusbar'); if (!bar) return;
  let st = {};
  try { st = await call('stats:get'); } catch (e) { st = {}; }
  bar.innerHTML = '';
  const pill = (html) => bar.appendChild(el('<span class="pill">' + html + '</span>'));
  pill(`<span class="dot ${st.hasKey ? '' : 'off'}"></span> LLM: <b>${esc((st.model || '').replace('-versatile', '').replace('-instant', ''))}</b>`);
  pill(`🧠 Brain: <b>${st.people || 0}</b> شخص`);
  pill(`🗄️ Memory: <b>${st.styleExamples || 0}</b> أمثلة أسلوب`);
  pill(`⭐ <b>${st.favorites || 0}</b> مفضّلة`);
  if (st.streak > 0) pill(`🔥 <b>${st.streak}</b> يوم دفء متواصل`);
}

// آخر رسالة اشتغل عليها الوكيل (للأدوات: نسخ/واتساب). بتتحدّث مع كل توليد/اختيار.
let lastMessage = '';
function setLastMessage(t) { if (t && t.trim()) lastMessage = t.trim(); }
async function resolveLastMessage() {
  if (lastMessage) return lastMessage;
  if (currentSuggestions && currentSuggestions.items[0]) return currentSuggestions.items[0].text;
  try { const st = await call('store:get'); const fb = (st.feedback || []).filter((f) => f.finalText); if (fb.length) return fb[fb.length - 1].finalText; } catch (e) { /* ignore */ }
  return '';
}

// 🧩 Skills — مهارات فعّالة: كل بطاقة بتشتغل بضغطة.
function renderSkills() {
  const host = $('#view-skills'); host.innerHTML = '';
  host.appendChild(el('<h1>🧩 Skills — المهارات</h1><p class="sub">دوس على أي مهارة وهي تشتغل على طول — النيّة بتوجّه الكتابة، والتحرير بيعدّل آخر اقتراح.</p>'));

  host.appendChild(el('<h2>نيّات الرسالة</h2>'));
  const g1 = el('<div class="mod-grid"></div>'); host.appendChild(g1);
  META.intents.forEach((it) => {
    const card = el(`<div class="mod"><div class="mod-top"><span class="mod-ico">${it.emoji}</span> ${esc(it.label)}</div><p>${esc(it.hint)}</p></div>`);
    const b = el('<button class="chip">✍️ اكتب بالنيّة دي</button>');
    b.onclick = async () => {
      if (!currentRecipient()) { toast('ضيف شخص في Brain الأول'); show('people'); return; }
      selIntent = it.id; show('home'); await doGenerate('manual');
    };
    card.appendChild(b); g1.appendChild(card);
  });

  host.appendChild(el('<h2>مهارات التحرير التكراري</h2>'));
  const g2 = el('<div class="mod-grid"></div>'); host.appendChild(g2);
  [['➕', 'أطول', 'يوسّع الرسالة ويدفّيها من غير حشو', 'longer'], ['➖', 'أقصر', 'يكثّف في سطر أو اتنين', 'shorter'], ['💘', 'أرومانسي', 'يزوّد الحنية من غير مبالغة', 'romantic'], ['🌿', 'أبسط', 'كلمات يومية أوضح', 'simpler']].forEach(([e2, l, d, sid]) => {
    const card = el(`<div class="mod"><div class="mod-top"><span class="mod-ico">${e2}</span> ${l}</div><p>${d}</p></div>`);
    const b = el('<button class="chip">✨ طبّق على آخر رسالة</button>');
    b.onclick = async () => {
      const base = await resolveLastMessage();
      if (!base) { toast('اعمل اقتراح الأول من Agents'); show('home'); return; }
      b.disabled = true;
      try { const nt = await call('refine', { text: base, styleId: sid }); setLastMessage(nt); openResult(l, nt); }
      catch (e) { toast('التعديل مش متاح دلوقتي (محتاج نت + مفتاح Groq)'); }
      b.disabled = false;
    };
    card.appendChild(b); g2.appendChild(card);
  });
}

// عرض ناتج مهارة في المودال مع أزرار نسخ/واتساب.
function openResult(title, text) {
  $('#modalTitle').textContent = title;
  const body = $('#modalBody'); body.innerHTML = '';
  body.appendChild(el(`<div style="white-space:pre-wrap;margin-bottom:12px">${esc(text)}</div>`));
  const row = el('<div class="row tight"></div>');
  const cp = el('<button class="ghost small">📋 نسخ</button>'); cp.onclick = () => { navigator.clipboard.writeText(text); toast('اتنسخت ✅'); };
  const wa = el('<button class="ghost small">📲 واتساب</button>'); wa.onclick = () => { sendWhatsApp(text, currentRecipient(), false); };
  row.append(cp, wa); body.appendChild(row);
  $('#modal').classList.remove('hidden');
}

// 🔧 Tools — أدوات فعّالة بضغطة (مفيش إرسال تلقائي لأي حد).
function renderTools() {
  const host = $('#view-tools'); host.innerHTML = '';
  host.appendChild(el('<h1>🔧 Tools — أدوات الوكيل</h1><p class="sub">كل أداة بتشتغل بضغطة منك — الرسالة تتجهّز وانت اللي تبعت.</p>'));
  const r = currentRecipient();
  const g = el('<div class="mod-grid"></div>'); host.appendChild(g);
  const desktopTools = [
    ['💬', 'WhatsApp', r ? 'يفتح شات ' + esc(whoName(r)) + ' والرسالة جاهزة' : 'يفتح واتساب والرسالة جاهزة', 'فتح', async () => {
      const t = await resolveLastMessage(); sendWhatsApp(t, currentRecipient(), false);
    }],
    ['📋', 'Clipboard', 'ينسخ آخر رسالة لأي مكان', 'نسخ', async () => {
      const t = await resolveLastMessage(); if (!t) { toast('مفيش رسالة لسه — اعمل اقتراح'); return; }
      navigator.clipboard.writeText(t); toast('اتنسخت ✅');
    }],
    ['🌐', 'Browser', 'يفتح لوحة مفاتيح Groq في متصفحك', 'فتح', async () => {
      await call('openExternal', { url: 'https://console.groq.com/keys' });
    }],
  ];
  desktopTools.forEach(([e2, n, d, btn, fn]) => {
    const card = el(`<div class="mod"><div class="mod-top"><span class="mod-ico">${e2}</span> ${n}</div><p>${d}</p></div>`);
    const b = el(`<button class="chip">${btn}</button>`); b.onclick = fn;
    card.appendChild(b); g.appendChild(card);
  });

  host.appendChild(el('<h2>أدوات الموبايل</h2><p class="sub">دي بتشتغل في نسخة الأندرويد (صلاحيات الجهاز).</p>'));
  const g2 = el('<div class="mod-grid"></div>'); host.appendChild(g2);
  [['📇', 'Contacts', 'استيراد جهات الاتصال + بثّ مخصّص باسم كل حد'], ['📅', 'Calendar', 'قراءة المناسبات من أجندتك'], ['🔔', 'Reminders', 'تذكير «ابعت بكرة» بالمواعيد']].forEach(([e2, n, d]) => {
    const card = el(`<div class="mod"><div class="mod-top"><span class="mod-ico">${e2}</span> ${n}</div><p>${d}</p><span class="tag">موبايل</span></div>`);
    const b = el('<button class="chip">📱 نزّل الأندرويد</button>');
    b.onclick = () => call('openExternal', { url: 'https://github.com/ahmadzayan-hub/desktop-tutorial/releases/download/android-latest/wisal.apk' });
    card.appendChild(b); g2.appendChild(card);
  });
}

// 📣 Groups / Broadcast — استيراد + مجموعات + تخصيص + طابور بضغطة لكل شخص.
let BC = { groupId: null, intentId: null, context: '', results: {}, busy: false, cc: '' };
function kindMeta(id) { return (META.groupKinds || []).find((k) => k.id === id) || { emoji: '📇', label: id }; }
function bcGroup() { return GROUPS.find((g) => g.id === BC.groupId) || null; }
async function saveGroups() { GROUPS = await call('groups:set', GROUPS); }

function renderBroadcast() {
  const host = $('#view-broadcast'); host.innerHTML = '';
  host.appendChild(el('<div class="banner"><h1>📣 Groups — إرسال جماعي مخصّص</h1><p>استورد ناسك أو اختارهم في مجموعة، والوكيل يكتب رسالة تخصّ كل واحد — وانت تبعت بضغطة لكل شخص (بدون بلاست تلقائي).</p></div>'));

  // شريط المجموعات
  const bar = el('<div class="card"></div>');
  bar.appendChild(el('<div class="muted" style="margin-bottom:8px">مجموعاتك</div>'));
  const chips = el('<div class="chips"></div>'); bar.appendChild(chips);
  GROUPS.forEach((g) => {
    const c = el(`<button class="chip ${g.id === BC.groupId ? 'sel' : ''}">${kindMeta(g.kind).emoji} ${esc(g.name)} · ${(g.members || []).length}</button>`);
    c.onclick = () => { BC.groupId = g.id; BC.results = {}; renderBroadcast(); };
    chips.appendChild(c);
  });
  const add = el('<button class="chip">➕ مجموعة جديدة</button>');
  add.onclick = async () => {
    const g = { id: 'g' + Date.now(), name: 'مجموعة جديدة', kind: 'work', members: [] };
    GROUPS.push(g); await saveGroups(); BC.groupId = g.id; BC.results = {}; renderBroadcast();
  };
  chips.appendChild(add);
  host.appendChild(bar);

  const g = bcGroup();
  if (!g) { host.appendChild(el('<p class="muted">ابدأ بإنشاء مجموعة (شغل/مشروع/أسرة) وضيف ناسها.</p>')); return; }

  // إعداد المجموعة
  if (BC.cc === '' || BC.cc == null) BC.cc = S.countryCode || '20';
  const setup = el('<div class="card depth"></div>');
  const nameRow = el('<label class="field"><span>اسم المجموعة</span><input type="text" id="gName"></label>');
  setup.appendChild(nameRow); $('#gName', nameRow).value = g.name;
  $('#gName', nameRow).onchange = async (e) => { g.name = e.target.value.trim() || g.name; await saveGroups(); };
  const ccRow = el('<label class="field"><span>كود الدولة الافتراضي (للأرقام المحلية الناقصة)</span><input type="text" id="gCc"></label>');
  setup.appendChild(ccRow); $('#gCc', ccRow).value = BC.cc;
  $('#gCc', ccRow).oninput = (e) => { BC.cc = e.target.value.replace(/\D/g, ''); S = Object.assign(S, { countryCode: BC.cc }); call('settings:set', { countryCode: BC.cc }); };
  setup.appendChild(el('<div class="muted" style="margin-bottom:4px">نوع المجموعة</div>'));
  const kchips = el('<div class="chips" style="margin-bottom:6px"></div>');
  (META.groupKinds || []).forEach((k) => {
    const c = el(`<button class="chip ${g.kind === k.id ? 'sel' : ''}">${k.emoji} ${esc(k.label)}</button>`);
    c.onclick = async () => { g.kind = k.id; await saveGroups(); renderBroadcast(); };
    kchips.appendChild(c);
  });
  setup.appendChild(kchips);
  // أزرار الاستيراد
  const imp = el('<div class="row" style="margin-top:6px"></div>');
  const csvBtn = el('<button class="ghost">📁 استيراد CSV</button>');
  csvBtn.onclick = async () => {
    try {
      const list = await call('csv:pick');
      if (!list) return;
      bcAddMembers(g, list); await saveGroups(); renderBroadcast(); toast('اتضاف ' + list.length + ' جهة ✅');
    } catch (e) { toast('تعذّر قراءة الملف'); }
  };
  const brainBtn = el('<button class="ghost">👥 من Brain</button>');
  brainBtn.onclick = async () => {
    if (!PEOPLE.length) { toast('مفيش ناس في Brain'); return; }
    bcAddMembers(g, PEOPLE.map((p) => ({ id: 'p_' + p.id, name: whoName(p), number: p.number || '', relation: p.relation, dialect: p.dialect || 'egyptian', tone: p.tone || '', notes: p.notes || '', relationship: relLabel(p.relation) })));
    await saveGroups(); renderBroadcast(); toast('اتضافوا من Brain ✅');
  };
  const manBtn = el('<button class="ghost">➕ يدوي</button>');
  manBtn.onclick = async () => { bcAddMembers(g, [{ id: 'm' + Date.now(), name: 'اسم جديد', number: '', relation: '', dialect: 'egyptian', tone: '', notes: '', relationship: '' }]); await saveGroups(); renderBroadcast(); };
  const delG = el('<button class="ghost">🗑️ حذف المجموعة</button>');
  delG.onclick = async () => { GROUPS = GROUPS.filter((x) => x.id !== g.id); await saveGroups(); BC.groupId = null; renderBroadcast(); };
  imp.append(csvBtn, brainBtn, manBtn, delG);
  setup.appendChild(imp);
  host.appendChild(setup);

  // الأعضاء
  host.appendChild(el(`<h2>الأعضاء (${(g.members || []).length})</h2>`));
  if (!(g.members || []).length) host.appendChild(el('<p class="muted">لسه مفيش حد. استورد CSV أو ضيف من Brain.</p>'));
  (g.members || []).forEach((m) => {
    const item = el(`<div class="list-item depth"><div class="person-card"><div><div class="name">${esc(m.name)}</div><div class="muted">${esc(m.number || 'بدون رقم')}${m.relationship ? ' · ' + esc(m.relationship) : ''}${m.dialect ? ' · ' + esc((META.dialects.find((d) => d.id === m.dialect) || {}).label || m.dialect) : ''}</div></div><div class="row tight"></div></div></div>`);
    const acts = $('.row', item);
    const an = el('<button class="ghost small">🔎 تحليل</button>'); an.onclick = () => openPersona(g, m);
    const ed = el('<button class="ghost small">✏️</button>'); ed.onclick = () => openMemberEdit(g, m);
    const rm = el('<button class="ghost small">🗑️</button>'); rm.onclick = async () => { g.members = g.members.filter((x) => x.id !== m.id); delete BC.results[m.id]; await saveGroups(); renderBroadcast(); };
    acts.append(an, ed, rm);
    host.appendChild(item);
  });
  if (!(g.members || []).length) return;

  // التأليف
  const comp = el('<div class="card depth"></div>');
  comp.appendChild(el('<div class="muted" style="margin-bottom:6px">نيّة الرسالة (اختياري)</div>'));
  const ich = el('<div class="chips" style="margin-bottom:10px"></div>');
  META.intents.forEach((it) => {
    const c = el(`<button class="chip ${BC.intentId === it.id ? 'sel' : ''}">${it.emoji} ${esc(it.label)}</button>`);
    c.onclick = () => { BC.intentId = BC.intentId === it.id ? null : it.id; renderBroadcast(); };
    ich.appendChild(c);
  });
  comp.appendChild(ich);
  const ctx = el('<label class="field"><span>سياق مشترك للكل (اختياري)</span><textarea id="bcCtx"></textarea></label>');
  comp.appendChild(ctx); $('#bcCtx', ctx).value = BC.context;
  const genRow = el('<div class="row"></div>');
  const genBtn = el(`<button class="btn">✨ جهّز رسالة مخصّصة لكل شخص (${g.members.length})</button>`);
  genBtn.onclick = () => bcGenerateAll(g);
  genRow.appendChild(genBtn);
  comp.appendChild(genRow);
  host.appendChild(comp);

  // النتائج
  const doneCount = g.members.filter((m) => BC.results[m.id]).length;
  if (doneCount) {
    const tools = el(`<div class="row" style="margin:6px 0 12px"><span class="grow muted">جاهز ${doneCount}/${g.members.length}</span></div>`);
    const copyAll = el('<button class="ghost small">📋 نسخ الكل</button>');
    copyAll.onclick = () => { navigator.clipboard.writeText(bcExportText(g)); toast('اتنسخ الكل ✅'); };
    const exp = el('<button class="ghost small">📤 تصدير ملف</button>');
    exp.onclick = async () => { const ok = await call('export:save', { filename: (g.name || 'group') + '-messages.txt', content: bcExportText(g) }); if (ok) toast('اتصدّر ✅'); };
    tools.append(copyAll, exp);
    host.appendChild(tools);
  }
  const out = el('<div id="bcOut"></div>'); host.appendChild(out);
  bcRenderResults(g);
}

function bcAddMembers(g, list) {
  g.members = g.members || [];
  const seen = new Set(g.members.map((m) => (m.number || '').replace(/\D/g, '')).filter(Boolean));
  list.forEach((m) => {
    const key = (m.number || '').replace(/\D/g, '');
    if (key && seen.has(key)) return;
    if (key) seen.add(key);
    g.members.push(Object.assign({ id: m.id || ('m' + Date.now() + '_' + g.members.length), dialect: 'egyptian', relation: '', tone: '', notes: '', relationship: '' }, m));
  });
}

function bcRenderResults(g) {
  const out = $('#bcOut'); if (!out) return; out.innerHTML = '';
  g.members.forEach((m) => {
    const txt = BC.results[m.id];
    if (!txt) return;
    const card = el(`<div class="suggestion depth"><span class="theme">${esc(m.name)}${m.number ? ' · ' + esc(m.number) : ''}</span></div>`);
    const ta = el('<textarea class="bc-msg"></textarea>'); ta.value = txt;
    ta.onchange = () => { BC.results[m.id] = ta.value; };
    card.appendChild(ta);
    const actions = el('<div class="row tight" style="margin-top:8px"></div>');
    const wa = el(`<button class="btn">📲 ابعت لـ${esc(m.name)}</button>`);
    wa.onclick = () => { const t = ta.value; setLastMessage(t); sendWhatsApp(t, { number: m.number, name: m.name }, false, BC.cc); };
    const cp = el('<button class="ghost">📋 نسخ</button>'); cp.onclick = () => { navigator.clipboard.writeText(ta.value); toast('اتنسخت ✅'); };
    actions.append(wa, cp);
    card.appendChild(actions);
    out.appendChild(card);
  });
}

const BC_MAX = 50; // سقف التوليد الجماعي في المرة الواحدة (حماية للوقت والتكلفة).
async function bcGenerateAll(g) {
  if (BC.busy) return;
  BC.context = $('#bcCtx') ? $('#bcCtx').value : BC.context;
  const targets = g.members.slice(0, BC_MAX);
  if (g.members.length > BC_MAX) toast('هجهّز أول ' + BC_MAX + ' — كرّر للباقي');
  BC.busy = true; BC.results = {};
  const out = $('#bcOut');
  let done = 0;
  for (const m of targets) {
    if (out) out.innerHTML = `<div class="card"><span class="spin"></span> بكتب رسائل مخصّصة... ${done}/${targets.length}</div>`;
    try { const r = await call('group:one', { member: m, intentId: BC.intentId, context: BC.context }); BC.results[m.id] = r.text; }
    catch (e) { BC.results[m.id] = ''; }
    done++;
  }
  BC.busy = false;
  toast('جهّزت ' + done + ' رسالة ✍️');
  renderBroadcast();
}

function bcExportText(g) {
  return g.members.filter((m) => BC.results[m.id]).map((m) => `— ${m.name}${m.number ? ' (' + m.number + ')' : ''}\n${BC.results[m.id]}`).join('\n\n');
}

// مودال تحليل شخصية العضو من معلومات ملصوقة (مش كشط سوشيال).
function openPersona(g, m) {
  $('#modalTitle').textContent = '🔎 تحليل شخصية — ' + m.name;
  const body = $('#modalBody'); body.innerHTML = '';
  body.appendChild(el('<p class="muted">الصق نبذة/معلومات عنه (أو محتوى من صفحته أنت ناسخه). البرنامج يحلّل النص ده بس — مايفتحش روابط ولا يكشط بيانات.</p>'));
  body.appendChild(el('<label class="field"><span>رابط كمرجع (اختياري)</span><input type="text" id="pmUrl" placeholder="https://..."></label>'));
  body.appendChild(el('<label class="field"><span>الصق معلومات عنه</span><textarea id="pmInfo" style="min-height:120px"></textarea></label>'));
  const row = el('<div class="row"></div>');
  const go = el('<button class="btn">✨ حلّل وخصّص</button>');
  const outp = el('<div class="muted" style="margin-top:10px"></div>');
  go.onclick = async () => {
    const info = $('#pmInfo').value.trim(); if (!info) { toast('الصق معلومات الأول'); return; }
    go.disabled = true; outp.innerHTML = '<span class="spin"></span> بحلّل...';
    try {
      const p = await call('persona:analyze', { info, url: $('#pmUrl').value.trim() });
      if (p) {
        if (p.dialect) m.dialect = p.dialect;
        if (p.tone) m.tone = p.tone;
        if (p.relationship) m.relationship = p.relationship;
        m.notes = [m.notes, p.summary].filter(Boolean).join(' — ');
        await saveGroups();
        outp.innerHTML = '✅ ' + esc(p.summary || 'اتحدّث') + '<br>اللهجة: ' + esc(p.dialect || '') + ' · النبرة: ' + esc(p.tone || '');
        toast('خصّصت ملفه 🌟');
      } else outp.textContent = 'مش قادر أحلّل دلوقتي.';
    } catch (e) { outp.textContent = 'محتاج نت + مفتاح Groq.'; }
    go.disabled = false;
  };
  row.appendChild(go); body.appendChild(row); body.appendChild(outp);
  $('#modal').classList.remove('hidden');
}

function openMemberEdit(g, m) {
  $('#modalTitle').textContent = '✏️ تعديل — ' + m.name;
  const body = $('#modalBody'); body.innerHTML = '';
  body.appendChild(el('<label class="field"><span>الاسم</span><input type="text" id="meName"></label>'));
  body.appendChild(el('<label class="field"><span>رقم واتساب (دولي بأرقام)</span><input type="text" id="meNum"></label>'));
  body.appendChild(el('<label class="field"><span>صفة العلاقة</span><input type="text" id="meRel"></label>'));
  body.appendChild(el('<label class="field"><span>ملاحظات</span><textarea id="meNotes"></textarea></label>'));
  body.appendChild(el('<div class="muted" style="margin-bottom:4px">اللهجة</div>'));
  const dch = el('<div class="chips" style="margin-bottom:10px"></div>');
  META.dialects.forEach((d) => { const c = el(`<button class="chip ${m.dialect === d.id ? 'sel' : ''}">${esc(d.label)}</button>`); c.onclick = () => { m.dialect = d.id; Array.from(dch.children).forEach((x, i) => x.classList.toggle('sel', META.dialects[i].id === m.dialect)); }; dch.appendChild(c); });
  body.appendChild(dch);
  $('#meName', body).value = m.name || ''; $('#meNum', body).value = m.number || '';
  $('#meRel', body).value = m.relationship || ''; $('#meNotes', body).value = m.notes || '';
  $('#sGroqEnabled', c).onchange = event => { S.groqEnabled = event.target.checked; };
  $('#sKey', c).oninput = event => { S.groqKey = event.target.value; };
  $('#sName', c).oninput = event => { S.myName = event.target.value; };
  const save = el('<button class="btn">💾 حفظ</button>');
  save.onclick = async () => {
    m.name = $('#meName').value.trim() || m.name; m.number = $('#meNum').value.trim().replace(/[^\d+]/g, '');
    m.relationship = $('#meRel').value.trim(); m.notes = $('#meNotes').value.trim();
    await saveGroups(); $('#modal').classList.add('hidden'); renderBroadcast(); toast('اتحفظ ✅');
  };
  body.appendChild(save);
  $('#modal').classList.remove('hidden');
}

function applyTheme() { document.documentElement.setAttribute('data-theme', S.theme === 'dark' ? 'dark' : 'light'); }
function updateWhoBadge() {
  const r = currentRecipient();
  $('#whoBadge').textContent = r ? ('✍️ بتكتب لـ ' + whoName(r)) : 'ضيف شخص عشان تبدأ';
}

// ---------- الرئيسية ----------
function renderHome() {
  const host = $('#view-home'); host.innerHTML = '';
  const r = currentRecipient();
  host.appendChild(el(`<div class="banner"><h1>🤖 Composer Agent</h1><p>${r ? 'الوكيل بيقترحلك رسالة لـ' + esc(whoName(r)) + '، تعدّلها وتبعتها بضغطة' : 'ضيف شخص في Brain عشان الوكيل يبدأ'}</p></div>`));

  // منتقي الأشخاص
  if (PEOPLE.length) {
    const bar = el('<div class="card"><div class="muted" style="margin-bottom:8px">بتكتب لـ</div><div class="chips" id="peopleChips"></div></div>');
    host.appendChild(bar);
    PEOPLE.forEach((p) => {
      const c = el(`<button class="chip person ${p.id === (r && r.id) ? 'sel' : ''}">${relEmoji(p.relation)} ${esc(whoName(p))}</button>`);
      c.onclick = async () => { S = await call('settings:set', { selectedRecipientId: p.id }); currentSuggestions = null; updateWhoBadge(); renderHome(); };
      $('#peopleChips', bar).appendChild(c);
    });
  }

  // مناسبات جاية
  const up = [];
  PEOPLE.forEach((p) => (p.occasions || []).forEach((o) => { const d = daysUntil(o.date); if (d != null && d <= 45) up.push({ p, o, d }); }));
  up.sort((a, b) => a.d - b.d);
  if (up.length) {
    const box = el('<div class="card"><h2 style="margin-top:0">🎀 مناسبات جايّة</h2><div id="upList"></div></div>');
    host.appendChild(box);
    up.slice(0, 4).forEach((u) => {
      const when = u.d === 0 ? 'النهاردة' : u.d === 1 ? 'بكرة' : 'بعد ' + u.d + ' يوم';
      const line = el(`<div class="row" style="margin-bottom:6px"><span class="grow">${esc(u.o.label)} لـ${esc(whoName(u.p))} · ${when}</span></div>`);
      const w = el('<button class="chip">✍️ اكتب</button>'); w.onclick = async () => { S = await call('settings:set', { selectedRecipientId: u.p.id }); await doGenerate('occasion', u.o.label); };
      const g = el('<button class="chip">🎁 أفكار</button>'); g.onclick = async () => { S = await call('settings:set', { selectedRecipientId: u.p.id }); openGiftIdeas(u.o.label); };
      line.appendChild(w); line.appendChild(g);
      $('#upList', box).appendChild(line);
    });
  }

  // النيّة + السياق + التوليد
  const gen = el('<div class="card"></div>');
  gen.appendChild(el('<div class="muted" style="margin-bottom:6px">نيّة الرسالة (اختياري)</div>'));
  const ichips = el('<div class="chips" style="margin-bottom:12px"></div>');
  META.intents.forEach((it) => {
    const c = el(`<button class="chip ${selIntent === it.id ? 'sel' : ''}">${it.emoji} ${esc(it.label)}</button>`);
    c.onclick = () => { selIntent = selIntent === it.id ? null : it.id; renderHome(); };
    ichips.appendChild(c);
  });
  gen.appendChild(ichips);
  gen.appendChild(el('<label class="field"><span>إيه المناسبة أو اللي حصل؟ (اختياري)</span><textarea id="ctx"></textarea></label>'));
  const btns = el('<div class="row"></div>');
  const b1 = el(`<button class="btn">${selIntent ? '✨ اكتب الرسالة' : '✨ اقتراح فوري'}</button>`); b1.onclick = () => doGenerate('manual');
  const b2 = el('<button class="ghost">🌅 صباحي</button>'); b2.onclick = () => doGenerate('morning');
  const b3 = el('<button class="ghost">🌙 مسائي</button>'); b3.onclick = () => doGenerate('evening');
  btns.append(b1, b2, b3);
  gen.appendChild(btns);
  host.appendChild(gen);
  if ($('#ctx')) $('#ctx').value = renderHome._ctx || '';

  // الاقتراحات
  const out = el('<div id="sugOut"></div>');
  host.appendChild(out);
  if (currentSuggestions) renderSuggestions();
  updateWhoBadge();
}

async function doGenerate(slot, occasionLabel) {
  const baseCtx = $('#ctx') ? $('#ctx').value : '';
  renderHome._ctx = baseCtx;
  // نمرّر المناسبة للـ backend كسياق عشان الرسالة تبقى مخصّصة ليها فعلاً.
  const ctx = occasionLabel ? ('المناسبة: ' + occasionLabel + '. ' + baseCtx).trim() : baseCtx;
  const out = $('#sugOut'); if (out) out.innerHTML = '<div class="card"><span class="spin"></span> بكتب لك اقتراحين...</div>';
  try {
    const res = await call('generate', { slot, intentId: occasionLabel ? null : selIntent, context: ctx });
    currentSuggestions = { items: res.items, themes: res.themes, slot };
    if (res.items[0]) setLastMessage(res.items[0].text);
    if (res.note) toast(res.note);
    renderSuggestions();
  } catch (e) { if (out) out.innerHTML = ''; toast('حصل خطأ: ' + e.message); }
}

function renderSuggestions() {
  const out = $('#sugOut'); if (!out) return; out.innerHTML = '';
  const r = currentRecipient();
  const isGroup = r && /^group/.test(r.relation);
  currentSuggestions.items.forEach((item, idx) => {
    const card = el(`<div class="suggestion"><span class="theme">${idx + 1}️⃣ ${esc(item.theme)}</span><div class="text">${esc(item.text)}</div></div>`);
    const refine = el('<div class="chips" style="margin-bottom:10px"></div>');
    [['longer', '➕ أطول'], ['shorter', '➖ أقصر'], ['romantic', '💘 أرومانسي'], ['simpler', '🌿 أبسط']].forEach(([sid, lbl]) => {
      const c = el(`<button class="chip">${lbl}</button>`);
      c.onclick = async () => {
        c.disabled = true;
        try { const nt = await call('refine', { text: item.text, styleId: sid }); item.text = nt; setLastMessage(nt); renderSuggestions(); }
        catch (e) { toast('التعديل مش متاح دلوقتي'); c.disabled = false; }
      };
      refine.appendChild(c);
    });
    card.appendChild(refine);
    const actions = el('<div class="row tight"></div>');
    const wa = el(`<button class="btn">${isGroup ? '📣 ابعت للجروب' : '📲 ابعت لـ' + esc(whoName(r))}</button>`);
    wa.onclick = () => { setLastMessage(item.text); sendWhatsApp(item.text, r, isGroup); };
    const copy = el('<button class="ghost">📋 نسخ</button>'); copy.onclick = () => { navigator.clipboard.writeText(item.text); toast('اتنسخت ✅'); };
    const pick = el('<button class="ghost">👍 اختار</button>');
    pick.onclick = async () => {
      await call('learn:choose', { text: item.text, theme: item.theme, recipientId: r ? r.id : '', slot: currentSuggestions.slot, themesShown: currentSuggestions.themes });
      setLastMessage(item.text);
      toast('حفظت أسلوبك من الاختيار ده 👌');
    };
    actions.append(wa, copy, pick);
    card.appendChild(actions);
    out.appendChild(card);
  });
  // تعديل يدوي
  const edit = el('<div class="card"><label class="field"><span>عدّل بنفسك واحفظه لأسلوبك</span><textarea id="editBox"></textarea></label></div>');
  const save = el('<button class="btn">💾 احفظ نسختي المعدّلة</button>');
  save.onclick = async () => {
    const t = $('#editBox').value.trim(); if (!t) return;
    await call('learn:edit', { text: t, theme: currentSuggestions.themes[0], recipientId: r ? r.id : '', slot: currentSuggestions.slot, themesShown: currentSuggestions.themes });
    setLastMessage(t);
    $('#editBox').value = ''; toast('سجّلت نسختك وضفتها لأسلوبي 🌟');
  };
  edit.appendChild(save);
  out.appendChild(edit);
}

// تطبيع رقم لـ wa.me: يكمّل كود الدولة للأرقام المحلية، ويسيب الدولي زي ما هو.
function normalizeNum(raw, cc) {
  const s = String(raw == null ? '' : raw).trim();
  let d = s.replace(/\D/g, '');
  if (!d) return d;
  if (s.startsWith('+')) return d;
  if (d.startsWith('00')) return d.slice(2);
  const code = String(cc || '').replace(/\D/g, '');
  if (!code) return d;
  d = d.replace(/^0+/, '');
  if (!d.startsWith(code)) d = code + d;
  return d;
}
function sendWhatsApp(text, r, isGroup, cc) {
  const enc = encodeURIComponent(text);
  const raw = r && r.number ? r.number : '';
  const num = cc ? normalizeNum(raw, cc) : String(raw).replace(/\D/g, '');
  const url = (!isGroup && num) ? ('https://wa.me/' + num + '?text=' + enc) : ('https://wa.me/?text=' + enc);
  call('openExternal', { url });
}

async function openGiftIdeas(label) {
  $('#modalTitle').textContent = '🎁 أفكار لـ' + label;
  $('#modalBody').innerHTML = '<span class="spin"></span> بجهّزلك أفكار...';
  $('#modal').classList.remove('hidden');
  try {
    const txt = await call('giftIdeas', { occasionLabel: label });
    $('#modalBody').innerHTML = esc(txt) + '<div class="muted" style="margin-top:10px">دي أفكار وفئة تقريبية، مش أسعار حقيقية لحظية.</div>';
  } catch (e) { $('#modalBody').textContent = 'الأفكار مش متاحة دلوقتي (محتاج نت + مفتاح Groq).'; }
}

// ---------- الأشخاص ----------
let editingId = null;
function renderPeople() {
  const host = $('#view-people'); host.innerHTML = '';
  host.appendChild(el('<h1>🧠 Brain — العقل الثاني</h1><p class="sub">معرفة الوكيل عن كل شخص: العلاقة، النبرة، اللهجة، الملاحظات، المناسبات.</p>'));

  // فورمة
  const f = el('<div class="card"></div>');
  f.appendChild(el(`<h2 style="margin-top:0">${editingId ? 'تعديل شخص' : 'إضافة شخص'}</h2>`));
  f.appendChild(el('<label class="field"><span>الاسم أو الدلع</span><input type="text" id="pName"></label>'));
  f.appendChild(el('<div class="muted" style="margin-bottom:4px">العلاقة</div>'));
  const rchips = el('<div class="chips" style="margin-bottom:10px" id="relChips"></div>');
  f.appendChild(rchips);
  f.appendChild(el('<label class="field"><span>رقم واتساب (اختياري، دولي بأرقام)</span><input type="text" id="pNum"></label>'));
  f.appendChild(el('<label class="field"><span>حاجات عنه (بيحب إيه، ذكريات)</span><textarea id="pNotes"></textarea></label>'));
  f.appendChild(el('<label class="field"><span>مناسباته (سطر لكل واحدة: عيد ميلاد=08-24)</span><textarea id="pOcc"></textarea></label>'));
  f.appendChild(el('<div class="muted" style="margin-bottom:4px">اللهجة</div>'));
  const dchips = el('<div class="chips" style="margin-bottom:10px" id="dChips"></div>');
  f.appendChild(dchips);
  f.appendChild(el('<div class="muted" style="margin-bottom:4px">لغة الرسالة (حسب لغته الأولى)</div>'));
  const lchips = el('<div class="chips" style="margin-bottom:10px" id="langChips"></div>');
  f.appendChild(lchips);
  f.appendChild(el('<label class="field"><span>نبرة خاصة بيه (اختياري)</span><input type="text" id="pTone"></label>'));
  const actions = el('<div class="row"></div>');
  const saveBtn = el(`<button class="btn">${editingId ? '💾 حفظ التعديل' : '➕ إضافة'}</button>`);
  actions.appendChild(saveBtn);
  if (editingId) { const cancel = el('<button class="ghost">إلغاء</button>'); cancel.onclick = () => { editingId = null; formState = defForm(); renderPeople(); }; actions.appendChild(cancel); }
  f.appendChild(actions);
  host.appendChild(f);

  // شيبس العلاقة واللهجة
  META.relations.forEach((rel) => {
    const c = el(`<button class="chip ${formState.relation === rel.id ? 'sel' : ''}">${rel.emoji} ${esc(rel.label)}</button>`);
    c.onclick = () => { formState.relation = rel.id; syncForm(); renderRelChips(); };
    rchips.appendChild(c);
  });
  META.dialects.forEach((d) => {
    const c = el(`<button class="chip ${formState.dialect === d.id ? 'sel' : ''}">${esc(d.label)}</button>`);
    c.onclick = () => { formState.dialect = d.id; syncForm(); renderDialectChips(); };
    dchips.appendChild(c);
  });
  const LANGS = [{ id: 'auto', label: '🌐 تلقائي' }, { id: 'ar', label: '🇪🇬 عربي' }, { id: 'en', label: '🇬🇧 إنجليزي' }];
  LANGS.forEach((L) => {
    const c = el(`<button class="chip ${(formState.language || 'auto') === L.id ? 'sel' : ''}">${esc(L.label)}</button>`);
    c.onclick = () => { formState.language = L.id; syncForm(); renderLangChips(); };
    lchips.appendChild(c);
  });
  function renderRelChips() { Array.from(rchips.children).forEach((c, i) => c.classList.toggle('sel', META.relations[i].id === formState.relation)); }
  function renderDialectChips() { Array.from(dchips.children).forEach((c, i) => c.classList.toggle('sel', META.dialects[i].id === formState.dialect)); }
  function renderLangChips() { Array.from(lchips.children).forEach((c, i) => c.classList.toggle('sel', LANGS[i].id === (formState.language || 'auto'))); }

  // تعبئة الحقول
  $('#pName', f).value = formState.name; $('#pNum', f).value = formState.number;
  $('#pNotes', f).value = formState.notes; $('#pOcc', f).value = formState.occText; $('#pTone', f).value = formState.tone;
  syncFromInputs(f);

  saveBtn.onclick = async () => {
    syncFromInputs(f);
    if (!formState.name.trim()) { toast('اكتب الاسم الأول'); return; }
    const occs = parseOcc(formState.occText);
    const list = PEOPLE.slice();
    if (editingId) {
      const i = list.findIndex((x) => x.id === editingId);
      if (i >= 0) list[i] = Object.assign({}, list[i], { name: formState.name.trim(), relation: formState.relation, number: formState.number.trim(), notes: formState.notes.trim(), occasions: occs, tone: formState.tone.trim(), dialect: formState.dialect, language: formState.language || 'auto' });
    } else {
      list.push({ id: 'p' + Date.now(), name: formState.name.trim(), relation: formState.relation, number: formState.number.trim(), notes: formState.notes.trim(), occasions: occs, tone: formState.tone.trim(), dialect: formState.dialect, language: formState.language || 'auto' });
    }
    PEOPLE = await call('people:set', list);
    if (!S.selectedRecipientId && PEOPLE[0]) S = await call('settings:set', { selectedRecipientId: PEOPLE[0].id });
    editingId = null; formState = defForm(); updateWhoBadge(); renderPeople(); toast('اتحفظ ✅');
  };

  // القائمة
  host.appendChild(el('<h2>أشخاصك</h2>'));
  if (!PEOPLE.length) host.appendChild(el('<p class="muted">لسه مفيش حد. ضيف أول شخص فوق.</p>'));
  PEOPLE.forEach((p) => {
    const sel = p.id === S.selectedRecipientId;
    const item = el(`<div class="list-item"><div class="person-card"><div class="name">${sel ? '✅ ' : ''}${relEmoji(p.relation)} ${esc(whoName(p))} · ${esc(relLabel(p.relation))}</div><div class="row tight"></div></div></div>`);
    const acts = $('.row', item);
    const ch = el('<button class="ghost small">اختار</button>'); ch.onclick = async () => { S = await call('settings:set', { selectedRecipientId: p.id }); updateWhoBadge(); show('home'); };
    const ed = el('<button class="ghost small">تعديل</button>'); ed.onclick = () => { editingId = p.id; formState = { name: p.name || '', relation: p.relation, number: p.number || '', notes: p.notes || '', occText: (p.occasions || []).map((o) => o.label + '=' + o.date).join('\n'), tone: p.tone || '', dialect: p.dialect || 'egyptian', language: p.language || 'auto' }; renderPeople(); };
    const del = el('<button class="ghost small">حذف</button>'); del.onclick = async () => { const list = PEOPLE.filter((x) => x.id !== p.id); PEOPLE = await call('people:set', list); if (S.selectedRecipientId === p.id) S = await call('settings:set', { selectedRecipientId: (PEOPLE[0] && PEOPLE[0].id) || '' }); updateWhoBadge(); renderPeople(); };
    acts.append(ch, ed, del);
    host.appendChild(item);
  });
}
function defForm() { return { name: '', relation: (META.relations[0] || {}).id || 'partner_wife', number: '', notes: '', occText: '', tone: '', dialect: 'egyptian', language: 'auto' }; }
let formState = defForm();
function syncForm() {}
function syncFromInputs(f) { formState.name = $('#pName', f).value; formState.number = $('#pNum', f).value; formState.notes = $('#pNotes', f).value; formState.occText = $('#pOcc', f).value; formState.tone = $('#pTone', f).value; }
function parseOcc(text) {
  return String(text || '').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const i = l.lastIndexOf('='); if (i <= 0) return null;
    const label = l.slice(0, i).trim(); const date = l.slice(i + 1).trim();
    return (label && /^\d{2}-\d{2}$/.test(date)) ? { label, date } : null;
  }).filter(Boolean);
}

// ---------- السجل ----------
async function renderHistory() {
  const host = $('#view-history'); host.innerHTML = '';
  host.appendChild(el('<h1>🗄️ Memory — سجل الرسائل</h1><p class="sub">ذاكرة الوكيل من اختياراتك: كل رسالة عدّلتها أو اخترتها.</p>'));
  const store = await call('store:get');
  const all = (store.feedback || []).filter((f) => f.finalText).reverse();
  const favs = new Set(store.favorites || []);
  const bar = el('<div class="card"><label class="field" style="margin:0"><input type="text" id="q" placeholder="دوّر في رسايلك"></label></div>');
  host.appendChild(bar);
  const listHost = el('<div id="histList"></div>'); host.appendChild(listHost);
  const draw = () => {
    const q = ($('#q').value || '').trim();
    listHost.innerHTML = '';
    const items = all.filter((f) => !q || f.finalText.includes(q));
    if (!items.length) { listHost.appendChild(el('<p class="muted">مفيش رسايل بالفلتر ده.</p>')); return; }
    items.forEach((f) => {
      const fav = favs.has(f.finalText);
      const who = (PEOPLE.find((p) => p.id === f.recipientId) || {}).name || '';
      const card = el(`<div class="list-item"><div class="muted">${esc(f.date)}${who ? ' · ' + esc(who) : ''}</div><div style="margin:6px 0">${esc(f.finalText)}</div><div class="row tight"></div></div>`);
      const acts = $('.row', card);
      const star = el(`<button class="ghost small">${fav ? '⭐' : '☆'}</button>`); star.onclick = async () => { const list = await call('favorite:toggle', { text: f.finalText }); favs.clear(); list.forEach((x) => favs.add(x)); draw(); };
      const cp = el('<button class="ghost small">📋</button>'); cp.onclick = () => { navigator.clipboard.writeText(f.finalText); toast('اتنسخت ✅'); };
      const wa = el('<button class="ghost small">📲</button>'); wa.onclick = () => { const r = currentRecipient(); sendWhatsApp(f.finalText, r, false); };
      const del = el('<button class="ghost small">🗑️</button>'); del.onclick = async () => { await call('history:delete', { date: f.date, text: f.finalText }); const idx = all.indexOf(f); if (idx >= 0) all.splice(idx, 1); draw(); };
      acts.append(star, cp, wa, del);
      listHost.appendChild(card);
    });
  };
  $('#q', bar).oninput = draw;
  draw();
}

// ---------- الإعدادات ----------
function renderSettings() {
  const host = $('#view-settings'); host.innerHTML = '';
  host.appendChild(el('<h1>✨ LLM — المحرّك والإعدادات</h1><p class="sub">مفتاح Groq، الموديل، النبرة، والمظهر. المفتاح بيتخزّن على جهازك بس.</p>'));
  const c = el('<div class="card"></div>');
  c.appendChild(el(`<label class="field"><span>مفتاح Groq (console.groq.com/keys)</span><input type="password" id="sKey" value="${esc(S.groqKey)}"></label>`));
  c.appendChild(el(`<label class="field"><span>اسمك</span><input type="text" id="sName" value="${esc(S.myName)}"></label>`));
  c.appendChild(el('<div class="muted" style="margin-bottom:4px">الموديل</div>'));
  const models = (META.modelCatalog?.models || []).map(id => [id, id.replace('openai/', '')]);
  if (!models.some(([id]) => id === S.model)) c.appendChild(el(`<p class="sub">النموذج المحفوظ غير متاح: ${esc(S.model)}. اختر بديلاً؛ لم يتغير اختيارك تلقائياً.</p>`));
  c.appendChild(el(`<label class="field"><span><input id="sGroqEnabled" type="checkbox" ${S.groqEnabled ? 'checked' : ''}> راجعت خطة Groq المجانية وحدودها وأوافق على إرسال الاسم والملاحظات وأمثلة الأسلوب لتوليد الرسالة.</span></label><p class="sub">القوالب المحلية تعمل دون شبكة. لا تحويل تلقائي لخطة مدفوعة. مراجعة القائمة: ${esc(META.modelCatalog?.reviewed)}</p>`));
  const mchips = el('<div class="chips" style="margin-bottom:12px"></div>');
  models.forEach(([id, lbl]) => { const b = el(`<button class="chip ${S.model === id ? 'sel' : ''}">${lbl}</button>`); b.onclick = () => { S.model = id; renderSettings(); }; mchips.appendChild(b); });
  c.appendChild(mchips);
  c.appendChild(el('<div class="muted" style="margin-bottom:4px">طول الرسالة</div>'));
  const lchips = el('<div class="chips" style="margin-bottom:12px"></div>');
  [['short', 'قصيرة'], ['medium', 'متوسطة']].forEach(([id, lbl]) => { const b = el(`<button class="chip ${S.messageLength === id ? 'sel' : ''}">${lbl}</button>`); b.onclick = () => { S.messageLength = id; renderSettings(); }; lchips.appendChild(b); });
  c.appendChild(lchips);
  const toggles = el('<div class="row" style="margin-bottom:12px"></div>');
  const hEmo = el(`<button class="chip ${S.emoji ? 'sel' : ''}">😊 إيموجي</button>`); hEmo.onclick = () => { S.emoji = !S.emoji; renderSettings(); };
  const hHum = el(`<button class="chip ${S.humor ? 'sel' : ''}">😄 دُعابة خفيفة</button>`); hHum.onclick = () => { S.humor = !S.humor; renderSettings(); };
  toggles.append(hEmo, hHum);
  c.appendChild(toggles);
  c.appendChild(el('<div class="muted" style="margin-bottom:4px">المظهر</div>'));
  const tchips = el('<div class="chips" style="margin-bottom:12px"></div>');
  [['light', 'فاتح'], ['dark', 'غامق']].forEach(([id, lbl]) => { const b = el(`<button class="chip ${S.theme === id ? 'sel' : ''}">${lbl}</button>`); b.onclick = () => { S.theme = id; applyTheme(); renderSettings(); }; tchips.appendChild(b); });
  c.appendChild(tchips);
  $('#sGroqEnabled', c).onchange = event => { S.groqEnabled = event.target.checked; };
  $('#sKey', c).oninput = event => { S.groqKey = event.target.value; };
  $('#sName', c).oninput = event => { S.myName = event.target.value; };
  const save = el('<button class="btn">💾 حفظ</button>');
  save.onclick = async () => { S = await call('settings:set', { groqKey: $('#sKey').value.trim(), groqEnabled: $('#sGroqEnabled').checked, myName: $('#sName').value.trim(), model: S.model, messageLength: S.messageLength, emoji: S.emoji, humor: S.humor, theme: S.theme }); applyTheme(); toast('اتحفظ ✅'); };
  c.appendChild(save);
  host.appendChild(c);
}

// ---------- Onboarding ----------
function renderOnboard() {
  const host = $('#view-onboard'); host.innerHTML = '';
  host.appendChild(el('<div class="banner"><h1>أهلاً بيك في وصال 💗</h1><p>مساعدك عشان تفضل قريّب من اللي بتحبهم.</p></div>'));
  const c = el('<div class="card"></div>');
  c.appendChild(el('<p class="sub">ابدأ بالقوالب المحلية. Groq اختياري بحصص حسب الحساب؛ راجع الخطة وفعّله من الإعدادات.</p>'));
  c.appendChild(el(`<label class="field"><span>مفتاح Groq (console.groq.com/keys)</span><input type="password" id="oKey"></label>`));
  c.appendChild(el(`<label class="field"><span>اسمك</span><input type="text" id="oName"></label>`));
  const b = el('<button class="btn block">يلا نبدأ ✨</button>');
  b.onclick = async () => { S = await call('settings:set', { groqKey: $('#oKey').value.trim(), myName: $('#oName').value.trim(), onboarded: true }); $('#view-onboard').classList.add('hidden'); show('people'); };
  c.appendChild(b);
  host.appendChild(c);
  ['home', 'skills', 'tools', 'people', 'history', 'settings'].forEach((v) => $('#view-' + v).classList.add('hidden'));
  $('#view-onboard').classList.remove('hidden');
}

// ---------- تشغيل ----------
async function boot() {
  S = await call('settings:get');
  META = await call('meta:get');
  PEOPLE = await call('people:get');
  try { GROUPS = await call('groups:get'); } catch (e) { GROUPS = []; }
  formState = defForm();
  applyTheme();
  updateWhoBadge();
  document.querySelectorAll('.nav-item').forEach((b) => (b.onclick = () => show(b.dataset.view)));
  $('#themeBtn').onclick = async () => { S = await call('settings:set', { theme: S.theme === 'dark' ? 'light' : 'dark' }); applyTheme(); };
  $('#modalClose').onclick = () => $('#modal').classList.add('hidden');
  updateStatus();
  if (!S.onboarded) renderOnboard(); else show('home');
}
boot();
