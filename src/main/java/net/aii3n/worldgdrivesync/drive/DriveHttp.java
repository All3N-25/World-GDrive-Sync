package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.google.GoogleAuthException;
import net.aii3n.worldgdrivesync.session.CloudStore.BytesProgress;
import net.aii3n.worldgdrivesync.session.NotEnoughSpaceException;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * Drive's v3 REST API over HTTP: authorization with the shared account's access token (renewed once
 * on a 401), retries with backoff on rate limits, server errors and dropped connections, resumable
 * uploads and streamed downloads. Knows nothing about the mod's files; {@link DriveCloudStore} does.
 *
 * <p>A 404 is reported as a {@link FileNotFoundException}; any other failure as an IOException.
 */
final class DriveHttp {
	static final String API = "https://www.googleapis.com/drive/v3";
	private static final String UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";
	/** Resumable upload chunk; Drive wants a multiple of 256 KiB. */
	private static final int CHUNK = 8 * 1024 * 1024;
	private static final int ATTEMPTS = 4;

	private final GoogleAccount account;
	private final String api;
	private final String uploadApi;
	private final Pause pause;
	private static final System.Logger LOGGER = System.getLogger(DriveHttp.class.getName());

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			// NEVER: resumable uploads answer 308 "Resume Incomplete" with no Location header.
			// Following redirects makes java.net.http treat that as a redirect and throw
			// "Invalid redirection" before we can read the Range header.
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	DriveHttp(GoogleAccount account) {
		this(account, API, UPLOAD_API, Thread::sleep);
	}

	/**
	 * As above, with where requests and uploads go and how a back-off waits: a fake Drive and no
	 * waiting in tests.
	 */
	DriveHttp(GoogleAccount account, String api, String uploadApi, Pause pause) {
		this.account = account;
		this.api = api;
		this.uploadApi = uploadApi;
		this.pause = pause;
	}

	/** How a back-off waits. */
	interface Pause {
		void sleep(long millis) throws InterruptedException;
	}

	/** GETs {@code url} (under {@link #API}) and parses the JSON answer. */
	JsonObject getJson(String url) throws IOException {
		return json(send(token -> authorized(url, token).GET()));
	}

	/** POSTs JSON metadata to {@code url}, for a file without content such as a folder. */
	private JsonObject postJson(String url, JsonObject body) throws IOException {
		return json(send(token -> authorized(url, token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
	}

	/**
	 * Creates a file without content from its metadata, such as a folder, exactly once, although a
	 * create whose answer is lost is sent again: the file's id is asked of Drive first, so a repeat
	 * is refused instead of making a second file. If it fails, the file is deleted (best effort), in
	 * case Drive stored it.
	 *
	 * @return the new file's {@code id} and {@code name}
	 */
	JsonObject createOnce(JsonObject metadata) throws IOException {
		String id;
		try {
			id = getJson(api + "/files/generateIds?count=1").getAsJsonArray("ids").get(0).getAsString();
		} catch (RuntimeException e) {
			throw new IOException("Unexpected answer from Google Drive", e);
		}
		JsonObject withId = metadata.deepCopy();
		withId.addProperty("id", id);
		try {
			try {
				return postJson(api + "/files?fields=id,name", withId);
			} catch (IdTaken e) {
				return getJson(api + "/files/" + id + "?fields=id,name"); // an earlier send stored it
			}
		} catch (IOException failure) {
			try {
				delete(id);
			} catch (FileNotFoundException neverStored) {
				// Nothing to clean up.
			} catch (IOException e) {
				LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete " + id + " after failing to create it", e);
				failure.addSuppressed(e);
			}
			throw failure;
		}
	}

	/** Drive's refusal to create a file under an id that is in use. */
	private static final class IdTaken extends IOException {
		IdTaken(String message) {
			super(message);
		}
	}

	/** PATCHes a file's metadata (name, parents in the url, appProperties). */
	void patchJson(String url, JsonObject body) throws IOException {
		send(token -> authorized(url, token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())));
	}

	void delete(String fileId) throws IOException {
		send(token -> authorized(api + "/files/" + fileId, token).DELETE());
	}

	/** A small file's whole content. */
	byte[] download(String fileId) throws IOException {
		return send(token -> authorized(api + "/files/" + fileId + "?alt=media", token).GET()).body();
	}

	/** Replaces a small file's content, by file id: a file deleted meanwhile is a FileNotFoundException, never re-created. */
	void replaceContent(String fileId, String contentType, byte[] content) throws IOException {
		send(token -> authorized(uploadApi + "/files/" + fileId + "?uploadType=media&fields=id", token)
				.header("Content-Type", contentType)
				.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content)));
	}

	/** Creates a small file with its metadata and content in one request. */
	void createSmallFile(JsonObject metadata, String contentType, byte[] content) throws IOException {
		String boundary = "wgds-" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
				+ metadata + "\r\n--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.writeBytes(content);
		body.writeBytes(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
		byte[] bytes = body.toByteArray();
		send(token -> authorized(uploadApi + "/files?uploadType=multipart&fields=id", token)
				.header("Content-Type", "multipart/related; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)));
	}

	/**
	 * Creates a file from a zip with a resumable upload, picking up where Drive left off after a
	 * failure. Drive creates the file only once the upload completes.
	 *
	 * @return Drive's answer for the finished file (its {@code id})
	 * @throws NotEnoughSpaceException if Drive refuses it because its storage is full; not retried
	 */
	JsonObject uploadNewFile(JsonObject metadata, Path zip, LongConsumer bytesSent) throws IOException {
		long size = Files.size(zip);
		try {
			HttpResponse<byte[]> started = send(token -> authorized(uploadApi + "/files?uploadType=resumable&fields=id", token)
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("X-Upload-Content-Type", "application/zip")
					.header("X-Upload-Content-Length", Long.toString(size))
					.POST(HttpRequest.BodyPublishers.ofString(metadata.toString())));
			URI session = started.headers().firstValue("Location").map(URI::create)
					.orElseThrow(() -> new IOException("Drive didn't start the upload"));
			return new ResumableUpload(session, zip, size, bytesSent).run();
		} catch (StorageFull e) {
			throw new NotEnoughSpaceException(size, e);
		}
	}

	/** Drive's refusal to store anything more: the account's storage quota is used up. */
	private static final class StorageFull extends IOException {
		StorageFull(String message) {
			super(message);
		}
	}

	/** Streams a large file to {@code target}, reporting bytes as they arrive; retried from the start on failure. */
	void downloadLargeFile(String fileId, Path target, BytesProgress progress) throws IOException {
		long size = Long.parseLong(getJson(api + "/files/" + fileId + "?fields=size").get("size").getAsString());
		for (int attempt = 1; ; attempt++) {
			try {
				streamTo(fileId, target, size, progress);
				return;
			} catch (IOException e) {
				if (attempt >= ATTEMPTS) throw e;
				backOff(attempt);
			}
		}
	}

	/** One streamed download attempt. One renewal on a 401. */
	private void streamTo(String fileId, Path target, long size, BytesProgress progress) throws IOException {
		boolean renewed = false;
		while (true) {
			String token = accessToken();
			HttpRequest request = authorized(api + "/files/" + fileId + "?alt=media", token)
					.timeout(Duration.ofMinutes(5))
					.GET().build();
			HttpResponse<InputStream> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Download interrupted", e);
			}
			try (InputStream in = response.body()) {
				int status = response.statusCode();
				if (status == 401 && !renewed) {
					account.accessTokenRejected(token);
					renewed = true;
					continue;
				}
				if (status / 100 != 2) throw new IOException("Google Drive refused the download: HTTP " + status);
				try (var out = Files.newOutputStream(target)) {
					byte[] buffer = new byte[64 * 1024];
					long received = 0;
					progress.onProgress(0, size);
					for (int n; (n = in.read(buffer)) >= 0; ) {
						out.write(buffer, 0, n);
						received += n;
						progress.onProgress(received, size);
					}
					if (received != size) throw new IOException("Download ended early (" + received + " of " + size + " bytes)");
				}
				return;
			}
		}
	}

	/** Sends a zip in chunks to a resumable-upload session, picking up where Drive left off after a failure. */
	private final class ResumableUpload {
		private final URI session;
		private final Path zip;
		private final long size;
		private final LongConsumer bytesSent;

		ResumableUpload(URI session, Path zip, long size, LongConsumer bytesSent) {
			this.session = session;
			this.zip = zip;
			this.size = size;
			this.bytesSent = bytesSent;
		}

		/**
		 * Sends the whole zip; returns Drive's JSON for the stored file. Gives up after {@link
		 * #ATTEMPTS} failures with no new bytes stored in between. A failure is a request that fails or
		 * is turned away for now, or a 308 showing nothing new (except the status check's answer right
		 * after a failure).
		 */
		JsonObject run() throws IOException {
			long offset = 0;
			/* The most Drive has ever reported stored: only going past it is progress. */
			long furthest = 0;
			int failures = 0;
			// After a failed request, ask Drive what it has before sending any more.
			boolean recovering = false;
			try (var channel = Files.newByteChannel(zip)) {
				while (true) {
					// With every byte stored, only Drive's answer for the finished file is still missing.
					boolean statusCheck = recovering || (size > 0 && offset >= size);
					String range = statusCheck ? "bytes */" + size : chunkRange(offset);
					byte[] body = statusCheck ? new byte[0] : chunk(channel, offset);
					HttpResponse<byte[]> response;
					try {
						response = put(range, body);
					} catch (IOException e) {
						backOffOrGiveUp(++failures, e);
						recovering = true;
						continue;
					}
					int status = response.statusCode();
					if (status == 200 || status == 201) {
						bytesSent.accept(size);
						return json(response);
					}
					if (status == 308) {
						offset = rangeEnd(response);
						bytesSent.accept(offset);
						if (offset > furthest) {
							furthest = offset;
							failures = 0;
						} else if (!recovering) {
							// The status check after a failure is already counted by that failure.
							backOffOrGiveUp(++failures, new IOException("The upload stopped making progress; try again"));
						}
						recovering = false;
						continue;
					}
					if (status == 404 || status == 410) throw new IOException("Drive dropped the upload; try again");
					if (storageFull(status, response)) throw new StorageFull("Drive refused the upload: " + describe(response));
					IOException refused = new IOException("Drive refused the upload: " + describe(response));
					if (!retryable(status, response)) throw refused;
					backOffOrGiveUp(++failures, refused);
					recovering = true;
				}
			}
		}

		/** Waits before the next attempt, or throws {@code cause} if that was the last one. */
		private void backOffOrGiveUp(int failures, IOException cause) throws IOException {
			if (failures >= ATTEMPTS) throw cause;
			backOff(failures);
		}

		private int chunkLength(long offset) {
			return (int) Math.min(CHUNK, size - offset);
		}

		private String chunkRange(long offset) {
			return size == 0 ? "bytes */0" : "bytes " + offset + "-" + (offset + chunkLength(offset) - 1) + "/" + size;
		}

		private byte[] chunk(SeekableByteChannel channel, long offset) throws IOException {
			byte[] chunk = new byte[chunkLength(offset)];
			channel.position(offset);
			ByteBuffer buffer = ByteBuffer.wrap(chunk);
			while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
				// keep reading
			}
			return chunk;
		}

		private HttpResponse<byte[]> put(String range, byte[] chunk) throws IOException {
			HttpRequest request = HttpRequest.newBuilder(session)
					.header("Content-Range", range)
					.timeout(Duration.ofMinutes(5))
					.PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
					.build();
			try {
				return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Upload interrupted", e);
			}
		}

		/** Next offset from a 308's {@code Range: bytes=0-N} header (none means nothing stored yet). */
		private long rangeEnd(HttpResponse<?> response) {
			return response.headers().firstValue("Range")
					.map(r -> Long.parseLong(r.substring(r.lastIndexOf('-') + 1)) + 1)
					.orElse(0L);
		}
	}

	private static HttpRequest.Builder authorized(String url, String token) {
		return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
	}

	/**
	 * Sends a Drive request built for the current access token. A 401 renews the token once; rate
	 * limits, server errors and dropped connections are retried with backoff. Any other non-2xx
	 * answer is an IOException.
	 */
	private HttpResponse<byte[]> send(Function<String, HttpRequest.Builder> request) throws IOException {
		boolean renewed = false;
		for (int attempt = 1; ; attempt++) {
			String token = accessToken();
			HttpResponse<byte[]> response;
			try {
				response = http.send(request.apply(token).timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted", e);
			} catch (IOException e) {
				if (attempt >= ATTEMPTS) throw e;
				backOff(attempt);
				continue;
			}
			int status = response.statusCode();
			if (status / 100 == 2) return response;
			if (status == 401 && !renewed) {
				account.accessTokenRejected(token);
				renewed = true;
				continue;
			}
			if (retryable(status, response) && attempt < ATTEMPTS) {
				backOff(attempt);
				continue;
			}
			if (status == 404) throw new FileNotFoundException("Google Drive error: " + describe(response));
			if (status == 409) throw new IdTaken("Google Drive error: " + describe(response));
			if (storageFull(status, response)) throw new StorageFull("Google Drive error: " + describe(response));
			throw new IOException("Google Drive error: " + describe(response));
		}
	}

	private String accessToken() throws IOException {
		try {
			return account.accessToken();
		} catch (GoogleAuthException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	private static boolean retryable(int status) {
		return status == 429 || status / 100 == 5;
	}

	private static boolean retryable(int status, HttpResponse<byte[]> response) {
		if (retryable(status)) return true;
		if (status != 403) return false;
		String body = new String(response.body(), StandardCharsets.UTF_8);
		return body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded");
	}

	private static boolean storageFull(int status, HttpResponse<byte[]> response) {
		return status == 403 && new String(response.body(), StandardCharsets.UTF_8).contains("storageQuotaExceeded");
	}

	private void backOff(int attempt) throws IOException {
		long millis = (long) (500 * Math.pow(2, attempt - 1) * (0.5 + Math.random()));
		try {
			pause.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static String describe(HttpResponse<byte[]> response) {
		String body = new String(response.body(), StandardCharsets.UTF_8);
		try {
			JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
			if (error != null && error.has("message")) return "HTTP " + response.statusCode() + " " + error.get("message").getAsString();
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return "HTTP " + response.statusCode();
	}

	private static JsonObject json(HttpResponse<byte[]> response) throws IOException {
		try {
			return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("Unexpected answer from Google Drive", e);
		}
	}
}
