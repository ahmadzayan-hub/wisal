const { test, beforeEach, after } = require('node:test');
const assert = require('node:assert');
const { useTempStore } = require('./helpers');

const tmp = useTempStore();
const llm = require('../llm');
const { generateSuggestions } = require('../generateSuggestion');

beforeEach(() => tmp.cleanup());
after(() => tmp.cleanup());

test('بيرجّع اقتراحين لهم موضوعين', async () => {
  llm.complete = async () => '١- أول اقتراح\n٢- تاني اقتراح';
  const r = await generateSuggestions({ slot: 'morning' });
  assert.strictEqual(r.items.length, 2);
  assert.strictEqual(r.themesShown.length, 2);
  assert.strictEqual(r.items[0].text, 'أول اقتراح');
  assert.strictEqual(r.items[1].text, 'تاني اقتراح');
});

test('بيتجاهل الكلام التمهيدي ويطلّع الاقتراحين المرقّمين', async () => {
  llm.complete = async () => 'اتفضل يا فندم:\n\n١- الاقتراح الحقيقي الأول\n٢- الاقتراح الحقيقي التاني';
  const r = await generateSuggestions({ slot: 'evening' });
  assert.strictEqual(r.items[0].text, 'الاقتراح الحقيقي الأول');
  assert.strictEqual(r.items[1].text, 'الاقتراح الحقيقي التاني');
});

test('ترقيم إنجليزي (1. / 2.) وبأقواس (1) / 2)) شغّال', async () => {
  llm.complete = async () => '1. واحد\n2) اتنين';
  const r = await generateSuggestions({ slot: 'morning' });
  assert.strictEqual(r.items[0].text, 'واحد');
  assert.strictEqual(r.items[1].text, 'اتنين');
});

test('المناسبة بتفرض الموضوع على الاتنين', async () => {
  let captured = '';
  llm.complete = async (msgs) => {
    captured = msgs[1].content;
    return '١- أ\n٢- ب';
  };
  const r = await generateSuggestions({ slot: 'occasion', occasion: { label: 'عيد جوازنا' } });
  assert.deepStrictEqual(r.themesShown, ['عيد جوازنا', 'عيد جوازنا']);
  assert.match(captured, /عيد جوازنا/);
});

test('provider failure returns explicitly labeled local templates', async () => {
  llm.complete = async () => { const e = new Error('انتهت الحصة.'); e.code = 'quota'; throw e; };
  const result = await generateSuggestions({ slot: 'morning' });
  assert.strictEqual(result.offline, true);
  assert.strictEqual(result.items.length, 2);
  assert.match(result.note, /قوالب محلية/);
  assert.match(result.note, /انتهت الحصة/);
});
