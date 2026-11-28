package net.aii3n.worldgdrivesync.google;

import java.net.URI;

/** Where the Google calls go. Real Google in the game, a fake server in tests. */
public record GoogleEndpoints(URI authorize, URI token, URI revoke, URI about) {
	public static final GoogleEndpoints GOOGLE = new GoogleEndpoints(
			URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
			URI.create("https://oauth2.googleapis.com/token"),
			URI.create("https://oauth2.googleapis.com/revoke"),
			URI.create("https://www.googleapis.com/drive/v3/about"));
}
