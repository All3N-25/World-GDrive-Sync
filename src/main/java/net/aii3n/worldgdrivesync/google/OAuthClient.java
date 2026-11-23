package net.aii3n.worldgdrivesync.google;

/**
 * The Google Cloud project's OAuth client ("Desktop app" type). For installed apps Google does
 * not treat the secret as confidential, but its token endpoint still requires it.
 */
public record OAuthClient(String clientId, String clientSecret) {
	// TODO(owner): replace these placeholders with the real "Desktop app" OAuth client from the
	//  Google Cloud console (APIs & Services > Credentials). The app must be published to
	//  production (not "Testing") or sign-ins expire after 7 days; see ADR-0001.
	public static final String CLIENT_ID = "REPLACE_ME.apps.googleusercontent.com";
	public static final String CLIENT_SECRET = "REPLACE_ME";

	/** The OAuth client built into the mod. */
	public static final OAuthClient BUILT_IN = new OAuthClient(CLIENT_ID, CLIENT_SECRET);

	/** True while the built-in client still has the placeholder values above. */
	public boolean isPlaceholder() {
		return clientId.startsWith("REPLACE_ME") || clientSecret.startsWith("REPLACE_ME");
	}
}
