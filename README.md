<div align="center">
  <h1>Wisal · وصال</h1>
  <p><strong>Clear words. Better connections.</strong><br>كلام أوضح. تواصل أقرب.</p>
  <p>A personal drafting companion for family, friends, colleagues and customers.</p>
  <p><a href="android-wife-assistant/README.md">Android guide</a> · <a href="docs/LLM_RELIABILITY.md">Model & quota policy</a> · <a href="docs/privacy-data-flow.md">Privacy</a> · <a href="docs/DEPLOY_WISAL.md">Safe releases</a></p>
</div>

## Thoughtful communication, without the clutter

Wisal helps you decide what to say, not who someone is. Choose a person, select your intent, set the language and tone, then edit two alternatives. Copy or share only when you are happy with the words.

وصال يساعدك تكتب رسالة أوضح وأقرب لأسلوبك: اختر الشخص والهدف واللغة والنبرة، راجع الصياغتين وعدّل، ثم انسخ أو شارك بنفسك. مناسب للأهل والأصدقاء والزملاء والعملاء، وليس للعلاقات العاطفية فقط.

- **A focused Android writing workspace:** one purpose input, contextual recipient, optional language/tone, then editable draft comparison and explicit copy/share.
- **Optional source review:** Arabic/English writing and review roles, at most one repair and four model calls. Additional calls consume account quota; automated review is not a correctness guarantee. See [bounded drafting](docs/BOUNDED_DRAFTING.md).
- **Useful offline:** local Arabic and English templates remain available when AI is off, unavailable or out of quota. They are explicitly labeled, not passed off as AI output.
- **Preferences you control:** edit communication tone, inspect style rules, disable them or reset learning. No sensitive personality inference from a name or relationship.
- **You decide what leaves the app:** personal drafting never sends a message automatically. Opening another app does not prove a message was sent.
- **Optional reminders and advanced tools:** history, draft polishing, reply drafting, groups, calendar and existing integrations remain available.

## AI is optional, not “free unlimited”

Hosted generation uses **Groq** and open-weight **GPT OSS** models. The catalog was reviewed on **2026-10-23** against [Groq's model list](https://console.groq.com/docs/models), [deprecations](https://console.groq.com/docs/deprecations) and [limits](https://console.groq.com/docs/rate-limits).

New installs and upgrades start with hosted generation disabled. Check your Groq organization's plan, model permissions and quotas before enabling it. A model being open-weight does not make its hosted API unlimited or guarantee no charge for your account.

Saved retired model choices are not silently replaced. Pick a supported model explicitly. Authentication, retired models, quota, rate limits and network failures have distinct handling. There is no automatic paid-plan, cross-provider or local-server fallback. See the [reliability contract](docs/LLM_RELIABILITY.md).

## Language support, honestly

The focused Android composer offers **Arabic and English** message controls and local templates. Arabic dialect choices are retained. The existing Spanish UI pack is a beta translation; it is not evidence of Spanish message-generation quality. Other languages and live model quality have not been verified in this change.

## Screens & visual provenance

The original app icons are preserved. Automated Compose UI captures use fictional adults only; capture provenance is recorded in [docs/images/README.md](docs/images/README.md). No private user screenshots, family details or real contact data are included.

## One product, several surfaces

| Folder | Purpose | Stack |
| --- | --- | --- |
| `android-wife-assistant/` | Personal Android app, primary composer | Kotlin, Jetpack Compose |
| `wisal-desktop/` | Windows desktop companion | Electron, Node.js |
| `telegram-wife-assistant/` | Owner-controlled Telegram companion | Node.js, Telegraf |
| `wisal-web/` | Landing and download page | Static HTML/CSS/JS |
| `wisal-cloud-api/` | Optional business-mode backend | Node.js |
| `wisal-direct-relay/` | Optional encrypted delivery relay | Node.js, libsignal integration |
| `docs/` | Architecture, privacy, threat models and release guidance | Markdown |

This update does not remove desktop, web, relay, backups or existing integrations. The Android package remains `com.wisal.app`; historical source directory names are kept for compatibility.

## Build & verify

Android requires JDK 17 and Android SDK 35. Existing tests use JUnit, Robolectric and Compose on the JVM, without an emulator:

```sh
cd android-wife-assistant
./gradlew testDebugUnitTest --no-daemon
```

Node checks do not contact a live provider:

```sh
node wisal-desktop/test/core.test.js
node --test wisal-desktop/test/groq-policy.test.js
cd telegram-wife-assistant && npm ci && npm test
```

The Android workflow uploads unit-test reports and synthetic UI captures. Desktop CI also builds Windows artifacts. Passing a JVM suite is not a physical-device, upgrade-signature or live-provider validation.

## Install and update carefully

[Android releases](https://github.com/ahmadzayan-hub/wisal/releases/tag/android-latest) · [Windows releases](https://github.com/ahmadzayan-hub/wisal/releases/tag/windows-latest)

Release tags are distribution channels, not a promise that they contain this source revision. **Back up first.** An Android upgrade must use the same signing certificate and a higher versionCode than the installed app. Never uninstall to work around a signing conflict: uninstalling can erase private app data.

Android publication is an explicit, verified workflow operation. No new signing identity is generated when the existing release key is missing. See [release instructions](docs/DEPLOY_WISAL.md).

## Privacy & security boundaries

Local storage does not mean local inference. When Groq is enabled, names, selected relationship, notes, message context and selected style examples may be included in the request. Avoid sensitive information. The Android Groq key uses encrypted storage and is excluded from the app's JSON backup. Existing desktop storage has different protections; see the [data-flow map](docs/privacy-data-flow.md).

The optional Direct relay is a separate path from AI generation. Its libsignal design does not make Groq requests end-to-end encrypted. Review [the threat model](docs/THREAT_MODEL_WISAL.md) before deployment.

## Contributing

Keep fixtures fictional, test failure paths, preserve existing stored data and never commit credentials. Propose model changes with official sources and a review date. New languages need reviewed strings and behavioral tests, not a “supports all languages” badge. Keep unrelated integrations intact and explain what was and was not verified.
