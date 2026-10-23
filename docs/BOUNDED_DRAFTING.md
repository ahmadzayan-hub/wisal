# Bounded drafting and source review

This Android flow is explicitly optional, behind Language & style. It is not an autonomous messenger, a personality profiler, a guarantee of correctness, or a group of human reviewers.

## Actual execution

1. A local deterministic clarifier validates the selected person/language, input lengths and common secret patterns, and asks for context for requests, follow-ups and other source-dependent intents. This step is not described as LLM inference.
2. A writing role receives only the selected name/relationship, typed context, language and tone. It produces exactly two short alternatives in a strict JSON schema. Saved person notes, style examples, contact numbers, photos and message history are not supplied by this flow.
3. A separate review role compares both alternatives with the explicit source for meaning, negation/uncertainty, facts, promises and privacy. A local conservative guard also checks newly introduced numbers, timing, common promises, contact details and sensitive inferences. The review response must match the exact draft-set digest and a strict verdict schema.
4. A rejected pair may receive **one repair**, then must pass review again. Unreviewed or failed drafts are not exposed as successful AI output. Failures use the existing clearly labeled local-template path.
5. Successful drafts reach an editable preview. Only the user's explicit Copy/Share/WhatsApp action leaves the app. Edits invalidate the displayed automated-review status; review is not secretly rerun.

The capability interface has only text completion, not permissions, contacts, browsing, messaging, file access or delivery tools. All roles use the existing Groq adapter and configured catalog. There is no cross-provider/paid fallback. Source data and model outputs are treated as data, not tool instructions.

## Budget and limitations

- At most four logical model calls, one repair, and a 90-second total workflow budget.
- Existing per-call ceilings still apply: 16,000 input characters, 1,024 completion tokens, at most two HTTP attempts and bounded Retry-After. Thus four logical calls can include up to eight HTTP attempts, still bounded by the total workflow timer.
- This reviewed flow currently accepts Arabic and English only. Other languages require explicit implementation and evaluation; no all-language quality claim is made.
- Typed source is limited to 4,000 characters; each draft to 600 characters.
- Cancellation interrupts the active role, including HTTP/backoff through the adapter. Leaving the composer, changing person/preferences/intent or editing context stops in-flight work.
- Hosted generation and this additional review mode are separately opt-in. Additional calls consume the user's account quota. No free/unlimited claim.
- A model reviewing another model can repeat the same mistake. Rule-based checks are conservative tripwires, not complete semantic verification, secret detection or a privacy proof. Human preview remains essential.
- Offline scripted tests prove contracts, failure/cancellation behavior and bounded execution, not real-provider quality. No live provider calls were made for these tests.

## UX rationale

The first view is a compact writing workspace: contextual recipient row, one meaningful purpose input, progressive language/style options and one visible primary action. It removes the oversized slogan, five rows of visible choices and repeated empty-state call to action. After generation, the draft comparison takes over the page; the brief can be reopened without losing the user's context. Wide windows present the two alternatives side by side instead of stretching a phone layout. Secondary tools and integrations remain available.

Controls use a minimum 48 dp target; text can wrap and scale. The page responds to available window height and IME insets. Automated captures cover Arabic/English, large text, wide windows and a short input-focused window. Robolectric does not provide a real device keyboard screenshot; real-IME, screen-reader and installed-device acceptance remain outstanding.

References: [Android adaptive layouts](https://developer.android.com/design/ui/mobile/guides/layout-and-content/adapt-layout), [Android touch targets](https://support.google.com/accessibility/android/answer/7101858?hl=en-GB).

## Identity and safe diagnostics

About shows the actual BuildConfig versionName/versionCode/package. An explicit share button exports only these identifiers, Android API level and the app's public signing-certificate SHA-256 fingerprint (or unavailable). It cannot access Settings, Store, keys, contacts or messages. This is not an upgrade compatibility claim; compare the actual installed certificate and version before releasing an update.

Local development versionCode defaults to 1. Explicit invalid/out-of-range release codes now fail instead of silently falling back to 1. The already published Android APK was inspected as data, not installed: version 1.0.4/code 4. It is not evidence of the version installed on any user's phone. No new signing key or release is created by this change.
