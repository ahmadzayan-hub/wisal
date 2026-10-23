// =====================================================================
// generateSuggestion.js — قلب التوليد.
// بيولّد اقتراحين مختلفين قصار باللهجة المصرية، نبرة دافئة صادقة.
//
// مهم: ده "تعلّم بالسياق" (in-context learning) — احنا بنحط أمثلة من
// اختياراتك السابقة في الموجّه (few-shot) عشان الموديل يقلّد صوتك.
// إحنا *مش* بندرّب النموذج ولا بنعدّل أوزانه. مفيش أي تدريب.
// =====================================================================

const config = require('./config');
const llm = require('./llm');
const store = require('./store');

// ---- اختيار موضوعين للترجيح + التنويع ----
// 1) نستبعد المواضيع المستخدمة في آخر كام يوم (تنويع).
// 2) نختار باحتمال متناسب مع وزن كل موضوع (الترجيح).
function pickThemes(count = 2) {
  const weights = store.getThemeWeights();
  const recent = new Set(store.recentThemes(config.avoidRecentThemeDays));

  // المرشّحون = كل المواضيع ما عدا المستخدمة مؤخراً.
  let pool = config.themes.filter((t) => !recent.has(t));
  // لو استبعدنا كل حاجة (استخدام كتير)، نرجّع كل المواضيع.
  if (pool.length < count) pool = [...config.themes];

  const chosen = [];
  const available = [...pool];
  for (let i = 0; i < count && available.length > 0; i++) {
    const picked = weightedRandom(available, weights);
    chosen.push(picked);
    // نشيل المختار عشان الاتنين يبقوا مختلفين.
    available.splice(available.indexOf(picked), 1);
  }
  return chosen;
}

// اختيار عشوائي مرجّح حسب الوزن.
function weightedRandom(items, weights) {
  const total = items.reduce((sum, it) => sum + (weights[it] ?? 1), 0);
  let r = Math.random() * total;
  for (const it of items) {
    r -= weights[it] ?? 1;
    if (r <= 0) return it;
  }
  return items[items.length - 1];
}

// ---- مرساة الصوت: برومبت نظام ثابت لا يتغيّر مهما اتعلّم ----
// ده اللي بيحافظ على نبرة أساسية ثابتة وما يخليش الأسلوب ينحرف مع
// تعديل أو اتنين. أمثلة الأسلوب بتأثّر، بس المرساة دي فوقها دايماً.
function buildSystemPrompt() {
  return [
    'انت مساعد بيكتب لي رسائل قصيرة لزوجتي باللهجة المصرية العامية.',
    `النبرة: ${config.tone}.`,
    'قواعد ثابتة لا تتغيّر:',
    '- الرسالة من سطر لـ 3 أسطر بحد أقصى.',
    '- لهجة مصرية طبيعية، كأني أنا اللي بكتبها، مش فصحى ولا كلام رسمي.',
    '- صدق وبساطة، من غير مبالغة ولا كلام مصنوع ولا شِعر متكلّف.',
    '- من غير إيموجي كتير (إيموجي واحد على الأكثر، أو من غير خالص).',
    '- متبدأش بـ "حبيبتي" في كل مرة، نوّع في البداية.',
    'هدفك: اقتراح أكتبه أنا وأبعته بنفسي. انت بتساعدني بس.',
  ].join('\n');
}

// ---- حقن أمثلة الأسلوب (few-shot) ----
// بناخد آخر أمثلة من اختياراتك ونحطها كـ "ده أسلوبي". الموديل يقلّدها.
function buildStyleBlock() {
  const examples = store.getStyleExamples();
  if (examples.length === 0) {
    return 'لسه مفيش أمثلة من أسلوبي. اكتب بنبرة دافئة بسيطة طبيعية.';
  }
  const lines = examples
    .slice(-config.styleExamplesMax)
    .map((e, i) => `${i + 1}) ${e.text}`)
    .join('\n');
  return [
    'دي أمثلة من رسايل اخترتها أو عدّلتها قبل كده — ده أسلوبي وصوتي.',
    'قلّد روح الأمثلة دي (اختيار الكلمات والإيقاع)، من غير ما تنسخها حرفياً:',
    lines,
  ].join('\n');
}

/**
 * generateSuggestions — يولّد اقتراحين.
 * @param {object} params
 * @param {'morning'|'evening'|'occasion'} params.slot  الخانة
 * @param {object} [params.occasion]  مناسبة اختيارية { label, ... }
 * @returns {Promise<{ items: Array<{text:string, theme:string}>, slot:string, themesShown:string[] }>}
 */
async function generateSuggestions({ slot, occasion } = {}) {
  // المناسبة بتفرض الموضوع. غير كده نختار موضوعين بالترجيح + التنويع.
  let themes;
  if (occasion) {
    themes = [occasion.label, occasion.label];
  } else {
    themes = pickThemes(2);
  }

  const slotLabel =
    slot === 'morning' ? 'الصبح' : slot === 'evening' ? 'بالليل' : 'دلوقتي';

  // وصف الموقف للموديل.
  const situation = occasion
    ? `النهاردة مناسبة: ${occasion.label}. اكتب رسالة مخصصة للمناسبة دي.`
    : `الوقت: ${slotLabel}. الموضوع المطلوب لكل اقتراح موجود تحت.`;

  const userPrompt = [
    buildStyleBlock(),
    '',
    situation,
    '',
    `اكتب لي اقتراحين مختلفين تماماً عن بعض.`,
    occasion
      ? `الاتنين عن: ${occasion.label}.`
      : `الاقتراح الأول موضوعه: ${themes[0]}. الاقتراح التاني موضوعه: ${themes[1]}.`,
    '',
    'رجّع الرد بالظبط بالصيغة دي ومن غير أي كلام زيادة:',
    '١- <نص الاقتراح الأول>',
    '٢- <نص الاقتراح التاني>',
  ].join('\n');

  try {
    const raw = await llm.complete([
      { role: 'system', content: buildSystemPrompt() },
      { role: 'user', content: userPrompt },
    ]);
    return { items: parseTwo(raw, themes), slot, themesShown: themes, offline: false };
  } catch (error) {
    const items = ['أتمنى أن تكون بخير. أحببت أن أطمئن عليك.', 'مرحباً، أتمنى لك يوماً طيباً. يسعدني أن نتواصل عندما يناسبك.']
      .map(text => ({ text, theme: 'تواصل' }));
    return { items, slot, themesShown: items.map(x => x.theme), offline: true,
      note: (error.code ? error.message : 'تعذّر التوليد.') + ' هذه قوالب محلية، وليست نتيجة ذكاء اصطناعي.' };
  }
}

// ---- تحليل رد الموديل لاقتراحين ----
// بنقبل أرقام عربي (١-/٢-) أو إنجليزي (1-/2-/1./2.).
function parseTwo(raw, themes) {
  const lines = raw
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);

  // الأفضلية للأسطر اللي فيها ترقيم فعلي (١- / ٢- / 1. / 2)) عشان أي كلام
  // تمهيدي زيادة من الموديل ما يتحسبش كأنه اقتراح.
  const numbered = [];
  for (const line of lines) {
    const m = line.match(/^\s*[١٢12]\s*[-.)]\s*(.+)$/);
    if (m) numbered.push(m[1].trim());
  }

  let first;
  let second;
  if (numbered.length >= 2) {
    [first, second] = numbered;
  } else {
    // احتياطي: أول سطرين غير فاضيين بعد إزالة أي ترقيم.
    const cleaned = lines
      .map((l) => l.replace(/^\s*[١٢12]\s*[-.)]\s*/, '').trim())
      .filter(Boolean);
    first = cleaned[0] || raw.trim();
    second = cleaned[1] || cleaned[0] || raw.trim();
  }

  return [
    { text: first, theme: themes[0] },
    { text: second, theme: themes[1] },
  ];
}

module.exports = { generateSuggestions, pickThemes };
