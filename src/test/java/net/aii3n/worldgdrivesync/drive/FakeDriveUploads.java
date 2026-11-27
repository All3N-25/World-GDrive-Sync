package net.aii3n.worldgdrivesync.drive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Drive's resumable-upload endpoint (and the token endpoint it needs), running on
 * loopback. Each PUT to the upload session gets the next scripted {@link Answer}; once the script
 * runs out, {@link #otherwise} answers every further one.
 */
final class FakeDriveUploads implements AutoCloseable {
	/** How Drive answers one PUT to the upload session. */
	interface Answer {
		void send(HttpExchange exchange) throws IOException;
	}

	private final HttpServer server;
	private final Queue<Answer> script = new ConcurrentLinkedQueue<>();
	volatile Answer otherwise = status(400);
	/** The {@code Content-Range} of every PUT to the upload session, in order. */
	final List<String> puts = new CopyOnWriteArrayList<>();

	FakeDriveUploads() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/token", exchange -> respond(exchange, 200, "{\"access_token\":\"access\",\"expires_in\":3599}"));
		server.createContext("/upload/files", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().set("Location", base() + "/session");
			respond(exchange, 200, "{}");
		});
		server.createContext("/session", exchange -> {
			exchange.getRequestBody().readAllBytes();
			puts.add(exchange.getRequestHeaders().getFirst("Content-Range"));
			Answer answer = script.poll();
			(answer != null ? answer : otherwise).send(exchange);
		});
		server.start();
	}

	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	String uploadApi() {
		return base() + "/upload";
	}

	URI tokenEndpoint() {
		return URI.create(base() + "/token");
	}

	void then(Answer... answers) {
		script.addAll(List.of(answers));
	}

	/** The connection drops before Drive answers. */
	static Answer dropped() {
		return HttpExchange::close;
	}

	/** "Resume Incomplete" with the first {@code bytes} bytes stored. */
	static Answer received(long bytes) {
		return exchange -> {
			if (bytes > 0) exchange.getResponseHeaders().set("Range", "bytes=0-" + (bytes - 1));
			exchange.sendResponseHeaders(308, -1);
			exchange.close();
		};
	}

	/** The upload is complete and the file stored. */
	static Answer stored(String fileId) {
		return exchange -> respond(exchange, 200, "{\"id\":\"" + fileId + "\"}");
	}

	static Answer status(int status) {
		return exchange -> respond(exchange, status, "{}");
	}

	/** Drive asking the client to slow down, which it does with a 403. */
	static Answer rateLimited() {
		return exchange -> respond(exchange, 403, "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}");
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
