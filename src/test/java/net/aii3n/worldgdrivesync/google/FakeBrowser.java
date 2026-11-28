package net.aii3n.worldgdrivesync.google;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Plays the part of the player's web browser: it receives the sign-in URL, and the player
 * "answers" Google's consent page by sending the browser to the loopback redirect with
 * whatever query the test chooses.
 */
final class FakeBrowser implements Consumer<URI> {
	private final HttpClient http = HttpClient.newHttpClient();
	private final Function<Map<String, String>, String> answer;
	volatile URI openedUri;
	volatile Map<String, String> openedQuery;
	/** The page the loopback redirect showed; fails if the connection dropped before it arrived. */
	final CompletableFuture<String> redirectPage = new CompletableFuture<>();

	private FakeBrowser(Function<Map<String, String>, String> answer) {
		this.answer = answer;
	}

	/** Player approves: Google redirects back with a valid code and the original state. */
	static FakeBrowser approving(FakeGoogle google) {
		return new FakeBrowser(query -> {
			google.expectedCodeChallenge = query.get("code_challenge");
			return "code=" + FakeGoogle.VALID_CODE + "&state=" + enc(query.get("state"));
		});
	}

	/** Player presses Cancel on Google's consent page. */
	static FakeBrowser denying() {
		return new FakeBrowser(query -> "error=access_denied&state=" + enc(query.get("state")));
	}

	/** Something other than our sign-in hits the loopback with a forged state. */
	static FakeBrowser forgingState(FakeGoogle google) {
		return new FakeBrowser(query -> {
			google.expectedCodeChallenge = query.get("code_challenge");
			return "code=" + FakeGoogle.VALID_CODE + "&state=forged";
		});
	}

	/** Player never finishes in the browser. */
	static FakeBrowser idle() {
		return new FakeBrowser(null);
	}

	@Override
	public void accept(URI uri) {
		openedUri = uri;
		openedQuery = FakeGoogle.parseForm(uri.getRawQuery());
		if (answer == null) return;
		URI redirect = URI.create(openedQuery.get("redirect_uri") + "?" + answer.apply(openedQuery));
		http.sendAsync(HttpRequest.newBuilder(redirect).GET().build(), HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> {
					if (error != null) redirectPage.completeExceptionally(error);
					else redirectPage.complete(response.body());
				});
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
