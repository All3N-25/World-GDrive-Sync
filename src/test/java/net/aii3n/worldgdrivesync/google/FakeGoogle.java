package net.aii3n.worldgdrivesync.google;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Google's OAuth and Drive "about" endpoints, running on loopback.
 * It only issues tokens for {@link #VALID_CODE} and only when the PKCE verifier matches
 * the challenge the browser was sent with.
 */
final class FakeGoogle implements AutoCloseable {
	static final String VALID_CODE = "good-code";
	static final String ACCESS_TOKEN = "access-1";
	static final String REFRESH_TOKEN = "refresh-1";
	static final String ACCOUNT_EMAIL = "shared-worlds@example.com";

	private final HttpServer server;
	final List<String> revokedTokens = new CopyOnWriteArrayList<>();
	volatile String expectedCodeChallenge;

	FakeGoogle() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/token", this::token);
		server.createContext("/about", this::about);
		server.createContext("/revoke", this::revoke);
		server.start();
	}

	GoogleEndpoints endpoints() {
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		return new GoogleEndpoints(
				URI.create(base + "/auth"),
				URI.create(base + "/token"),
				URI.create(base + "/revoke"),
				URI.create(base + "/about"));
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		boolean ok = "authorization_code".equals(form.get("grant_type"))
				&& VALID_CODE.equals(form.get("code"))
				&& form.get("code_verifier") != null
				&& challengeOf(form.get("code_verifier")).equals(expectedCodeChallenge);
		if (ok) {
			respond(exchange, 200, """
					{"access_token":"%s","expires_in":3599,"refresh_token":"%s","scope":"https://www.googleapis.com/auth/drive.file","token_type":"Bearer"}
					""".formatted(ACCESS_TOKEN, REFRESH_TOKEN));
		} else {
			respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
		}
	}

	private void about(HttpExchange exchange) throws IOException {
		if (!("Bearer " + ACCESS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
			respond(exchange, 401, "{}");
			return;
		}
		respond(exchange, 200, """
				{"user":{"displayName":"Shared Worlds","emailAddress":"%s"}}
				""".formatted(ACCOUNT_EMAIL));
	}

	private void revoke(HttpExchange exchange) throws IOException {
		Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		revokedTokens.add(form.get("token"));
		respond(exchange, 200, "{}");
	}

	static Map<String, String> parseForm(String encoded) {
		Map<String, String> result = new HashMap<>();
		if (encoded == null || encoded.isEmpty()) return result;
		for (String pair : encoded.split("&")) {
			int eq = pair.indexOf('=');
			String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			result.put(key, value);
		}
		return result;
	}

	private static String challengeOf(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
