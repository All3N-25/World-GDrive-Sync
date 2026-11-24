# Google OAuth and Drive notes

The Drive folder layout and its `appProperties` tags are documented in the `DriveCloudStore` javadoc. HTTP, token renewal, retries and resumable transfers live in `DriveHttp`. This file holds the reasons and the traps that the code doesn't spell out.

## Why raw HTTP, not the Google client libraries

Loom's `include` is not transitive. Bundling `google-api-client` and `google-api-services-drive` puts only those jars in the mod, and about 20 transitive jars (httpclient, grpc, opencensus, auth-library, …) would be missing at runtime. The mod therefore calls OAuth and Drive v3 with `java.net.http` plus the Gson that Minecraft already ships. The game's Java 25 runtime includes `java.net.http` and `jdk.httpserver`, which runs the loopback redirect server.

Before adding any dependency, check that it ends up in the jar: `unzip -l build/libs/*.jar | grep META-INF/jars`. Don't bundle libraries Minecraft already provides (Gson, Guava, commons-codec, slf4j).

## OAuth

- The OAuth client is a **Desktop app**. Google still requires its secret at the token endpoint, so both the ID and the secret are built into the mod (`OAuthClient`). They stay out of this public repo: the build reads `GDRIVE_CLIENT_ID` and `GDRIVE_CLIENT_SECRET` from the environment (CI secrets) or the gitignored `.env`, and `scripts/setup-google-oauth.sh` walks through creating the client and filling both in.
- `access_type=offline` plus `prompt=consent` guarantees a refresh token. `invalid_grant` on refresh means the sign-in is gone; treat it as disconnected.
- An app left in **Testing** mode gets refresh tokens that expire after 7 days. It must be published to production (ADR-0001). A production app without brand verification may still show Google's "unverified app" page; confirm in the Cloud console.
- `drive.file` lets the mod see only files it created. Files added to the Drive by hand are invisible by design.

## Drive v3 traps

- **`appProperties`** allow at most 124 bytes per key plus value, so keep keys to one or two letters. Anything bigger goes in a small JSON file's content (as the Lock does).
- **Lock ordering** uses Drive's server-set `createdTime`, then the file ID, never players' clocks.
- **Resumable uploads:** chunks must be multiples of 256 KiB. `308` means "send the next chunk" (read the `Range` header). On a dropped connection, query the committed offset with `Content-Range: bytes */<total>` and resume.
- **Never let `java.net.http` follow redirects** for Drive calls: it treats the resumable upload's `308` as a redirect and throws "Invalid redirection" because there is no `Location`.
- **`java.net.http` forbids setting `Content-Length`.** It comes from the body publisher, so send each chunk with `BodyPublishers.ofByteArray`.
- **`DELETE`** is permanent; there is no trash.
- **Stale status still depends on clocks.** It compares the viewer's clock with the Host's Heartbeat time. Computing "now" from the `Date` header of a Drive response would remove that dependency; not done yet.
