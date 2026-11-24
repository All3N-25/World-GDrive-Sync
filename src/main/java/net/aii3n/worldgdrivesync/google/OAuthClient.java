package net.aii3n.worldgdrivesync.google;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The Google Cloud project's OAuth client ("Desktop app" type). For installed apps Google does
 * not treat the secret as confidential, but its token endpoint still requires it.
 *
 * <p>The values are kept out of the public repo: the build writes them into {@value #RESOURCE}
 * from {@code GDRIVE_CLIENT_ID} / {@code GDRIVE_CLIENT_SECRET}, taken from the environment (CI)
 * or the gitignored {@code .env} that {@code scripts/setup-google-oauth.sh} fills in.
 */
public record OAuthClient(String clientId, String clientSecret) {
	static final String RESOURCE = "/world-gdrive-sync/oauth-client.properties";
	private static final String PLACEHOLDER = "REPLACE_ME";

	/** The OAuth client built into the mod, or a placeholder if the build had none. */
	public static final OAuthClient BUILT_IN = loadBuiltIn();

	/** True while the built-in client is missing, so sign-in can't work. */
	public boolean isPlaceholder() {
		return clientId.isBlank() || clientSecret.isBlank()
				|| clientId.startsWith(PLACEHOLDER) || clientSecret.startsWith(PLACEHOLDER);
	}

	private static OAuthClient loadBuiltIn() {
		try (InputStream in = OAuthClient.class.getResourceAsStream(RESOURCE)) {
			if (in == null) return new OAuthClient(PLACEHOLDER, PLACEHOLDER);
			Properties props = new Properties();
			props.load(in);
			return new OAuthClient(
					props.getProperty("clientId", PLACEHOLDER).trim(),
					props.getProperty("clientSecret", PLACEHOLDER).trim());
		} catch (IOException e) {
			return new OAuthClient(PLACEHOLDER, PLACEHOLDER);
		}
	}
}
