# Wisal — Implementation Roadmap (vertical slices)

> Every phase ends with: lint/checks, tests, builds green in CI, results recorded here.

## Phase 0 — Audit & recovery ✅ DONE
- Deep assessment with measured evidence: `docs/DEEP_ASSESSMENT.md`.
- H1/H2 (atomic storage + process-wide lock), M3 (desktop CI), M4/M5 (webhook signature + rate limit) fixed and CI-verified.
- Dangerous placeholder scan: no hard-coded secrets; Groq key in Android Keystore; backups exclude the key.

## Phase 1 — Design foundation ✅ DONE (this iteration)
- Global design tokens (Porcelain/Midnight/Coral/Amber/Teal) on Android theme + web CSS variables. Light-first, Midnight dark as user option.
- New Living Link logo (native SVG: logo + favicon variants, works at 16px).
- AR/EN i18n layer with instant RTL/LTR switch; all 10 Android screens bilingual; language picker in Settings; no flags for languages.
- Web rebuilt on the global identity with honest trust claims (NO E2EE claim — not built yet).

## Phase 2 — Personal AI assistant ✅ SHIPPED (pre-existing, kept)
- People/occasions/notes, two suggestions, polish, smart reply, style learning (local), history, reminders.
- Remaining in-phase work: style-learning transparency screen («ما الذي تعلّمه وصال؟») with editable rules; local model provider abstraction (today: Groq external provider with disclosure; template fallback exists offline).

## Phase 3 — Wisal Direct (E2EE) 🚧 IN PROGRESS — release-gated by security
1. ✅ ADR-002: E2EE library evaluation (libsignal vs vodozemac vs OpenMLS). Owner decision: support both `signal` and `vodozemac` build variants behind a protocol-agnostic `CryptoProvider` abstraction.
2. ✅ Identity: device-generated EC P-256 keypair (`DeviceIdentityCodec`), no contact-book upload, no phone/email required.
3. ✅ Pairing: 48h expiring one-time invitations, `wisal://pair` deep link, accept screen (pending/accepted/expired/already-used states), replay-guarded.
4. ✅ Relay backend (`wisal-direct-relay` — separate service, NOT `wisal-cloud-api`): signature-authenticated device registry, opaque encrypted-envelope submit/inbox/ack, 14-day max TTL, sweep-on-fetch, delete-on-ack, automated plaintext-leak test, allowlist-only logger.
5. ✅ Real `CryptoProvider` backend: `LibsignalCryptoProvider` (libsignal 0.86.5, PQXDH + Double Ratchet), verified against actual published bytecode and a real executed handshake, not documentation. In-memory only — no persistence, no relay wiring yet.
6. ✅ Relay side of pre-key exchange: `POST /api/prekeys` (signed, timestamped publish; monotonic one-time pre-key ids block replay) and `POST /api/prekeys-claim` (signed claim; one-time pre-key handed out once, then deleted). Tests in `wisal-direct-relay/test/prekeys.test.js`. Android client calls not built yet.
7. ✅ Relay persistence: PostgreSQL store behind the same interface, atomic claim/registration/publish, production fails closed (503) without `DATABASE_URL`; tested against real PostgreSQL 16 in CI including concurrent claims.
8. 🗺️ NEXT: Android client for publish/claim; persist identity/pre-keys (parallel to `DeviceIdentityStore`); one-to-one chat UI wired to real two-device exchange; offline delivery test; privacy-neutral push notifications; external security review before any E2EE claim.
- **Rule: no E2EE marketing claims until implementation + tests genuinely support it.** Today's slices (identity, pairing, relay) carry zero encryption claims — the accept screen says so explicitly. Mock transports must be DEMO_ONLY-flagged and blocked in production builds (enforced: `CryptoProviderFactory` throws if `DEMO_ONLY` is requested outside a debug build).

## Phase 4 — Windows adaptive UI 🗺️
- Rail/sidebar + chat-list pane + conversation pane; keyboard shortcuts; window resize/scaling tests; QR device linking.

## Phase 5 — Website expansion 🗺️
- Pages: how-it-works, privacy, security architecture, AI principles, FAQ, business (separate), localized routes + hreflang for Level A locales; real screenshots from running apps.

## Phase 6 — Family Circles 🗺️ (after 1:1 is stable)
## Phase 7 — Wisal Business workspace 🗺️
- Separate surface, consent records (source/date/purpose/status), consent-gated sending, audit trail, opt-out. Today's business mode already enforces per-person manual review and no bulk send.

## Verification log
- 2026-08-13: Android CI green at eb959ea (83 tests); cloud-api CI green at e76e619; desktop tests green locally+CI; web validated (HTML balance, JS syntax, screenshots light/dark/mobile/desktop, zero console errors). Localization commit 5a37f22 pending CI — see Actions.
