package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Drive's file metadata endpoints (and the token endpoint they need), running on
 * loopback: ID generation, creating a file without content, reading and deleting it. As in Drive,
 * creating an ID that exists already is refused with a 409.
 */
final class FakeDriveFiles implements AutoCloseable {
	private final HttpServer server;
	/** The name of every stored file, by id. */
	final Map<String, String> files = new ConcurrentHashMap<>();
	/** How many more answers to a create are lost, the file being stored all the same. */
	volatile int answersToLose;
	/** Set to refuse every delete. */
	volatile boolean refusesDeletes;
	private int nextId = 1;

	FakeDriveFiles() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/token", exchange -> respond(exchange, 200, "{\"access_token\":\"access\",\"expires_in\":3599}"));
		server.createContext("/drive/files", this::files);
		server.start();
	}

	private void files(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String rest = exchange.getRequestURI().getPath().substring("/drive/files".length()).replaceFirst("^/", "");
		switch (exchange.getRequestMethod()) {
			case "POST" -> {
				JsonObject metadata = JsonParser.parseString(body).getAsJsonObject();
				String id = metadata.has("id") ? metadata.get("id").getAsString() : "id-" + nextId++;
				boolean stored = files.putIfAbsent(id, metadata.get("name").getAsString()) == null;
				if (answersToLose > 0) {
					answersToLose--;
					exchange.close();
				} else if (stored) {
					respond(exchange, 200, file(id));
				} else {
					respond(exchange, 409, "{\"error\":{\"message\":\"A file already exists with the provided ID.\"}}");
				}
			}
			case "GET" -> {
				if (rest.equals("generateIds")) respond(exchange, 200, "{\"ids\":[\"id-" + nextId++ + "\"]}");
				else respond(exchange, files.containsKey(rest) ? 200 : 404, files.containsKey(rest) ? file(rest) : "{}");
			}
			case "DELETE" -> {
				if (refusesDeletes) respond(exchange, 403, "{}");
				else if (files.remove(rest) == null) respond(exchange, 404, "{}");
				else {
					exchange.sendResponseHeaders(204, -1);
					exchange.close();
				}
			}
			default -> respond(exchange, 405, "{}");
		}
	}

	private String file(String id) {
		return "{\"id\":\"" + id + "\",\"name\":\"" + files.get(id) + "\"}";
	}

	String api() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/drive";
	}

	URI tokenEndpoint() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
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
