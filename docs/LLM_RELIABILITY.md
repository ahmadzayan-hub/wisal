# LLM reliability contract

Reviewed: 2026-10-23. Applies to Android, Windows desktop and Telegram hosted generation in this repository.

## Source of truth and lifecycle

- Android catalog: `AppConstants.MODELS`, review date and official source constants.
- Node catalog/transport: `wisal-desktop/lib/groq-policy.js`; byte-identical copy in the standalone Telegram folder, guarded by a parity test.
- Active choices: `openai/gpt-oss-20b`, `openai/gpt-oss-120b`.
- Retired known choices: `llama-3.1-8b-instant`, `llama-3.3-70b-versatile` for free/developer usage. Saved user choices are retained, not silently migrated.
- Official references: [models](https://console.groq.com/docs/models), [deprecations](https://console.groq.com/docs/deprecations), [rate limits](https://console.groq.com/docs/rate-limits).

Review provider changes before every release. Catalog inclusion is not proof of access or free eligibility for a particular account. No live credentials were used during development or tests.

## Request limits

Hosted generation defaults off, including upgrades. The user enables it after checking their own organization's plan and limitations. There is no provider failover or plan upgrade in code.

Each request is limited to 16,000 input characters and 1,024 completion tokens, including reasoning allowance where applicable. The interactive time budget is 45 seconds. There are at most two HTTP attempts, and only rate-limit/server failures can retry. Retry-After seconds up to 10 seconds are honored; longer or invalid waits return control to the user. Network failures do not retry automatically. These ceilings are safeguards, not a monetary guarantee or a precise tokenizer-based quota calculation.

## Failure contract

| Condition | Behavior |
| --- | --- |
| Hosted mode disabled | Local templates; no network call |
| Missing key | Actionable settings guidance |
| Retired or unavailable model | Explicit model selection; no silent migration |
| 401 / 403 | Distinguish authentication from account permissions |
| Known exhausted-quota code | Stop immediately, retain local path |
| 429 rate limit | Bounded Retry-After handling |
| 5xx | At most one bounded retry |
| Network / timeout | Safe error; no raw provider body or key in diagnostics |
| Empty / malformed response | Failure, never AI success |
| Cancellation | Cancel in-flight Android requests; never convert cancellation into local success |

Providers sometimes use the same 429 code for multiple limits. If the response does not identify exhausted quota, the UI calls it a usage/rate limit rather than guessing a billing cause.

Local templates are distinctly marked in the Android pending state, desktop result and Telegram message. They may not match the full context, dialect or intent. Review them before sharing.

## Data and models

Prompt data includes the selected name, relationship, notes, context and style examples. The UI must say this before enabling hosted generation. Model instructions prohibit sensitive personality inference and invented commitments. Prompt instructions are not a substitute for reviewing output.

This change does not add a local model server. A future desktop local-server integration must be explicitly configured and tested. A deployed website's `localhost` is not the user's computer. No Android-native local LLM capability is claimed.

## Regression requirements

Keep offline tests for no-network disabled mode, missing credentials, retired models, quota, 429, Retry-After ceilings, malformed/empty output, cancellation and sanitized errors. Preserve legacy model selections and local data during migration. Never test with real family information, production credentials or live customer communications.
