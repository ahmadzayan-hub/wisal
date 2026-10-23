# Wisal for Android · وصال

A personal communication assistant for family, friends, colleagues and customers. Kotlin + Jetpack Compose; Android 8.0/API 26 or newer, ARM64 devices. `applicationId` stays `com.wisal.app`, with existing preferences, JSON data and backups preserved.

## The simple flow

1. Add a person and choose the relationship yourself. A fresh install does not invent a spouse.
2. Pick an intent such as check-in, follow-up, request, thanks or apology.
3. Choose Arabic or English and a tone. These are editable communication preferences, not personality diagnoses.
4. Generate two drafts, edit their text, then explicitly copy or share.
5. Save a preferred style if helpful. Only “I confirm I reached out” records a contact; choosing or copying a draft is not delivery evidence.

Additional tools and reminders live behind a secondary section. Existing history, groups, calendar, integrations and style transparency remain available.

## Local first, optional hosted AI

Local templates work without a key or network and are visibly labeled. They do not understand arbitrary context and should be edited.

Groq requires an API key and an explicit enable control after checking your organization's free-plan eligibility, permissions and quotas. The current catalog lists `openai/gpt-oss-20b` and `openai/gpt-oss-120b` (reviewed 2026-10-23). Existing retired Llama selections are preserved until you choose a replacement. No paid or cross-provider fallback occurs.

Background generation is separately opt-in and consumes quota. Background scheduling retains the existing Dubai time zone. Contact reminders and final sending remain user-controlled.

## Privacy

Generation can send recipient name, relationship, notes, context and style examples to Groq. Local persistence is not a promise of local inference. Avoid sensitive data. Groq keys are encrypted using Android Keystore-backed preferences and excluded from JSON backups. If secure storage is unavailable, new keys are not written as plaintext. Legacy plaintext keys migrate only after encrypted storage succeeds.

No contact, calendar, microphone or emulator access was used to validate this change. User-supplied screenshots are not repository fixtures.

## Build and test

Use JDK 17, SDK platform 35 and build tools 35.0.0:

```sh
./gradlew testDebugUnitTest --no-daemon
```

The suite includes model retirement, credential/quota errors, Retry-After, cancellation, response validation, data preservation and Compose flows. UI captures use fictional adults and the actual Compose views under Robolectric, not a physical phone.

## Upgrading an existing installation

Export a backup in Settings. Keep the same application ID, signing certificate and stored-data names. The release versionCode must exceed the installed versionCode, including versions produced before the repository move. Missing release signing creates no replacement key and cannot publish an installable upgrade.

Read [safe release instructions](../docs/DEPLOY_WISAL.md) before building or installing. Do not uninstall the app to fix a signature mismatch.
