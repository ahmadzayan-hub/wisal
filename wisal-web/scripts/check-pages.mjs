// فحص صفحات wisal-web من غير أي dependency: بنية الصفحة، الروابط الداخلية،
// خريطة الموقع، سياسة الأمان (CSP)، وقاعدة ADR-002: مفيش أي ادّعاء تشفير
// إيجابي على الموقع لحد ما التنفيذ والاختبارات تثبته.
// تشغيل: node wisal-web/scripts/check-pages.mjs
// صفحات en/ لازم تكون LTR، وروابط hreflang لازم تكون متبادلة.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
// صفحات الجذر عربي (RTL)، وصفحات en/ إنجليزي (LTR).
const LOCALE_DIRS = { '': { lang: 'ar', dir: 'rtl' }, en: { lang: 'en', dir: 'ltr' } };
const pages = Object.keys(LOCALE_DIRS).flatMap((d) =>
  readdirSync(join(root, d)).filter((f) => f.endsWith('.html')).map((f) => (d ? `${d}/${f}` : f)),
).sort();
const SITE = 'https://wisal-app.vercel.app';
const urlOf = (page) => `${SITE}/${page.replace(/(^|\/)index\.html$/, '$1').replace(/\.html$/, '')}`;
const pageOfUrl = (url) => {
  const rel = url.slice(SITE.length + 1);
  return rel === '' ? 'index.html' : `${rel}.html`;
};
const alternates = new Map(); // page -> Map(hreflang -> url)
const sitemap = readFileSync(join(root, 'sitemap.xml'), 'utf8');
const failures = [];
const fail = (page, msg) => failures.push(`${page}: ${msg}`);

// عبارات ادّعاء تشفير. مسموحة بس لو قبلها نفي قريب ("مش"، "غير"، "not"، "no").
const CLAIM = /(رسائل مشف[ّ]?رة|مشف[ّ]?رة طرف لطرف|تشفير طرف لطرف|end-to-end|\bE2EE\b|encrypted messag)/gi;
const NEGATION = /(مش|غير|ليست|ليس|not|no)\s+\S*\s*$/i;

for (const page of pages) {
  const html = readFileSync(join(root, page), 'utf8');
  const locale = LOCALE_DIRS[page.includes('/') ? page.split('/')[0] : ''];
  const expectedRoot = `<html lang="${locale.lang}" dir="${locale.dir}">`;
  if (!html.includes(expectedRoot)) fail(page, `root must be ${expectedRoot}`);
  if (!/<title>[^<]{5,}<\/title>/.test(html)) fail(page, 'missing <title>');
  if (!/<meta name="description" content="[^"]{20,}">/.test(html)) fail(page, 'missing meta description');
  const canonical = urlOf(page);
  if (!html.includes(`<link rel="canonical" href="${canonical}">`)) fail(page, `canonical must be ${canonical}`);
  if (!sitemap.includes(`<loc>${canonical}</loc>`)) fail(page, 'not listed in sitemap.xml');

  // CSP: script-src 'self' — سكريبت inline أو خارجي هيتمنع بصمت في المتصفح.
  for (const m of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    const attrs = m[1];
    if (/type="application\/ld\+json"/.test(attrs)) continue; // بيانات، مش كود
    const src = /src="([^"]+)"/.exec(attrs);
    if (!src) fail(page, 'inline <script> is blocked by the site CSP');
    else if (/^(https?:)?\/\//.test(src[1])) fail(page, `external script ${src[1]} is blocked by the site CSP`);
  }
  for (const m of html.matchAll(/<(?:img|link|iframe)\b[^>]*(?:src|href)="(https?:\/\/[^"]+)"/gi)) {
    // canonical وalternate روابط وصفية، المتصفح ما بيحمّلهاش.
    if (/^<link rel="(canonical|alternate)"/.test(m[0])) continue;
    fail(page, `external resource ${m[1]} is blocked by the site CSP`);
  }

  // روابط hreflang: نجمعها هنا ونتأكد من التبادل بعد ما نقرا كل الصفحات.
  const alts = new Map();
  for (const m of html.matchAll(/<link rel="alternate" hreflang="([^"]+)" href="([^"]+)">/g)) alts.set(m[1], m[2]);
  if (alts.size) alternates.set(page, alts);

  // كل رابط داخلي لازم يوصل لصفحة أو ملف موجود.
  for (const m of html.matchAll(/href="(\/[^"#?]*)"/g)) {
    const path = m[1];
    if (path === '/') continue;
    const name = path.slice(1);
    if (!existsSync(join(root, name)) && !existsSync(join(root, `${name}.html`))) fail(page, `broken internal link ${path}`);
  }

  // قاعدة ADR-002: مفيش ادّعاء تشفير إيجابي.
  const text = html.replace(/<[^>]+>/g, ' ');
  for (const m of text.matchAll(CLAIM)) {
    const before = text.slice(Math.max(0, m.index - 24), m.index);
    if (!NEGATION.test(before)) fail(page, `positive encryption claim "${m[0]}" (ADR-002 forbids it until release gates pass)`);
  }
}

// hreflang: كل بديل لازم يكون صفحة موجودة، وتشير هي كمان لنفس المجموعة.
for (const [page, alts] of alternates) {
  if (alts.get(locale(page)) !== urlOf(page)) fail(page, `hreflang="${locale(page)}" must point to itself (${urlOf(page)})`);
  if (!alts.has('x-default')) fail(page, 'hreflang group needs x-default');
  for (const [lang, url] of alts) {
    if (lang === 'x-default') continue;
    const target = pageOfUrl(url);
    if (!pages.includes(target)) { fail(page, `hreflang="${lang}" points to missing page ${url}`); continue; }
    const back = alternates.get(target);
    if (!back || back.get(locale(page)) !== urlOf(page)) fail(page, `${target} does not link back with hreflang="${locale(page)}"`);
  }
}

function locale(page) { return LOCALE_DIRS[page.includes('/') ? page.split('/')[0] : ''].lang; }

if (failures.length) {
  console.error(`check-pages: ${failures.length} problem(s)`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`check-pages: ${pages.length} pages OK (${pages.join(', ')})`);
