# Wisal | وصال — Product Architecture

> Status: living document. Honest about what exists today vs. what is roadmap.
> Vision: «مساحة خاصة لأقرب ناس، يساعدكم الذكاء الاصطناعي على التعبير بصدق، ولا يتحدث نيابة عنكم.»
> Global: "Closer, in every language." / «أقرب، مهما كانت لغتك.»

## Three communication paths

| Path | Status | Notes |
|---|---|---|
| 1. Personal AI Assistant | ✅ **Shipped** | On-device data, AI drafts via Groq (context only at generation time), manual send only |
| 2. Wisal Direct (E2EE) | 🚧 **In progress (Phase 3)** | Device identity + expiring one-time invitations + `wisal://pair` deep link ship in the Android app; `wisal-direct-relay` (device registry + opaque envelope store-and-forward) ships as a backend. Real E2EE (libsignal/vodozemac per ADR-002) is NOT yet integrated. NOT claimed anywhere in product copy until it is, and tested |
| 3. WhatsApp Fallback | ✅ **Shipped** | Opens WhatsApp with the reviewed draft; final Send is always the user's tap |

## Current stack (Architecture Decision Gate: EXTEND, do not rewrite)

- **Android (primary)**: Kotlin + Jetpack Compose Material 3. Layered: `data/` (storage + pure AI engines) → `ui/` (Compose + ViewModel) → `util/` → `work/`. 83+ automated tests in CI (unit + Robolectric + Compose).
- **Windows**: Electron (`wisal-desktop`) with tested pure core (`lib/core.js`). Adaptive desktop layout is roadmap (Phase 4); current UI is functional single-pane.
- **Web**: static Arabic-first site (`wisal-web`), zero external requests, security headers, honest privacy copy.
- **Backend (business mode only)**: `wisal-cloud-api` on Vercel — WhatsApp Business Cloud API relay with HMAC webhook verification, timing-safe auth, rate limiting. **This is not a messaging relay for Direct mode.**
- **Backend (Wisal Direct, in progress)**: `wisal-direct-relay` on Vercel — signature-authenticated device registry + opaque encrypted-envelope store-and-forward (ADR-002 §5.3). Every mutating call requires a signature proving control of the device's private key; the server schema has no plaintext field. Storage: PostgreSQL when `DATABASE_URL` is set, with atomic claims and registrations; production without it refuses every request (503) instead of using memory. No E2EE protocol runs yet — this is the pipe, not the encryption.

Rationale (ADR-001): the existing stack builds green, is tested, and supports the shipped product. A cross-platform rewrite (e.g. Flutter) is only justified when Phase 3 (Direct messaging) starts, and must pass the E2EE/RTL/secure-storage validation checklist first.

## Non-negotiable product principles (enforced today)

- AI never sends, schedules, auto-replies, or impersonates. Every AI output is an editable draft until the user taps Send. (Enforced: no send API is ever called without a user tap; broadcast is one-tap-per-person; Business API send is per-customer button behind explicit configuration.)
- Honest privacy language: "on your device" claims are scoped to Personal Assistant mode; Groq transmission at generation time is disclosed in-app (first-run AI transparency dialog) and on the web; Business mode discloses external provider use before activation.
- Family-first: no ads, feeds, rankings, guilt mechanics, or punitive streaks. The warmth streak shows only when positive and hides at zero (no shaming).

## Modes

- **Personal**: people, occasions, notes, two suggestions, polish-my-draft, smart reply, local history/favorites, local reminders (reminders notify the user; they never send messages).
- **Business (separate, opt-in)**: inside Broadcast behind an explicit toggle; per-customer review + tap; optional Meta Cloud API backend with per-message buttons. No "send all". Consent management + audit trail are Phase 7 work.

## Information architecture (current → target)

Current Android tabs: اليوم · الأشخاص · السجل · إحصائيات · الإعدادات.
Target (§8.1) when Direct ships: اليوم · الأشخاص · المحادثات · الإعدادات (history merges into المحادثات). Business navigation appears only after activation.

## Language architecture

See `docs/localization-strategy.md`. Today: app UI is fully bilingual ar/en with instant RTL/LTR switching (`I18n.t(ar,en)` + per-app language setting); message language/dialect is per-person. CLDR-grade locale framework is the Level C target.
