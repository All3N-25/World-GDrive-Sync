# World-GDrive-Sync

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Google sign-in

The mod needs a Google OAuth client (Desktop app) to sign in to Drive. Run `scripts/setup-google-oauth.sh` once: it walks you through the Google Cloud console, writes the client ID and secret to the gitignored `.env` that the build reads, and can set them as GitHub secrets for CI. Without them the mod still builds, but sign-in shows as unavailable.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
