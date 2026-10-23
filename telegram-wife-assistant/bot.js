// =====================================================================
// bot.js — منطق تيليجرام.
// المسؤول عن: إرسال الاقتراحين بأزرار، التقاط ضغط الأزرار والرد النصي،
// وأوامر /start و /stats و /reset.
//
// قاعدة صارمة: الـ agent بيكلّم chatId بتاعي أنا فقط. مفيش أي إرسال لأي
// حد تاني. كل دالة إرسال بتروح لـ config.chatId بس.
// =====================================================================

const { Telegraf, Markup } = require('telegraf');
const config = require('./config');
const store = require('./store');
const review = require('./review');
const { generateSuggestions } = require('./generateSuggestion');
const { getTodaysOccasion } = require('./occasions');

// آخر اقتراحات اتبعتت (في الذاكرة) عشان نربط الزرار/الرد النصي بسياقه.
// مستخدم واحد بس، فماب بسيط كفاية.
let pending = null; // { slot, themesShown, items, occasion }

// تنسيق رسالة الاقتراحين.
function formatMessage(items, slot, occasion) {
  const head = occasion
    ? `💌 مناسبة: ${occasion.label}`
    : slot === 'morning'
    ? '🌅 اقتراح الصباح'
    : slot === 'evening'
    ? '🌙 اقتراح المساء'
    : '💌 اقتراح';

  return [
    head,
    '',
    `1️⃣ ${items[0].text}`,
    '',
    `2️⃣ ${items[1].text}`,
    '',
    'اختار، أو انسخ بضغطة 📋، أو ابعتلي نسختك المعدّلة كنص حر.',
  ].join('\n');
}

// أزرار التفاعل تحت الرسالة + زراير "نسخ" بضغطة واحدة.
function buildKeyboard(items) {
  const rows = [
    [
      Markup.button.callback('1️⃣ اختار الأول', 'pick1'),
      Markup.button.callback('2️⃣ اختار الثاني', 'pick2'),
    ],
  ];

  // زراير نسخ تنسخ النص للـ clipboard على طول (Bot API copy_text).
  // الحد الأقصى لنص الزرار 256 حرف، فبنضيفه بس لو الاقتراح قصير كفاية.
  const copyRow = [];
  if (items?.[0] && items[0].text.length <= 256) {
    copyRow.push({ text: '📋 انسخ الأول', copy_text: { text: items[0].text } });
  }
  if (items?.[1] && items[1].text.length <= 256) {
    copyRow.push({ text: '📋 انسخ التاني', copy_text: { text: items[1].text } });
  }
  if (copyRow.length) rows.push(copyRow);

  rows.push([
    Markup.button.callback('🔄 جديد', 'regen'),
    Markup.button.callback('🙈 تجاهل', 'ignore'),
  ]);

  return Markup.inlineKeyboard(rows);
}

/**
 * sendSuggestions — يولّد ويبعت اقتراحين للخانة دي.
 * بيحترم dryRun (يطبع بدل ما يبعت) وثبات الحالة (مش نفس الخانة مرتين/يوم).
 * @param {Telegraf} bot
 * @param {object} opts { slot, occasion, force, target }
 *   target: chat id اختياري (للأوامر الفورية)؛ الافتراضي config.chatId.
 */
async function sendSuggestions(bot, { slot, occasion, force = false, target } = {}) {
  // ثبات الحالة: ما نبعتش نفس الخانة مرتين في نفس اليوم (إلا بالـ force).
  if (!force && store.wasSlotSentToday(slot)) {
    console.log(`⏭️  الخانة (${slot}) اتبعتت النهاردة قبل كده — تخطّي.`);
    return;
  }

  const result = await generateSuggestions({ slot, occasion });
  pending = { slot, themesShown: result.themesShown, items: result.items, occasion };

  const text = (result.offline ? result.note + '\n\n' : '') + formatMessage(result.items, slot, occasion);

  if (config.dryRun) {
    console.log('\n===== [dryRun] مش هيتبعت، ده اللي كان هيوصلك =====');
    console.log(text);
    console.log('================================================\n');
    store.markSlotSentToday(slot);
    return;
  }

  const chatId = target || config.chatId;
  if (!chatId) {
    console.error('⚠️ chatId فاضي في config.js — مش عارف أبعت لمين. ابعت /start للبوت.');
    return;
  }

  await bot.telegram.sendMessage(chatId, text, buildKeyboard(result.items));
  store.markSlotSentToday(slot);
}

// تسجيل تغذية راجعة للجولة الحالية (مصدر واحد بدل ما نكرّر نفس الشكل).
function recordFeedback(choice, finalText) {
  store.addFeedback({
    slot: pending.slot,
    themesShown: pending.themesShown,
    choice,
    finalText,
  });
}

// ---- التعلّم: لما تختار اقتراح (الأول idx=0 أو التاني idx=1) ----
function learnFromPick(idx) {
  if (!pending) return;
  const chosen = pending.items[idx];
  const theme = pending.themesShown[idx] || pending.themesShown[0];

  // النص المختار يدخل ملف الأسلوب، وموضوعه يزيد وزنه، والتاني يقل شوية (ترجيح).
  store.addStyleExample(chosen.text, theme);
  store.bumpThemeWeight(theme, +0.3);
  const otherTheme = pending.themesShown[idx === 0 ? 1 : 0];
  if (otherTheme && otherTheme !== theme) store.bumpThemeWeight(otherTheme, -0.1);

  recordFeedback(idx === 0 ? 'pick1' : 'pick2', chosen.text);
}

// ---- التعلّم: لما تبعت نسخة معدّلة كنص حر (الاختيار النهائي) ----
function learnFromEdit(editedText) {
  if (!pending) return;
  const theme = pending.themesShown[0];
  store.addStyleExample(editedText, theme);
  store.bumpThemeWeight(theme, +0.3);
  recordFeedback('edited', editedText);
}

// ---- التعلّم: تجاهل (المواضيع المعروضة يقل وزنها بدون أمثلة أسلوب) ----
function learnFromIgnore() {
  if (!pending) return;
  for (const t of pending.themesShown) store.bumpThemeWeight(t, -0.2);
  recordFeedback('ignore', null);
}

/**
 * setupHandlers — يربط كل أحداث تيليجرام بالبوت.
 * @param {Telegraf} bot
 */
function setupHandlers(bot) {
  // حارس المالك: قبل ضبط chatId (مرحلة الإعداد) نسمح للكل عشان تجرّب،
  // وبعد ضبطه نرد على المالك بس — تطبيقاً لقاعدة "يكلّمني أنا فقط".
  const isOwner = (ctx) => {
    if (!config.chatId) return true;
    return String(ctx.chat?.id) === String(config.chatId);
  };

  // اقتراح فوري مشترك بين الأوامر (مع التقاط الأخطاء).
  const requestSuggestion = async (ctx, { slot, occasion }) => {
    if (!isOwner(ctx)) return;
    try {
      await sendSuggestions(bot, { slot, occasion, force: true, target: ctx.chat.id });
    } catch (err) {
      console.error('خطأ في اقتراح فوري:', err.message);
      await ctx.reply('⚠️ حصل خطأ وأنا بولّد. جرّب تاني كمان شوية.');
    }
  };

  // /start — يرحّب ويطبع chat_id عشان تحطه في config.
  bot.start((ctx) => {
    const id = ctx.chat.id;
    console.log(`\n🆔 الـ chat_id بتاعك هو: ${id}\n   حطه في config.js (أو متغيّر MY_CHAT_ID).\n`);
    ctx.reply(
      `أهلاً 👋 أنا مساعدك الشخصي لاقتراح رسايل لزوجتك.\n` +
        `الـ chat_id بتاعك: ${id}\n` +
        `حطه في config.js وأعد التشغيل.\n\n` +
        `الأوامر:\n` +
        `/suggest: اقتراح فوري\n` +
        `/occasion: اقتراح مناسبة (أو /occasion عيد جوازنا)\n` +
        `/morning · /evening: اقتراح صباحي/مسائي فوري\n` +
        `/stats: ملخّص · /reset: تصفير التعلّم`
    );
  });

  // /stats — يطبع الملخّص وقت ما تطلبه.
  bot.command('stats', (ctx) => {
    if (!isOwner(ctx)) return;
    const { text } = review.buildReport();
    ctx.reply(text, { parse_mode: 'Markdown' });
  });

  // /reset — يصفّر التعلّم لو حسّيت إن الأسلوب انحرف.
  bot.command('reset', (ctx) => {
    if (!isOwner(ctx)) return;
    store.resetLearning();
    pending = null;
    ctx.reply('🔄 اتصفّر التعلّم بالكامل: أمثلة الأسلوب والأوزان رجعت من جديد.');
  });

  // ---- أوامر الاقتراح الفوري ----
  // /suggest — اقتراح فوري عام (موضوع بالترجيح).
  bot.command('suggest', (ctx) => requestSuggestion(ctx, { slot: 'manual' }));

  // /occasion [نص] — اقتراح مناسبة فوري. لو كتبت نص بعد الأمر بيبقى هو المناسبة،
  // وإلا بياخد مناسبة النهاردة لو فيه، وإلا لمسة حب عامة.
  bot.command('occasion', (ctx) => {
    const arg = ctx.message.text.replace(/^\/occasion(@\S+)?\s*/, '').trim();
    const occasion = arg
      ? { key: 'manual', label: arg }
      : getTodaysOccasion() || { key: 'manual', label: 'لمسة حب من القلب' };
    return requestSuggestion(ctx, { slot: 'occasion', occasion });
  });

  // /morning و /evening — اقتراح فوري بنبرة الخانة دي.
  bot.command('morning', (ctx) => requestSuggestion(ctx, { slot: 'morning' }));
  bot.command('evening', (ctx) => requestSuggestion(ctx, { slot: 'evening' }));

  // ضغط زر الاختيار (الأول/التاني) — معالج واحد للاتنين بدل التكرار.
  // بنبعت النص المختار لوحده في رسالة عشان النسخ/الـ forward يبقى أسهل.
  const handlePick = (idx) => async (ctx) => {
    if (!isOwner(ctx)) return ctx.answerCbQuery();
    const chosen = pending?.items?.[idx]?.text;
    learnFromPick(idx);
    await ctx.answerCbQuery('اتسجّل ✅');
    await ctx.editMessageReplyMarkup(); // نشيل الأزرار بعد الاختيار
    if (chosen) await ctx.reply(chosen);
    await ctx.reply('👆 ده اختيارك — انسخه وابعته بإيدك. حفظت أسلوبك منه 👌');
  };
  bot.action('pick1', handlePick(0));
  bot.action('pick2', handlePick(1));

  bot.action('regen', async (ctx) => {
    if (!isOwner(ctx)) return ctx.answerCbQuery();
    if (!pending) return ctx.answerCbQuery('مفيش اقتراح حالي');
    // نسجّل إن المجموعة الأولى ما عجبتنيش.
    store.addFeedback({
      slot: pending.slot,
      themesShown: pending.themesShown,
      choice: 'regen',
      finalText: null,
    });
    await ctx.answerCbQuery('بولّد اقتراحين جداد...');
    await ctx.editMessageReplyMarkup();
    // نفس الخانة، نعيد التوليد بالـ force، ونبعت لنفس الشات.
    try {
      await sendSuggestions(bot, {
        slot: pending.slot,
        occasion: pending.occasion,
        force: true,
        target: ctx.chat.id,
      });
    } catch (err) {
      console.error('خطأ في إعادة التوليد:', err.message);
      await ctx.reply('⚠️ حصل خطأ وأنا بولّد. جرّب تاني كمان شوية.');
    }
  });

  bot.action('ignore', async (ctx) => {
    if (!isOwner(ctx)) return ctx.answerCbQuery();
    learnFromIgnore();
    await ctx.answerCbQuery('اتسجّل التجاهل');
    await ctx.editMessageReplyMarkup();
    await ctx.reply('تمام، تجاهلنا دي 🙈');
  });

  // أي رد نصي حر = نسختك المعدّلة (الاختيار النهائي).
  bot.on('text', (ctx) => {
    if (!isOwner(ctx)) return;
    const txt = ctx.message.text.trim();
    if (txt.startsWith('/')) return; // أوامر اتعاملنا معاها فوق
    if (!pending) {
      return ctx.reply('مفيش اقتراح حالي أعدّله. استنى اقتراح الصبح/المسا أو اطلب /stats.');
    }
    learnFromEdit(txt);
    ctx.reply('حلو 🌟 سجّلت نسختك المعدّلة وضفتها لأسلوبي. انسخها وابعتها بإيدك.');
  });
}

module.exports = { setupHandlers, sendSuggestions, Telegraf };
