# Google Drive on one shared Google account, with the narrow Drive permission

Shared Worlds live in Google Drive on a single Google account that the whole friend group signs into, and the mod asks only for the narrow "files this app created" permission (`drive.file`). We picked this because per-user accounts would need the full Drive permission to see a folder a friend created. That permission keeps an unverified Google Cloud app in testing mode, where sign-ins expire every 7 days and every friend must be added as a test user. With one account, the mod creates every file itself, so the narrow permission is enough and the app can be published without Google's verification.

## Considered Options

- **Per-user Google accounts, full Drive permission**: rejected because of the weekly re-sign-in and test-user management.
- **Cloudflare R2 / Backblaze B2 (S3-style storage)**: would give truly atomic Lock creation, but needs a credit card and a shared access key. Revisit it if Lock races ever become a real problem.
- **Dropbox / OneDrive**: no meaningful advantage over Drive, and Dropbox's 2 GB free tier is too small for a world plus Backups.

## Consequences

- Everyone in the group knows the shared account's password, so it should be a dedicated account holding nothing else.
- The mod can't see files someone uploads to that Drive by hand. Shared Worlds can only be created through the mod.
- Drive has no atomic "create only if missing" operation, so two players starting at once is settled by "earliest Lock wins" rather than by the storage layer.
