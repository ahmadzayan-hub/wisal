# Wisal privacy data flow

Updated 2026-10-23. Describe actual boundaries, not blanket “everything stays local” promises.

## Personal drafting

- Names, relationships, phone numbers, notes and style examples are persisted in each surface's local storage.
- When Groq is explicitly enabled, a request may contain the selected name, relationship, notes, situation, draft and style examples. Phone numbers and photos are not required by the ordinary composer prompt.
- Contact and calendar imports are optional user-triggered features. Imported information may later become generation context if the user uses it; local reading does not imply it can never leave the device.
- Local templates require no provider request. Their displayed source is distinct from AI output.
- Android's Groq key uses encrypted preferences backed by Android Keystore. Legacy plaintext Groq keys are removed only after encrypted migration succeeds. New plaintext fallback writes are prohibited.
- Android JSON backups exclude the Groq key, but contain private people/history/preferences. Sharing a backup exports that private data. Existing backup scope is not a full backup of every integration's state or device identity.
- Desktop currently stores its settings in local JSON, including the provider key. Do not describe that storage as encrypted. Use OS/device protections and avoid sharing the profile directory. OS-backed desktop credential migration remains separate work.
- Other legacy integration secrets have separate stores and need their own review. This change does not claim every historical secret was migrated.

## Sending and optional integrations

Copying, selecting or opening a draft is not proof of delivery. The Android composer records contact only on explicit user confirmation.

Business-mode transmission uses the configured backend and WhatsApp Business service. The Direct relay is a separate libsignal-based delivery feature; see its threat model and ADRs. Neither path makes Groq inference end-to-end encrypted.

There is no new contact scraping, third-party profile fetching, auto-send, analytics SDK or automatic provider switch in this update. The repository preserves existing integrations; evaluate their current configuration before enabling them.

## Verification boundaries

Development uses mock HTTP responses, synthetic adult contacts and JVM/Node tests. No real API key, live generation call, private family screenshot, device permission grant or external message is part of the test fixture.
