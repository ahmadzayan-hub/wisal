# 🗄️ Memory — سجل الجلسات (Sessions Log)

ملخّص مختصر لكل جلسة عمل (الأحدث فوق). الغرض: تتبّع القرارات والمخرجات.

## قالب القيد
```
### <تاريخ> — <عنوان الجلسة>
- الهدف:
- اتعمل:
- قرارات:
- متبقّي:
```

---

### مرحلة تفعيل الـ Agent OS — مهارات/أدوات فعّالة + ذاكرة حيّة
- الهدف: تنفيذ الأربع خطوات دفعة واحدة (Skills/Tools تفاعلية، Memory حيّة، جاهزية Play، جاهزية Vercel).
- اتعمل: خلّيت بطاقات Skills/Tools في الديسكتوب تشتغل بضغطة فعليّة (نيّة تكتب على طول،
  تحرير يعدّل آخر رسالة، أدوات واتساب/نسخ/متصفح حقيقية)؛ سكربت `agent-os/memory/sync.js`
  + workflow `memory-sync.yml` يجدّد سجل الجلسات أوتوماتيك من تاريخ Git بعد كل دمج؛
  دليل توقيع Play ودليل نشر Vercel (الكود جاهز، الباقي أسرار/حساب من المستخدم).
- قرارات: الأدوات كلها human-in-the-loop (مفيش إرسال تلقائي)؛ الذاكرة تتولّد من Git مش يدوي.
- متبقّي (من ناحية المستخدم): أسرار keystore على GitHub + حساب Play؛ ربط Vercel.

### مرحلة تأسيس الـ Agent OS — تنظيم + واجهة OS
- الهدف: تنظيم الريبو بهيكل agentic (ج) + إعادة تصميم الديسكتوب كواجهة OS (ب).
- اتعمل: مجلد `agent-os/` بالأعمدة الستة (Agents/Skills/MCP/Memory/Brain/LLM)؛
  وإعادة تصميم واجهة `wisal-desktop` لشكل نظام تشغيل agentic.
- قرارات: النطاق لعائلة وصال فقط (مش مشروع lahza)؛ الأدوات محلية (MCP مفهوم تنظيمي).
- متبقّي: توقيع Play، تكامل أسعار حقيقي، قنوات إرسال إضافية.

### مرحلة الديسكتوب + النشر
- الهدف: نسخة ويندوز + تجهيز Play + مراجعة.
- اتعمل: تطبيق Electron (`wisal-desktop`) + CI ويندوز + أيقونة مخصّصة؛ AAB + صفحة خصوصية
  + اسم تجاري «وصال» + `com.wisal.app`؛ أزرار تنزيل أندرويد/ويندوز في الموقع.
- قرارات: Electron لتوافق واسع؛ توقيع رسمي مؤجّل لحد ما المستخدم يضيف الأسرار.

---

<!-- AUTO:START -->

## سجل تلقائي (Auto Log) — من تاريخ Git

| التاريخ | الميلستون | commit |
| --- | --- | --- |
| 2026-08-18 | Fix CI: cut APK size from libsignal's multi-ABI native libs, harden R8 + dist pipeline | `2fa21b1` |
| 2026-08-18 | Fix CI: enable core library desugaring for libsignal-android's AAR metadata requirement | `4a30dfd` |
| 2026-08-13 | Add Spanish (es) locale pack, style-learning transparency screen, ADR-002 E2EE | `1b0474d` |
| 2026-08-09 | ci: publish wisal.apk/aab to apk-dist branch as extra distribution channel | `d6b8a08` |
| 2026-08-09 | docs: add project CHANGELOG covering all releases [skip ci] | `79bd8eb` |
| 2026-08-03 | Add real-screen Compose UI tests: Welcome + History compose without crash (Robolectric) | `31299fb` |
| 2026-08-03 | Add Compose UI test layer (Robolectric): smoke test for composition + interaction | `a5e049d` |
| 2026-08-03 | Add automated test layer: Robolectric data-layer tests guarding JSON/R8 serialization | `005b159` |
| 2026-08-03 | docs: third-party expert assessment of smart-features release (gaps + advice) [skip ci] | `0027515` |
| 2026-08-03 | Add smart features: quick tone switch, reconnect intent, global-occasion radar, WhatsApp Business customer-reply mode | `2413743` |
| 2026-08-03 | Add smart feature: 'حسّن رسالتي' (Polish My Draft) — rewrites your own outgoing message into 2 warmer in-persona versions, learns your style, manual send only | `990bf5f` |
| 2026-08-03 | Fix R8 release build: -dontwarn Tink/errorprone compile-only annotations | `8b0c65c` |
| 2026-08-03 | Performance: lazy broadcast list (LazyColumn) + single-read next-action (#93) | `d8071f5` |
| 2026-08-02 | Privacy transparency + accessibility labels + CI signing status (#90) | `b36d99b` |
| 2026-07-23 | feat(checkout): actually deliver the completed order to the business (WhatsApp) | `176ec43` |
| 2026-07-21 | Add V0.2: live KPI entry, full bilingual UI, snapshot save/load | `78f2947` |
| 2026-07-12 | Add generic Annual Operational Plan 2026 V0.1 (single self-contained HTML) | `e08e330` |
| 2026-07-20 | Transformation audit: P1 main-thread IO fix, P2 DRY+tests, CI resilience (#78) | `e003da2` |
| 2026-07-15 | Smart feature: رد ذكي — suggest replies to a received message (#77) | `6cb47b5` |
| 2026-07-15 | Debug review fixes: desktop occasion/slot, Android member-key collision, re-import state (#76) | `b03807c` |
| 2026-07-15 | Smart: proactive next-best-action on Android home + remove desktop tap effect (#75) | `954d4a0` |
| 2026-07-15 | Android: Material 3 Expressive redesign (modern 3D), remove tap effect, smarter hero (#74) | `53fc862` |
| 2026-07-14 | Android broadcast UX: copy-all/export, preview-first, favorite templates (#73) | `815b69a` |
| 2026-07-14 | Review fixes: phone-normalize bug + testable util + desktop parity + bulk cap (#72) | `a4bc92c` |
| 2026-07-14 | Android: AI-personalized message per member + watercolor tap effect (#71) | `3f13af6` |

> بيتولّد أوتوماتيك بواسطة `agent-os/memory/sync.js` — متعدّلش القسم ده بإيدك.

<!-- AUTO:END -->
