# Calm, truthful motion

This additive Android UI layer keeps the tested writing workspace and data/provider behavior intact.

- Drafts enter once with a short 180 ms fade and 8 dp movement. Options use a short finite disclosure. Editing a draft does not replay its entrance; hidden controls disappear immediately, including accessibility nodes.
- The UI reads Android's animator duration setting and `ValueAnimator.areAnimatorsEnabled()`. Zero/disabled/unknown uses a static equivalent. Non-zero durations run through Compose's system-scaled animation clock. A setting observer and lifecycle resume refresh the read-only preference; no system setting or permission is changed by the app.
- Actual writer/reviewer callbacks drive stage labels. There are no guessed percentages, countdowns, artificial minimum waits, simulated agent activity or infinite shimmer/pulse effects. Cancel remains a stable control outside changing status text; status updates use a polite live region.
- “Copied” appears only after the clipboard action returns successfully. A failure offers retry, and editing resets the confirmation. Opening Share or WhatsApp never claims “sent” or awards a celebration. No automatic copying, contact access, sending, engagement reward or new streak mechanism is introduced.
- Guidance is supportive: take your time, compare and edit, and stop when needed. No guilt, deadline pressure or claims about another person's feelings.

Tests cover system-zero static rendering, focus stability, hidden-node removal, real stage changes and cancellation, failed/successful copy, edit invalidation, finite transition frames and Arabic large text. The draft-card entrance also exports successive controlled test-clock frames; any clip assembled from them is labeled as a Compose test-frame recording, not a real-time phone recording. These are JVM/Compose test renders, not physical-device/real-TalkBack/IME acceptance or live-provider verification.

Official references: [Compose appearance/disclosure APIs](https://developer.android.com/develop/ui/compose/animation/composables-modifiers), [system animator enablement](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()), [Compose duration scale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale).
