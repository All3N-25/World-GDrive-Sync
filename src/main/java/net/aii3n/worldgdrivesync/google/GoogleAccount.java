package net.aii3n.worldgdrivesync.google;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The connection to the group's shared Google account (ADR-0001). Sign-in uses Google's
 * installed-app flow: the player's own browser, a loopback redirect, PKCE, and only the
 * {@code drive.file} permission.
 */
public final class GoogleAccount {
	public static final String DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";
	private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(5);
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Replace the access token this long before Google says it expires. */
	private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);

	private final Path tokenFile;
	private final OAuthClient client;
	private final GoogleEndpoints endpoints;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final InstantSource clock;
	private volatile StoredSignIn stored;
	/** Kept in memory only, never written to disk. */
	private volatile AccessToken accessToken;

	/** Loads any sign-in saved by an earlier game session from {@code tokenFile}. */
	public GoogleAccount(Path tokenFile, OAuthClient client, GoogleEndpoints endpoints) {
		this(tokenFile, client, endpoints, InstantSource.system());
	}

	/** As above, with the clock that decides when the access token needs replacing. */
	public GoogleAccount(Path tokenFile, OAuthClient client, GoogleEndpoints endpoints, InstantSource clock) {
		this.tokenFile = tokenFile;
		this.client = client;
		this.endpoints = endpoints;
		this.clock = clock;
		this.stored = StoredSignIn.load(tokenFile).orElse(null);
	}

	/**
	 * A current access token for Drive calls. Blocks while it fetches a new one from Google with the
	 * stored refresh token (when there is none yet, or it is about to expire). Call off the render
	 * thread.
	 *
	 * @throws GoogleAuthException if not connected, if Google can't be reached, or if Google says
	 *                             the sign-in was revoked or expired; in that last case the player
	 *                             is disconnected and must connect again
	 */
	public synchronized String accessToken() {
		StoredSignIn signIn = stored;
		if (signIn == null) throw new GoogleAuthException("Not connected to Google");
		AccessToken current = accessToken;
		if (current != null && clock.instant().isBefore(current.expiresAt().minus(EXPIRY_MARGIN))) return current.value();

		Map<String, String> params = new LinkedHashMap<>();
		params.put("client_id", client.clientId());
		params.put("client_secret", client.clientSecret());
		params.put("refresh_token", signIn.refreshToken());
		params.put("grant_type", "refresh_token");
		HttpResponse<String> response;
		try {
			response = postForm(endpoints.token(), params).join();
		} catch (CompletionException e) {
			throw new GoogleAuthException("Couldn't reach Google", e.getCause());
		}
		JsonObject json = parseJson(response.body());
		if ("invalid_grant".equals(string(json, "error"))) {
			forget(signIn);
			throw new GoogleAuthException("The Google sign-in was revoked or has expired; connect again in settings");
		}
		if (response.statusCode() != 200 || !json.has("access_token")) {
			throw new GoogleAuthException("Google refused to renew access: " + describeError(json, response.statusCode()));
		}
		accessToken = accessTokenFrom(json);
		return accessToken.value();
	}

	/** Drive answered 401 to {@code token}: the next {@link #accessToken()} fetches a new one. */
	public synchronized void accessTokenRejected(String token) {
		AccessToken current = accessToken;
		if (current != null && current.value().equals(token)) accessToken = null;
	}

	private synchronized void forget(StoredSignIn signIn) {
		if (stored != signIn) return;
		stored = null;
		accessToken = null;
		try {
			StoredSignIn.delete(tokenFile);
		} catch (IOException ignored) {
			// It is useless anyway; the next sign-in overwrites it.
		}
	}

	private AccessToken accessTokenFrom(JsonObject tokens) {
		JsonElement expiresIn = tokens.get("expires_in");
		long seconds = expiresIn == null ? 0 : expiresIn.getAsLong();
		return new AccessToken(string(tokens, "access_token"), clock.instant().plusSeconds(seconds));
	}

	private record AccessToken(String value, Instant expiresAt) {
	}

	/** The connected account's email, or empty when not connected. */
	public Optional<String> connectedAccount() {
		StoredSignIn current = stored;
		return current == null ? Optional.empty() : Optional.of(current.accountEmail());
	}

	public boolean isConnected() {
		return connectedAccount().isPresent();
	}

	/**
	 * Starts signing in: opens a loopback listener, then asks {@code openBrowser} to show Google's
	 * sign-in page. Returns at once; the player finishes in the browser.
	 */
	public SignIn signIn(Consumer<URI> openBrowser) {
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		} catch (IOException e) {
			return new SignIn(null, new CompletableFuture<>(),
					CompletableFuture.failedFuture(new GoogleAuthException("Couldn't listen for the browser's answer", e)));
		}
		String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort();
		String state = randomUrlSafe(32);
		String verifier = randomUrlSafe(64);

		CompletableFuture<String> code = new CompletableFuture<>();
		server.createContext("/", exchange -> answerRedirect(exchange, state, code));
		server.start();

		URI authorizationUrl = URI.create(endpoints.authorize() + "?" + form(Map.of(
				"client_id", client.clientId(),
				"redirect_uri", redirectUri,
				"response_type", "code",
				"scope", DRIVE_FILE_SCOPE,
				"code_challenge", challengeOf(verifier),
				"code_challenge_method", "S256",
				"state", state,
				"access_type", "offline",
				"prompt", "consent")));

		CompletableFuture<String> result = code
				.orTimeout(BROWSER_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
				.whenComplete((c, e) -> server.stop(0))
				.thenCompose(c -> exchangeCode(c, redirectUri, verifier))
				.thenCompose(tokens -> fetchAccountEmail(string(tokens, "access_token"))
						.thenApply(email -> remember(new StoredSignIn(string(tokens, "refresh_token"), email), accessTokenFrom(tokens))));

		try {
			openBrowser.accept(authorizationUrl);
		} catch (RuntimeException ignored) {
			// The screen also shows the link, so the player can open it by hand.
		}
		return new SignIn(authorizationUrl, code, result);
	}

	/**
	 * Forgets the stored sign-in on this machine at once, then asks Google to revoke it. The
	 * returned future completes when the revoke attempt is over; a failed revoke is ignored, since
	 * the sign-in is already gone from this machine.
	 */
	public CompletableFuture<Void> disconnect() {
		StoredSignIn previous = stored;
		stored = null;
		accessToken = null;
		try {
			StoredSignIn.delete(tokenFile);
		} catch (IOException e) {
			return CompletableFuture.failedFuture(new GoogleAuthException("Couldn't delete the saved sign-in", e));
		}
		if (previous == null) return CompletableFuture.completedFuture(null);
		return postForm(endpoints.revoke(), Map.of("token", previous.refreshToken()))
				.handle((response, error) -> null);
	}

	private synchronized String remember(StoredSignIn signIn, AccessToken token) {
		if (signIn.refreshToken() == null) {
			throw new GoogleAuthException("Google didn't grant offline access; try connecting again");
		}
		try {
			signIn.save(tokenFile);
		} catch (IOException e) {
			throw new GoogleAuthException("Couldn't save the sign-in", e);
		}
		stored = signIn;
		accessToken = token;
		return signIn.accountEmail();
	}

	private static void answerRedirect(HttpExchange exchange, String expectedState, CompletableFuture<String> code) throws IOException {
		Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
		String page;
		if (query.containsKey("error")) {
			code.completeExceptionally(new GoogleAuthException("Google sign-in was not completed: " + query.get("error")));
			page = "Sign-in was cancelled. You can close this tab and return to Minecraft.";
		} else if (query.containsKey("code")) {
			if (expectedState.equals(query.get("state"))) {
				code.complete(query.get("code"));
				page = "Signed in. You can close this tab and return to Minecraft.";
			} else {
				code.completeExceptionally(new GoogleAuthException("Sign-in answer didn't match this sign-in attempt"));
				page = "This sign-in link is out of date. Start again from Minecraft.";
			}
		} else {
			// Not the redirect (e.g. a favicon request); ignore.
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
			return;
		}
		byte[] body = ("<!doctype html><meta charset=utf-8><title>World GDrive Sync</title><p>" + page + "</p>")
				.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private CompletableFuture<JsonObject> exchangeCode(String code, String redirectUri, String verifier) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("code", code);
		params.put("client_id", client.clientId());
		params.put("client_secret", client.clientSecret());
		params.put("redirect_uri", redirectUri);
		params.put("grant_type", "authorization_code");
		params.put("code_verifier", verifier);
		return postForm(endpoints.token(), params).thenApply(response -> {
			JsonObject json = parseJson(response.body());
			if (response.statusCode() != 200 || !json.has("access_token")) {
				throw new GoogleAuthException("Google refused the sign-in: " + describeError(json, response.statusCode()));
			}
			return json;
		});
	}

	private CompletableFuture<String> fetchAccountEmail(String accessToken) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.about() + "?fields="
						+ URLEncoder.encode("user(displayName,emailAddress)", StandardCharsets.UTF_8)))
				.header("Authorization", "Bearer " + accessToken)
				.timeout(Duration.ofSeconds(30))
				.GET().build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			JsonObject json = parseJson(response.body());
			if (response.statusCode() != 200 || !json.has("user")) {
				throw new GoogleAuthException("Couldn't read the connected account: " + describeError(json, response.statusCode()));
			}
			JsonObject user = json.getAsJsonObject("user");
			String email = string(user, "emailAddress");
			return email != null ? email : string(user, "displayName");
		});
	}

	private CompletableFuture<HttpResponse<String>> postForm(URI uri, Map<String, String> params) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(form(params)))
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String describeError(JsonObject json, int status) {
		String error = string(json, "error");
		String description = string(json, "error_description");
		if (error == null) return "HTTP " + status;
		return description == null ? error : error + " (" + description + ")";
	}

	private static JsonObject parseJson(String body) {
		try {
			JsonElement element = JsonParser.parseString(body);
			return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			return new JsonObject();
		}
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static String form(Map<String, String> params) {
		return params.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}

	private static Map<String, String> parseQuery(String raw) {
		Map<String, String> result = new HashMap<>();
		if (raw == null || raw.isEmpty()) return result;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			result.put(key, eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return result;
	}

	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String challengeOf(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
