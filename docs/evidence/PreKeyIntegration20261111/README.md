# Pre-key integration evidence

Author: Ahmed Zaian

The final local implementation is commit
`8e6facfc78ccf9d9e9817039e2e8fed0df8f98c9`, based on PR #20
`a926d6bc7aa9b3fc0530c4e40e4be60cf207161d`.

`VerificationSummary.json` records the final counts and limitations. The security
suite has 149 passing tests, including 47 new tests. `SourceManifest.json` binds
each changed source/configuration/document file to the implementation commit.
`EvidenceManifest.json` hashes all other evidence files, excluding only itself.
No source file was changed after the final successful run; documentation changes
do not alter the tested implementation.

The standalone relay-client suite has 27 passing tests. These overlap the full
security suite. The relay has 27 named Node tests and six legacy assertion suites;
PostgreSQL 17.11 actually ran locally. The combined PR #19 checkout repeats all
149 security tests and separately passes the 25 existing pairing tests. Do not
sum overlapping runs as a count of unique tests.

The isolated HTTP suite uses native libsignal, the actual Android transport and
Node API handlers over loopback. A separate JVM test reopens synthetic file
snapshots across process exits. All keys, peers and messages in the fixtures are
synthetic. No real relay, production database or Android device was used.

The superseded clock-fixture failure is retained openly in
`SecurityBeforeTestFixtureCorrection.log` and the compatibility superseded log.
The fault trigger was changed from a count of clock samples to actual durable
pending state, and the complete suites were rerun successfully. The timestamp
regression logs show a separate meaningful RED result against PR #20 and GREEN
result against the fix.

Android SDK tests were not run locally. Android Keystore, ARM64 devices,
backup/restore, snapshot/database rollback resistance and final E2EE acceptance
remain unverified. `mayClaimE2ee=false` is retained. Remote CI results, if later
available, are a separate evidence layer and do not alter these local results.
