// عقل وصال لسطح المكتب (Node/Electron main process).
// كله محلي: الإعدادات والأشخاص والتعلّم في ملفات JSON داخل مجلد بيانات المستخدم.
// المزوّد الوحيد الخارجي هو Groq وقت التوليد بس.
'use strict';
const fs = require('fs');
const path = require('path');
const groqPolicy = require('./groq-policy');

let DATA_DIR = '.';
function init(dir) {
  DATA_DIR = dir;
  try { fs.mkdirSync(dir, { recursive: true }); } catch (e) { /* ignore */ }
}

function fileFor(name) { return path.join(DATA_DIR, name); }
function readJson(name, def) {
  try { return JSON.parse(fs.readFileSync(fileFor(name), 'utf8')); } catch (e) { return def; }
}
function writeJson(name, obj) {
  // كتابة ذرّية: ملف مؤقت ثم rename — كراش وسط الكتابة مايبوّظش الملف الأصلي.
  try {
    const tmp = fileFor(name + '.tmp');
    fs.writeFileSync(tmp, JSON.stringify(obj, null, 2));
    fs.renameSync(tmp, fileFor(name));
  } catch (e) { /* ignore */ }
}

// ---------- الإعدادات ----------
const DEFAULT_SETTINGS = {
  groqKey: '', myName: '', model: 'openai/gpt-oss-20b', groqEnabled: false,
  humor: false, emoji: true, messageLength: 'short',
  theme: 'light', selectedRecipientId: '', onboarded: false,
};
function getSettings() { return Object.assign({}, DEFAULT_SETTINGS, readJson('settings.json', {})); }
function setSettings(patch) { const s = Object.assign(getSettings(), patch || {}); writeJson('settings.json', s); return s; }

// ---------- الأشخاص ----------
function getPeople() { return readJson('people.json', []); }
function setPeople(list) { writeJson('people.json', Array.isArray(list) ? list : []); return getPeople(); }
function currentRecipient() {
  const s = getSettings(); const p = getPeople();
  return p.find((x) => x.id === s.selectedRecipientId) || p[0] || null;
}

// ---------- العلاقات ----------
const RELATIONS = [
  { id: 'partner_wife', label: 'زوجتي', toAddr: 'لمراتي', tone: 'حب رومانسي دافئ وصادق، حنية وشوق من غير مبالغة', emoji: '💗' },
  { id: 'partner_husband', label: 'زوجي', toAddr: 'لجوزي', tone: 'حب رومانسي دافئ وصادق، حنية وشوق من غير مبالغة', emoji: '💗' },
  { id: 'son', label: 'ابني', toAddr: 'لابني', tone: 'حنان وفخر وتشجيع وأمان', emoji: '👦' },
  { id: 'daughter', label: 'بنتي', toAddr: 'لبنتي', tone: 'حنان وفخر ولطف وحماية دافئة', emoji: '👧' },
  { id: 'mother', label: 'أمي', toAddr: 'لأمي', tone: 'احترام وحب عميق وامتنان وحنية', emoji: '👩' },
  { id: 'father', label: 'أبويا', toAddr: 'لأبويا', tone: 'احترام وحب وتقدير وامتنان', emoji: '👨' },
  { id: 'brother', label: 'أخويا', toAddr: 'لأخويا', tone: 'ود وسند وأخوّة وروح مرحة خفيفة', emoji: '🧑' },
  { id: 'sister', label: 'أختي', toAddr: 'لأختي', tone: 'ود وسند وحنية وأخوّة', emoji: '👩‍🦰' },
  { id: 'group_family', label: 'العيلة', toAddr: 'للعيلة', tone: 'دفء أسري جامع', emoji: '👨‍👩‍👧‍👦' },
  { id: 'group_friends', label: 'الأصحاب', toAddr: 'للأصحاب', tone: 'ود وروح مرحة وصداقة حلوة', emoji: '🧑‍🤝‍🧑' },
];
function relationById(id) { return RELATIONS.find((r) => r.id === id) || RELATIONS[0]; }

const DIALECTS = [
  { id: 'egyptian', label: 'مصري' }, { id: 'gulf', label: 'خليجي' },
  { id: 'levantine', label: 'شامي' }, { id: 'msa', label: 'فصحى' },
];
function dialectPhrase(id) {
  if (id === 'gulf') return 'اللهجة الخليجية';
  if (id === 'levantine') return 'اللهجة الشامية';
  if (id === 'msa') return 'العربية الفصحى البسيطة';
  return 'اللهجة المصرية العامية';
}

// ---------- اللغة (عربي/إنجليزي حسب لغة المستقبل) — نظير Lang في أندرويد ----------
function detectLang(text) {
  if (!text) return null;
  let ar = 0, la = 0;
  for (const ch of String(text)) {
    const c = ch.codePointAt(0);
    if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0x08A0 && c <= 0x08FF)) ar++;
    else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) la++;
  }
  if (ar === 0 && la === 0) return null;
  return ar >= la ? 'ar' : 'en';
}
function resolveLang(pref) {
  const p = (pref || '').toLowerCase();
  if (p === 'ar' || p === 'en') return p;
  for (let i = 1; i < arguments.length; i++) { const d = detectLang(arguments[i]); if (d) return d; }
  return 'ar';
}
function langDirective(lang) {
  return lang === 'en'
    ? "IMPORTANT: The recipient's first language is English. Write the ENTIRE message in natural, warm English. Do NOT use Arabic. Ignore any Arabic-dialect instruction."
    : '';
}

// ---------- النيّات ----------
const INTENTS = [
  { id: 'apology', label: 'اعتذار', emoji: '🕊️', hint: 'رسالة اعتذار صادقة بتصالح وتكسر الزعل من غير تبرير زيادة.' },
  { id: 'congrats', label: 'تهنئة', emoji: '🎉', hint: 'تهنئة فرحانة بمناسبة سعيدة.' },
  { id: 'comfort', label: 'مواساة', emoji: '🤍', hint: 'مواساة وتخفيف في وقت صعب، حضور وسند.' },
  { id: 'thanks', label: 'شكر', emoji: '🙏', hint: 'شكر وامتنان على حاجة عملها.' },
  { id: 'longing', label: 'اشتياق', emoji: '💭', hint: 'اشتياق ولهفة ودفء، إنه وحشك.' },
  { id: 'reassure', label: 'طمأنة', emoji: '🫂', hint: 'طمأنة وتهدئة قلق، إنك جنبه.' },
  { id: 'support', label: 'دعم', emoji: '💪', hint: 'تشجيع ودعم وثقة في قدراته.' },
  { id: 'dua', label: 'دعاء', emoji: '🤲', hint: 'دعاء من القلب بالخير والصحة.' },
  { id: 'reconnect', label: 'إعادة تواصل', emoji: '🌉', hint: 'رسالة بسيطة تكسر برود المسافة بعد فترة انقطاع، بتفتح الكلام بلطف من غير عتاب، بتحسّسه إنك افتكرته ووحشك.' },
];
function intentById(id) { return INTENTS.find((i) => i.id === id) || null; }

const THEMES = ['امتنان', 'اشتياق', 'تمني يوم جميل', 'تقدير', 'دعم', 'دعاء', 'كلمة من القلب'];

// ---------- المخزن والتعلّم ----------
function getStore() {
  return readJson('store.json', { styleExamples: [], themeWeights: {}, feedback: [], favorites: [], lastContacted: {} });
}
function saveStore(s) { writeJson('store.json', s); }

function styleExamplesFor(recipientId) {
  return getStore().styleExamples.filter((e) => e.recipientId === recipientId).slice(-30);
}
function addStyleExample(text, theme, recipientId) {
  const s = getStore();
  s.styleExamples.push({ text: (text || '').trim(), theme: theme || null, recipientId: recipientId || '' });
  // سقف 30 لكل شخص
  let count = s.styleExamples.filter((e) => e.recipientId === recipientId).length;
  while (count > 30) {
    const idx = s.styleExamples.findIndex((e) => e.recipientId === recipientId);
    if (idx >= 0) { s.styleExamples.splice(idx, 1); count--; } else break;
  }
  saveStore(s);
}
function addFeedback(fb) { const s = getStore(); s.feedback.push(fb); saveStore(s); }
function bumpTheme(theme, delta) {
  if (!theme) return; const s = getStore();
  let n = (s.themeWeights[theme] || 1) + delta;
  s.themeWeights[theme] = Math.max(0.2, Math.min(5, n)); saveStore(s);
}
function toggleFavorite(text) {
  const s = getStore(); const i = s.favorites.indexOf(text);
  if (i >= 0) s.favorites.splice(i, 1); else s.favorites.push(text);
  saveStore(s); return s.favorites;
}
function deleteHistory(date, text) {
  const s = getStore();
  s.feedback = s.feedback.filter((f) => !(f.date === date && f.finalText === text));
  saveStore(s);
}
function markContacted(recipientId) {
  if (!recipientId) return; const s = getStore();
  s.lastContacted[recipientId] = new Date().toISOString().slice(0, 10); saveStore(s);
}

// ---------- Groq ----------
async function groqComplete(messages, temperature) {
  const s = getSettings();
  try {
    return await groqPolicy.complete({ enabled: s.groqEnabled, apiKey: s.groqKey, model: s.model, messages, temperature });
  } catch (error) {
    const safe = new Error(groqPolicy.publicMessage(error));
    safe.code = error.code;
    throw safe;
  }
}

// ---------- بناء البرومبت ----------
function buildSystem(recipient, intent, opts) {
  opts = opts || {};
  const rel = relationById(recipient ? recipient.relation : 'partner_wife');
  const s = getSettings();
  const business = !!opts.business;
  const tone = business
    ? 'محترمة ودّية واضحة ومهنية، بتقدّم متابعة/قيمة حقيقية من غير مبالغة عاطفية ولا ضغط بيع'
    : ((recipient && recipient.tone) ? recipient.tone : rel.tone);
  const dialect = dialectPhrase(recipient ? recipient.dialect : 'egyptian');
  // لغة الرسالة حسب لغة الشخص الأولى (auto: نكشف من اسمه).
  const lang = resolveLang(recipient ? recipient.language : '', recipient ? recipient.name : '');
  const firstLine = business
    ? ('انت بتكتب رسالة قصيرة لعميل عنده محادثة شغّالة معاك بـ' + dialect + '.')
    : ('انت بتساعد شخص يكتب رسالة قصيرة ' + rel.toAddr + ' (' + rel.label + ') بـ' + dialect + '.');
  const lines = [
    firstLine,
    'النبرة المناسبة: ' + tone + '.',
    'اكتب كإنسان حقيقي بمشاعر صادقة ودفء - مش كلام آلة.',
    s.messageLength === 'medium' ? '- الرسالة من سطرين لـ 3 أسطر.' : '- الرسالة قصيرة: سطر أو سطرين بحد أقصى.',
    '- ' + dialect + ' طبيعية، كأنه هو اللي كتبها.',
    '- صدق وبساطة من غير مبالغة ولا كلام مصنوع.',
    s.emoji ? '- استخدم إيموجي أو اتنين معبّرين بذوق.' : '- من غير إيموجي خالص.',
  ];
  if (s.humor && !business) lines.push('- لمسة خفيفة من الدُعابة اللطيفة.');
  if (intent) lines.push('نوع الرسالة المطلوب: ' + intent.label + '. ' + intent.hint);
  // لو لغة الشخص إنجليزي، نحقن توجيه غالب في الأول عشان يكتب بالإنجليزي بالكامل.
  if (lang === 'en') lines.unshift(langDirective('en'));
  return lines.join('\n');
}

function buildUser(recipient, themes, intent, context) {
  const s = getSettings();
  const parts = [];
  const examples = styleExamplesFor(recipient ? recipient.id : '');
  if (examples.length) {
    parts.push('دي أمثلة من أسلوبي، قلّد روحها من غير نسخ حرفي:');
    parts.push(examples.map((e, i) => (i + 1) + ') ' + e.text).join('\n'));
  } else {
    parts.push('لسه مفيش أمثلة، اكتب بنبرة دافئة بسيطة.');
  }
  if (s.myName) parts.push('\nاسم اللي بيبعت: ' + s.myName + '.');
  if (recipient && recipient.name) parts.push('اسم اللي بيتبعتله: ' + recipient.name + ' - نادِه باسمه.');
  if (recipient && recipient.notes) parts.push('حاجات عنه: ' + recipient.notes + '.');
  if (context && context.trim()) parts.push('\nسياق مهم عن الموقف (خلّي الرسالة تتكلم عنه طبيعي): ' + context.trim());
  const themeLine = intent
    ? 'الاتنين عن: ' + intent.label + '، كل واحدة بزاوية مختلفة.'
    : 'الاقتراح الأول موضوعه: ' + themes[0] + '. الاقتراح التاني موضوعه: ' + themes[1] + '.';
  parts.push('\nاكتب اقتراحين مختلفين تماماً، وكإنهم من قلبه.', themeLine, '',
    'رجّع بالظبط بالصيغة دي ومن غير أي كلام زيادة:', '١- <الاقتراح الأول>', '٢- <الاقتراح التاني>');
  return parts.join('\n');
}

function pickThemes() {
  const w = getStore().themeWeights || {};
  const pool = THEMES.slice();
  const out = [];
  for (let k = 0; k < 2 && pool.length; k++) {
    const total = pool.reduce((a, t) => a + (w[t] || 1), 0);
    let r = Math.random() * total;
    let idx = 0;
    for (let i = 0; i < pool.length; i++) { r -= (w[pool[i]] || 1); if (r <= 0) { idx = i; break; } }
    out.push(pool.splice(idx, 1)[0]);
  }
  return out;
}

function parseTwo(raw, themes) {
  const lines = String(raw).split('\n').map((l) => l.trim()).filter(Boolean);
  const re = /^[١٢12]\s*[-.)]\s*(.+)$/;
  const nums = lines.map((l) => { const m = l.match(re); return m ? m[1].trim() : null; }).filter(Boolean);
  let a, b;
  if (nums.length >= 2) { a = nums[0]; b = nums[1]; }
  else {
    const clean = lines.map((l) => l.replace(/^[١٢12]\s*[-.)]\s*/, '').trim()).filter(Boolean);
    a = clean[0] || String(raw).trim(); b = clean[1] || a;
  }
  return [{ text: a, theme: themes[0] || '' }, { text: b, theme: themes[1] || themes[0] || '' }];
}

// بنك احتياطي لو مفيش نت/مفتاح
function fallbackTwo(recipient, intent) {
  const name = recipient && recipient.name ? recipient.name : '';
  const base = intent ? [
    'آسف بجد لو زعّلتك، إنت أغلى من أي خناقة 🕊️',
    'مبروك من قلبي، تستاهل كل خير 🎉',
  ] : [
    'وجودك في حياتي نعمة، بحبك وبشكر ربنا عليك كل يوم ❤️',
    'فاكرك دايماً وقلبي معاك، ربنا يخليك ليا 💗',
  ];
  const theme = intent ? intent.label : 'كلمة من القلب';
  return base.slice(0, 2).map((t) => ({ text: name ? ('يا ' + name + '، ' + t) : t, theme }));
}

async function generate(opts) {
  const recipient = currentRecipient();
  const intent = intentById(opts && opts.intentId);
  const themes = intent ? [intent.label, intent.label] : pickThemes();
  // الخانة (صباحي/مسائي) بتضيف لمسة للسياق عشان الاقتراح يختلف فعلاً حسب الوقت.
  const slot = (opts && opts.slot) || 'manual';
  let ctx = (opts && opts.context) || '';
  if (slot === 'morning') ctx = ('لمسة صباحية (صباح الخير). ' + ctx).trim();
  else if (slot === 'evening') ctx = ('لمسة مسائية (تصبح على خير). ' + ctx).trim();
  try {
    const raw = await groqComplete([
      { role: 'system', content: buildSystem(recipient, intent) },
      { role: 'user', content: buildUser(recipient, themes, intent, ctx) },
    ]);
    return { items: parseTwo(raw, themes), themes, offline: false, note: null };
  } catch (e) {
    return {
      items: fallbackTwo(recipient, intent), themes, offline: true,
      note: e.message + ' هذه قوالب محلية وليست نتيجة ذكاء اصطناعي. راجع اللغة والتفاصيل.',
    };
  }
}

async function refine(text, styleId) {
  const recipient = currentRecipient();
  const rel = relationById(recipient ? recipient.relation : 'partner_wife');
  const hint = ({
    longer: 'أطول شوية وأدفى من غير حشو', shorter: 'أقصر وأكثف',
    romantic: 'أرومانسي وأحنّ', simpler: 'أبسط وأوضح بكلمات يومية',
  })[styleId] || 'أحلى وأصدق';
  const sys = 'انت بتعيد صياغة رسالة قصيرة باللهجة المصرية، حافظ على المعنى والصدق، ورجّع النص بس.';
  const user = 'أعد صياغة الرسالة دي بحيث تبقى ' + hint + ' (نبرة تناسب ' + rel.label + '):\n\n' + text;
  return groqComplete([{ role: 'system', content: sys }, { role: 'user', content: user }], 0.7);
}

async function giftIdeas(occasionLabel) {
  const r = currentRecipient();
  const rel = relationById(r ? r.relation : 'partner_wife');
  const who = r && r.name ? r.name : rel.label;
  const sys = 'انت مستشار لطيف بتقترح أفكار عملية لمناسبة. اكتب مصري بسيط. ماتخترعش أسعار حقيقية، اكتب أفكار وفئة سعرية تقريبية (رخيّص/متوسط) بس.';
  const user = ['المناسبة: ' + occasionLabel, 'الشخص: ' + who + ' (' + rel.label + ').',
    r && r.notes ? 'حاجات عنه: ' + r.notes : '', '',
    'اقترح 3 أو 4 أفكار (هدية/كارت/ورد/لفتة) تناسب شخصيته، كل فكرة في سطر بإيموجي ومعاها فئة سعرية تقريبية بين قوسين.'].join('\n');
  return groqComplete([{ role: 'system', content: sys }, { role: 'user', content: user }], 0.8);
}

function todayISO() { return new Date().toISOString().slice(0, 10); }

// ---------- المجموعات (شغل/مشروع/أسرة) ----------
// كل مجموعة: { id, name, kind, members: [{ id, name, number, relation, dialect, tone, notes, relationship }] }
const GROUP_KINDS = [
  { id: 'work', label: 'شغل', emoji: '💼' },
  { id: 'project', label: 'مشروع', emoji: '🚀' },
  { id: 'family', label: 'أسرة', emoji: '👨‍👩‍👧‍👦' },
  { id: 'friends', label: 'أصحاب', emoji: '🧑‍🤝‍🧑' },
  { id: 'clients', label: 'عملاء', emoji: '🤝' },
  { id: 'other', label: 'أخرى', emoji: '📇' },
];
function getGroups() { return readJson('groups.json', []); }
function setGroups(list) { writeJson('groups.json', Array.isArray(list) ? list : []); return getGroups(); }

// قارئ CSV بسيط ومتين (يتعامل مع علامات التنصيص والفواصل جوه الحقل).
function parseCSV(text) {
  const rows = []; let field = '', row = [], inQ = false; const s = String(text || '');
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inQ) {
      if (c === '"') { if (s[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
      else field += c;
    } else if (c === '"') { inQ = true; }
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (field !== '' || row.length) { row.push(field); rows.push(row); row = []; field = ''; }
      if (c === '\r' && s[i + 1] === '\n') i++;
    } else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows;
}

// يحوّل نص CSV لأعضاء: يكتشف أعمدة الاسم/الرقم/العلاقة/الملاحظات من العناوين،
// وإلا يفترض العمود الأول اسم والتاني رقم.
function parseContactsCSV(text) {
  const rows = parseCSV(text).filter((r) => r.some((c) => (c || '').trim() !== ''));
  if (!rows.length) return [];
  const norm = (x) => String(x || '').trim().toLowerCase();
  const header = rows[0].map(norm);
  const findCol = (re) => header.findIndex((h) => re.test(h));
  let ni = findCol(/name|اسم|الاسم/), pi = findCol(/phone|mobile|number|whats|رقم|موبايل|هاتف|واتس/);
  let ri = findCol(/relation|علاقة|صفة/), oi = findCol(/note|ملاحظ|عن|حاج/);
  let start = 1;
  if (ni < 0 && pi < 0) { ni = 0; pi = 1; ri = -1; oi = -1; start = 0; } // مفيش عناوين
  const out = [];
  for (let i = start; i < rows.length; i++) {
    const r = rows[i];
    const name = ((ni >= 0 ? r[ni] : r[0]) || '').trim();
    const number = ((pi >= 0 ? r[pi] : r[1]) || '').trim().replace(/[^\d+]/g, '');
    if (!name && !number) continue;
    out.push({
      id: 'm' + i + '_' + number.replace(/\D/g, '').slice(-6),
      name: name || number, number,
      relation: (ri >= 0 && r[ri] ? r[ri].trim() : ''),
      notes: (oi >= 0 && r[oi] ? r[oi].trim() : ''),
      dialect: 'egyptian', tone: '', relationship: '',
    });
  }
  return out;
}

// ---------- تحليل الشخصية من معلومات ملصوقة (مش كشط سوشيال) ----------
function safeJson(raw) {
  try { const m = String(raw).match(/\{[\s\S]*\}/); return JSON.parse(m ? m[0] : raw); } catch (e) { return null; }
}
async function analyzePersona(info, url) {
  const sys = [
    'انت بتساعد المستخدم يخصّص رسالة لشخص. حلّل المعلومات اللي المستخدم بنفسه لصقها.',
    'ملاحظة مهمة: انت ماتفتحش أي روابط ولا تكشط أي بيانات من الإنترنت — تحليلك من النص الملصوق بس.',
    'رجّع JSON فقط بالمفاتيح دي:',
    '{"summary":"سطر يلخّص شخصيته/اهتماماته","dialect":"egyptian|gulf|levantine|msa","tone":"وصف قصير للنبرة الأنسب","relationship":"صفة العلاقة المقترحة بينه وبين المُرسِل"}',
    'من غير أي كلام خارج الـ JSON.',
  ].join('\n');
  const user = ['المعلومات اللي لصقها المستخدم:', String(info || '').trim(),
    url ? ('رابط مذكور كمرجع فقط (ممنوع فتحه): ' + url) : '', '', 'رجّع JSON فقط.'].join('\n');
  const raw = await groqComplete([{ role: 'system', content: sys }, { role: 'user', content: user }], 0.4);
  return safeJson(raw);
}

// ---------- توليد رسالة واحدة مخصّصة لعضو ----------
function buildUserSingle(recipient, theme, intent, context) {
  const s = getSettings(); const parts = [];
  const examples = styleExamplesFor(recipient && recipient.id ? recipient.id : '');
  if (examples.length) {
    parts.push('أمثلة من أسلوبي (قلّد الروح مش النص):');
    parts.push(examples.map((e, i) => (i + 1) + ') ' + e.text).join('\n'));
  }
  if (s.myName) parts.push('اسم المُرسِل: ' + s.myName + '.');
  if (recipient && recipient.name) parts.push('اسم المستقبل: ' + recipient.name + ' — نادِه باسمه.');
  if (recipient && recipient.relationship) parts.push('صفة العلاقة بينهم: ' + recipient.relationship + '.');
  if (recipient && recipient.notes) parts.push('معلومات عنه: ' + recipient.notes + '.');
  if (context && context.trim()) parts.push('سياق مهم: ' + context.trim() + '.');
  parts.push(intent ? ('نوع الرسالة: ' + intent.label + '. ' + intent.hint) : ('الموضوع: ' + theme + '.'));
  parts.push('اكتب رسالة واحدة قصيرة مخصّصة له بالظبط، من غير مقدمات ولا خيارات — النص بس.');
  return parts.join('\n');
}
async function generateOneFor(member, opts) {
  const intent = intentById(opts && opts.intentId);
  const themes = intent ? [intent.label] : pickThemes();
  const recipient = Object.assign({ relation: 'group_friends', dialect: 'egyptian' }, member || {});
  const business = !!(opts && opts.business);
  try {
    const raw = await groqComplete([
      { role: 'system', content: buildSystem(recipient, intent, { business: business }) },
      { role: 'user', content: buildUserSingle(recipient, themes[0], intent, (opts && opts.context) || '') },
    ], 0.85);
    const text = String(raw).split('\n').map((l) => l.replace(/^[١٢12]\s*[-.)]\s*/, '').trim()).filter(Boolean)[0] || String(raw).trim();
    return { text, offline: false };
  } catch (e) {
    const fb = fallbackTwo(recipient, intent)[0];
    return { text: fb.text, offline: true };
  }
}


// «سلسلة الدفء»: كام يوم متتالي فيهم رسالة اتبعتت (بسماحية يوم واحد) — نظير Streak في أندرويد.
function computeStreak(dates, todayISO) {
  const set = new Set((dates || []).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d || '')));
  if (!set.size) return 0;
  const day = (iso, delta) => {
    const t = new Date(iso + 'T00:00:00Z');
    t.setUTCDate(t.getUTCDate() + delta);
    return t.toISOString().slice(0, 10);
  };
  let cursor;
  if (set.has(todayISO)) cursor = todayISO;
  else if (set.has(day(todayISO, -1))) cursor = day(todayISO, -1);
  else return 0;
  let n = 0;
  while (set.has(cursor)) { n++; cursor = day(cursor, -1); }
  return n;
}

// ملخّص حالة للنظام (شريط الحالة + تبويبات Memory/Brain).
function stats() {
  const s = getSettings(); const st = getStore(); const p = getPeople();
  return {
    model: s.model,
    people: p.length,
    styleExamples: (st.styleExamples || []).length,
    feedback: (st.feedback || []).length,
    favorites: (st.favorites || []).length,
    hasKey: !!s.groqKey,
    streak: computeStreak((st.feedback || []).filter((f) => f.finalText).map((f) => f.date), todayISO()),
  };
}

module.exports = { modelCatalog: groqPolicy.catalog,
  init, getSettings, setSettings, getPeople, setPeople, currentRecipient,
  RELATIONS, DIALECTS, INTENTS, GROUP_KINDS, relationById,
  getStore, addStyleExample, addFeedback, bumpTheme, toggleFavorite, deleteHistory, markContacted,
  generate, refine, giftIdeas, todayISO, stats,
  getGroups, setGroups, parseContactsCSV, analyzePersona, generateOneFor,
  detectLang, resolveLang, langDirective, dialectPhrase, intentById, computeStreak,
};
