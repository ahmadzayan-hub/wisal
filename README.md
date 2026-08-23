# Wisal · وصال

## Product Authority

| | |
|---|---|
| **Primary User** | A busy partner / family person |
| **Job To Be Done** | Never neglect the people who matter |
| **System of Record** | Relationship circle, cadences, important dates (local & private) |
| **System of Intelligence** | Message suggestions, contextual reminders |
| **Explicit Non-Goals** | WhatsApp competitor · auto-sending by default · screen-time maximisation |


> Family-messaging assistant — one product, several surfaces.

Wisal (رسايل القلب) helps a busy partner stay warm and present: scheduled
heartfelt messages, smart reminders, and end-to-end-encrypted delivery.
This repository is the **Wisal monorepo**: every folder here is a surface
or service of the same product.

## Repository layout

| Path | Surface | Stack | CI |
| --- | --- | --- | --- |
| `wisal-web/` | Landing + download page | Static HTML/CSS/JS | — |
| `wisal-desktop/` | Windows desktop app | Electron | `desktop.yml` |
| `wisal-cloud-api/` | Business-mode cloud API | Node.js | `cloud-api.yml` |
| `wisal-direct-relay/` | E2EE store-and-forward relay (device registry + envelopes) | Node.js | `direct-relay.yml` |
| `android-wife-assistant/` | Android app (libsignal PQXDH + Double Ratchet) | Kotlin + Gradle | `android.yml` |
| `telegram-wife-assistant/` | Telegram bot | Node.js (PM2) | — |
| `docs/` | Threat models, ADRs, project notes | — | — |

Each folder is self-contained with its own manifest and build; there is no
root application. Android releases are published to the `android-latest`
GitHub Release by CI.

## Sibling products

Products that used to share this tree now live in their own repositories:
[Maktab](https://github.com/ahmadzayan-hub/Maktab) ·
[lahza](https://github.com/ahmadzayan-hub/lahza) ·
[masaar](https://github.com/ahmadzayan-hub/masaar) ·
[vertex](https://github.com/ahmadzayan-hub/vertex) ·
[mutabasir](https://github.com/ahmadzayan-hub/mutabasir) ·
[annual-operation-plan-2026](https://github.com/ahmadzayan-hub/annual-operation-plan-2026)

## Security

The Android ↔ relay path uses real libsignal (PQXDH + Double Ratchet); see
`docs/THREAT_MODEL_WISAL.md` and the ADRs under `docs/` for the verified
design decisions. The relay logs through an allowlist-only logger so message
content can never leak into logs.
