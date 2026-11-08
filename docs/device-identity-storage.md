# Device identity storage failures

The existing P-256 invitation/relay identity must survive storage failures without
falling back to plaintext or silently changing its identity.

`SecureStore.getSecret` and `setSecret` now require encrypted preferences. A legacy
plaintext value is migrated only after a successful synchronous encrypted commit
and matching read-back. Its legacy copy is then removed synchronously. Failed
writes preserve that legacy copy; failed cleanup reports failure and retries
cleanup on the next read. An existing encrypted value takes precedence over a
stale legacy copy. No new secret value is written to plaintext preferences.
After an encrypted write fails or cannot be verified, secret access stays blocked
for the rest of that process: SharedPreferences may retain an uncommitted value in
memory. Restarting the app loads storage again; the app never clears user data or
generates a replacement identity as recovery.

`DeviceIdentityPersistence` creates an identity only when a successful read reports
that none exists. It serializes concurrent first use, verifies persistence before
returning a new identity, and refuses malformed saved records, invalid IDs,
non-P-256 keys and mismatched key pairs without replacing them. Existing JSON
fields stay compatible; unknown metadata is tolerated. Error messages and causes
never carry stored key material. The invitation UI stops with AR/EN guidance when
identity access fails and does not open the sharing flow.

This repairs the existing device-signing identity boundary. It does not persist
Signal protocol identities/pre-keys/sessions, provide lost-device recovery or
establish E2EE release readiness. `mayClaimE2ee` remains false. No real user's data
or keys were read, migrated or removed during development.

Local failure contracts are in [verification/identity-storage](../verification/identity-storage/README.md).
The existing Android workflow remains responsible for Android compilation and
unit tests. Physical-device Keystore and backup/restore acceptance is unverified.

The repository's four Vercel configurations disable automatic deployment only for this
feature branch, using the documented
[`git.deploymentEnabled` setting](https://vercel.com/docs/project-configuration/git-configuration).
Other branches retain their existing behavior. The draft needs no hosted preview.
