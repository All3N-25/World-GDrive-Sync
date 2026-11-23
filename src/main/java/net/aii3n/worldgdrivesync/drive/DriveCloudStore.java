package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.google.GoogleAuthException;
import net.aii3n.worldgdrivesync.session.Backup;
import net.aii3n.worldgdrivesync.session.BackupLabel;
import net.aii3n.worldgdrivesync.session.CloudStore;
import net.aii3n.worldgdrivesync.session.CloudWorld;
import net.aii3n.worldgdrivesync.session.CrashedSession;
import net.aii3n.worldgdrivesync.session.Lock;
import net.aii3n.worldgdrivesync.session.WorldMetadata;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * The cloud store on the shared Google account's Drive (ADR-0001), over Drive's v3 REST API. It
 * only translates calls; every rule lives in the session coordinator.
 *
 * <p>Layout. Every file is tagged with a private {@code appProperties} key {@code t}, so it can be
 * found again; with the {@code drive.file} permission the mod only ever sees what it created.
 * <pre>
 * World GDrive Sync/            folder        t=root
 *   &lt;world name&gt;/              folder        t=world   (its Drive id is the world id)
 *     current.zip               Current Save  t=current
 *     metadata.json             {"revision":1,"savedAt":"..."}  t=meta
 *     lock-&lt;host id&gt;.json       one per Lock  t=lock, h=&lt;host id&gt;
 *     incoming.zip              a replacing upload in progress  t=incoming
 *     backups/                  folder        t=backups
 *       backup-r&lt;rev&gt;-&lt;savedAt&gt;.zip   one per Backup  t=backup, r, s (+ uh, un, us if Unmerged)
 * </pre>
 */
public final class DriveCloudStore implements CloudStore {
	private static final String API = "https://www.googleapis.com/drive/v3";
	private static final String UPLOAD_API = "https://www.googleapis.com/upload/drive/v3";
	private static final String FOLDER = "application/vnd.google-apps.folder";
	private static final String ROOT_NAME = "World GDrive Sync";
	/** Resumable upload chunk; Drive wants a multiple of 256 KiB. */
	private static final int CHUNK = 8 * 1024 * 1024;
	private static final int ATTEMPTS = 4;

	private final GoogleAccount account;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private volatile String rootId;

	public DriveCloudStore(GoogleAccount account) {
		this.account = account;
	}

	@Override
	public List<CloudWorld> listWorlds() throws IOException {
		List<CloudWorld> worlds = new ArrayList<>();
		for (JsonObject file : search(tagged("world"))) {
			worlds.add(new CloudWorld(string(file, "id"), string(file, "name")));
		}
		return worlds;
	}

	@Override
	public CloudWorld createWorld(String name) throws IOException {
		JsonObject folder = createFolder(name, rootFolder(), "world");
		return new CloudWorld(string(folder, "id"), string(folder, "name"));
	}

	@Override
	public Optional<WorldMetadata> readMetadata(String worldId) throws IOException {
		Optional<String> file = child(worldId, "meta");
		if (file.isEmpty()) return Optional.empty();
		String body = new String(download(file.get()), StandardCharsets.UTF_8);
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			return Optional.of(new WorldMetadata(json.get("revision").getAsInt(), Instant.parse(json.get("savedAt").getAsString())));
		} catch (RuntimeException e) {
			throw new IOException("Unreadable metadata for world " + worldId, e);
		}
	}

	@Override
	public void writeMetadata(String worldId, WorldMetadata metadata) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("revision", metadata.revision());
		json.addProperty("savedAt", metadata.savedAt().toString());
		byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);

		Optional<String> existing = child(worldId, "meta");
		if (existing.isPresent()) {
			send(token -> HttpRequest.newBuilder(URI.create(UPLOAD_API + "/files/" + existing.get() + "?uploadType=media&fields=id"))
					.header("Authorization", "Bearer " + token)
					.header("Content-Type", "application/json")
					.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content)));
		} else {
			createSmallFile("metadata.json", worldId, tag("meta"), "application/json", content);
		}
	}

	@Override
	public void uploadCurrentSave(String worldId, Path zip, LongConsumer bytesSent) throws IOException {
		long size = Files.size(zip);
		Optional<String> existing = child(worldId, "current");
		JsonObject metadata = new JsonObject();
		String sessionStart;
		String method;
		if (existing.isPresent()) {
			sessionStart = UPLOAD_API + "/files/" + existing.get() + "?uploadType=resumable&fields=id";
			method = "PATCH";
		} else {
			metadata.addProperty("name", "current.zip");
			metadata.add("parents", single(worldId));
			metadata.add("appProperties", tag("current"));
			sessionStart = UPLOAD_API + "/files?uploadType=resumable&fields=id";
			method = "POST";
		}
		resumableUpload(sessionStart, method, metadata, zip, bytesSent);
	}

	/**
	 * Uploads the new zip as a separate file tagged {@code t=incoming}, and only once it is complete
	 * moves the old {@code current.zip} into {@code backups/} (renamed, re-tagged {@code t=backup}
	 * with its label) and tags the new file {@code t=current}. Leftover incoming files from an
	 * earlier failed attempt are deleted first.
	 */
	@Override
	public Backup replaceCurrentSave(String worldId, Path zip, BackupLabel previous, LongConsumer bytesSent) throws IOException {
		String oldId = child(worldId, "current").orElseThrow(() -> new IOException("World " + worldId + " has no Current Save to replace"));
		for (JsonObject leftover : search("'" + worldId + "' in parents and " + tagged("incoming"))) {
			deleteFile(string(leftover, "id"));
		}

		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", "incoming.zip");
		metadata.add("parents", single(worldId));
		metadata.add("appProperties", tag("incoming"));
		String newId = string(resumableUpload(UPLOAD_API + "/files?uploadType=resumable&fields=id", "POST", metadata, zip, bytesSent), "id");
		if (newId == null) throw new IOException("Drive didn't say where the upload went");

		JsonObject backup = new JsonObject();
		backup.addProperty("name", "backup-r" + previous.revision() + "-" + previous.savedAt().toString().replace(':', '-') + ".zip");
		backup.add("appProperties", backupProperties(previous));
		String backupsId = backupsFolder(worldId);
		patch(API + "/files/" + oldId + "?addParents=" + enc(backupsId) + "&removeParents=" + enc(worldId) + "&fields=id", backup);

		JsonObject current = new JsonObject();
		current.addProperty("name", "current.zip");
		current.add("appProperties", tag("current"));
		patch(API + "/files/" + newId + "?fields=id", current);
		return new Backup(oldId, previous);
	}

	@Override
	public List<Backup> listBackups(String worldId) throws IOException {
		Optional<String> backupsId = child(worldId, "backups");
		if (backupsId.isEmpty()) return List.of();
		List<Backup> backups = new ArrayList<>();
		for (JsonObject file : search("'" + backupsId.get() + "' in parents and " + tagged("backup"))) {
			try {
				backups.add(new Backup(string(file, "id"), backupLabel(file.getAsJsonObject("appProperties"))));
			} catch (RuntimeException e) {
				throw new IOException("Unreadable Backup in world " + worldId, e);
			}
		}
		return backups;
	}

	@Override
	public void deleteBackup(String worldId, String backupId) throws IOException {
		deleteFile(backupId);
	}

	/**
	 * A Backup's label as short appProperties (each key and value together must fit in 124 bytes):
	 * {@code r} Revision, {@code s} savedAt; for an Unmerged Backup also {@code uh} host id,
	 * {@code un} host name, {@code us} session start.
	 */
	private static JsonObject backupProperties(BackupLabel label) {
		JsonObject properties = tag("backup");
		properties.addProperty("r", Integer.toString(label.revision()));
		properties.addProperty("s", label.savedAt().toString());
		label.unmerged().ifPresent(session -> {
			properties.addProperty("uh", session.hostId());
			properties.addProperty("un", session.hostName());
			properties.addProperty("us", session.startedAt().toString());
		});
		return properties;
	}

	private static BackupLabel backupLabel(JsonObject properties) {
		Optional<CrashedSession> unmerged = properties.has("uh")
				? Optional.of(new CrashedSession(string(properties, "uh"), string(properties, "un"), Instant.parse(string(properties, "us"))))
				: Optional.empty();
		return new BackupLabel(Integer.parseInt(string(properties, "r")), Instant.parse(string(properties, "s")), unmerged);
	}

	private String backupsFolder(String worldId) throws IOException {
		Optional<String> existing = child(worldId, "backups");
		return existing.isPresent() ? existing.get() : string(createFolder("backups", worldId, "backups"), "id");
	}

	private void patch(String url, JsonObject body) throws IOException {
		send(token -> HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())));
	}

	private void deleteFile(String fileId) throws IOException {
		send(token -> HttpRequest.newBuilder(URI.create(API + "/files/" + fileId))
				.header("Authorization", "Bearer " + token).DELETE());
	}

	/** Starts a resumable upload session and sends the zip through it; returns Drive's answer for the finished file. */
	private JsonObject resumableUpload(String sessionStart, String method, JsonObject metadata, Path zip, LongConsumer bytesSent) throws IOException {
		long size = Files.size(zip);
		HttpResponse<byte[]> started = send(token -> HttpRequest.newBuilder(URI.create(sessionStart))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("X-Upload-Content-Type", "application/zip")
				.header("X-Upload-Content-Length", Long.toString(size))
				.method(method, HttpRequest.BodyPublishers.ofString(metadata.toString())));
		URI session = started.headers().firstValue("Location").map(URI::create)
				.orElseThrow(() -> new IOException("Drive didn't start the upload"));
		return new ResumableUpload(session, zip, size, bytesSent).run();
	}

	@Override
	public void downloadCurrentSave(String worldId, Path zip, BytesProgress progress) throws IOException {
		String fileId = child(worldId, "current").orElseThrow(() -> new IOException("World " + worldId + " has no Current Save"));
		JsonObject info = json(send(token -> HttpRequest.newBuilder(URI.create(API + "/files/" + fileId + "?fields=size"))
				.header("Authorization", "Bearer " + token).GET()));
		long size = Long.parseLong(string(info, "size"));
		for (int attempt = 1; ; attempt++) {
			try {
				streamTo(fileId, zip, size, progress);
				return;
			} catch (IOException e) {
				if (attempt >= ATTEMPTS) throw e;
				backOff(attempt);
			}
		}
	}

	/** Streams a large file to disk, reporting bytes as they arrive. One renewal on a 401. */
	private void streamTo(String fileId, Path target, long size, BytesProgress progress) throws IOException {
		boolean renewed = false;
		while (true) {
			String token = accessToken();
			HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/files/" + fileId + "?alt=media"))
					.header("Authorization", "Bearer " + token)
					.timeout(Duration.ofMinutes(5))
					.GET().build();
			HttpResponse<java.io.InputStream> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Download interrupted", e);
			}
			try (java.io.InputStream in = response.body()) {
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

	@Override
	public List<Lock> readLocks(String worldId) throws IOException {
		List<Lock> locks = new ArrayList<>();
		List<JsonObject> files = new ArrayList<>(search("'" + worldId + "' in parents and " + tagged("lock")));
		// Earliest first by Drive's own createdTime (search orders by it); the file id breaks ties.
		files.sort(Comparator.comparing((JsonObject f) -> Instant.parse(string(f, "createdTime"))).thenComparing(f -> string(f, "id")));
		for (JsonObject file : files) {
			String body;
			try {
				body = new String(download(string(file, "id")), StandardCharsets.UTF_8);
			} catch (FileNotFoundException e) {
				continue; // Deleted since the search: a player who lost a simultaneous Play, or a released Lock.
			}
			try {
				JsonObject json = JsonParser.parseString(body).getAsJsonObject();
				locks.add(new Lock(json.get("hostId").getAsString(), json.get("hostName").getAsString(),
						Instant.parse(json.get("startedAt").getAsString()), Instant.parse(json.get("lastHeartbeat").getAsString())));
			} catch (RuntimeException e) {
				throw new IOException("Unreadable Lock in world " + worldId, e);
			}
		}
		return locks;
	}

	@Override
	public void writeLock(String worldId, Lock lock) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("hostId", lock.hostId());
		json.addProperty("hostName", lock.hostName());
		json.addProperty("startedAt", lock.startedAt().toString());
		json.addProperty("lastHeartbeat", lock.lastHeartbeat().toString());
		byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);

		Optional<String> existing = lockFile(worldId, lock.hostId());
		if (existing.isPresent()) {
			send(token -> HttpRequest.newBuilder(URI.create(UPLOAD_API + "/files/" + existing.get() + "?uploadType=media&fields=id"))
					.header("Authorization", "Bearer " + token)
					.header("Content-Type", "application/json")
					.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content)));
		} else {
			JsonObject properties = tag("lock");
			properties.addProperty("h", lock.hostId());
			createSmallFile("lock-" + lock.hostId() + ".json", worldId, properties, "application/json", content);
		}
	}

	@Override
	public void deleteLock(String worldId, String hostId) throws IOException {
		Optional<String> file = lockFile(worldId, hostId);
		if (file.isEmpty()) return;
		try {
			send(token -> HttpRequest.newBuilder(URI.create(API + "/files/" + file.get()))
					.header("Authorization", "Bearer " + token).DELETE());
		} catch (FileNotFoundException e) {
			// Already gone: two players Took Over the same Stale Lock at once.
		}
	}

	private Optional<String> lockFile(String worldId, String hostId) throws IOException {
		List<JsonObject> found = search("'" + worldId + "' in parents and " + tagged("lock")
				+ " and appProperties has { key='h' and value='" + hostId.replace("\\", "\\\\").replace("'", "\\'") + "' }");
		return found.isEmpty() ? Optional.empty() : Optional.of(string(found.getFirst(), "id"));
	}

	/** Sends a zip in chunks to a resumable-upload session, picking up where Drive left off after a failure. */
	private final class ResumableUpload {
		private final URI session;
		private final Path zip;
		private final long size;
		private final LongConsumer bytesSent;
		/** Drive's answer once a status check finds the upload already complete. */
		private JsonObject finished;

		ResumableUpload(URI session, Path zip, long size, LongConsumer bytesSent) {
			this.session = session;
			this.zip = zip;
			this.size = size;
			this.bytesSent = bytesSent;
		}

		/** Sends the whole zip; returns Drive's JSON for the stored file. */
		JsonObject run() throws IOException {
			long offset = 0;
			int failures = 0;
			try (var channel = Files.newByteChannel(zip)) {
				while (true) {
					if (size > 0 && offset >= size) {
						// A status check found everything already stored.
						bytesSent.accept(size);
						return finished;
					}
					int length = (int) Math.min(CHUNK, size - offset);
					byte[] chunk = new byte[length];
					channel.position(offset);
					ByteBuffer buffer = ByteBuffer.wrap(chunk);
					while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
						// keep reading
					}
					String range = size == 0 ? "bytes */0" : "bytes " + offset + "-" + (offset + length - 1) + "/" + size;
					HttpResponse<byte[]> response;
					try {
						response = put(range, chunk);
					} catch (IOException e) {
						if (++failures >= ATTEMPTS) throw e;
						backOff(failures);
						offset = committedOffset();
						continue;
					}
					int status = response.statusCode();
					if (status == 200 || status == 201) {
						bytesSent.accept(size);
						return json(response);
					}
					if (status == 308) {
						offset = rangeEnd(response);
						failures = 0;
						bytesSent.accept(offset);
						continue;
					}
					if (status == 404 || status == 410) throw new IOException("Drive dropped the upload; try again");
					if (retryable(status) && ++failures < ATTEMPTS) {
						backOff(failures);
						offset = committedOffset();
						continue;
					}
					throw new IOException("Drive refused the upload: " + describe(response));
				}
			}
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

		private long committedOffset() throws IOException {
			HttpResponse<byte[]> response = put("bytes */" + size, new byte[0]);
			if (response.statusCode() == 308) return rangeEnd(response);
			if (response.statusCode() == 200 || response.statusCode() == 201) {
				finished = json(response);
				return size;
			}
			throw new IOException("Couldn't resume the upload: " + describe(response));
		}

		/** Next offset from a 308's {@code Range: bytes=0-N} header (none means nothing stored yet). */
		private long rangeEnd(HttpResponse<?> response) {
			return response.headers().firstValue("Range")
					.map(r -> Long.parseLong(r.substring(r.lastIndexOf('-') + 1)) + 1)
					.orElse(0L);
		}
	}

	// --- Drive helpers ---

	private synchronized String rootFolder() throws IOException {
		if (rootId != null) return rootId;
		List<JsonObject> found = search(tagged("root"));
		rootId = found.isEmpty() ? string(createFolder(ROOT_NAME, null, "root"), "id") : string(found.getFirst(), "id");
		return rootId;
	}

	private Optional<String> child(String parentId, String type) throws IOException {
		List<JsonObject> found = search("'" + parentId + "' in parents and " + tagged(type));
		return found.isEmpty() ? Optional.empty() : Optional.of(string(found.getFirst(), "id"));
	}

	private static String tagged(String type) {
		return "appProperties has { key='t' and value='" + type + "' } and trashed=false";
	}

	private List<JsonObject> search(String query) throws IOException {
		List<JsonObject> files = new ArrayList<>();
		String pageToken = null;
		do {
			String url = API + "/files?spaces=drive&pageSize=1000&orderBy=createdTime"
					+ "&fields=" + enc("nextPageToken,files(id,name,createdTime,appProperties)")
					+ "&q=" + enc(query)
					+ (pageToken == null ? "" : "&pageToken=" + enc(pageToken));
			JsonObject page = json(send(token -> HttpRequest.newBuilder(URI.create(url))
					.header("Authorization", "Bearer " + token).GET()));
			JsonArray batch = page.getAsJsonArray("files");
			if (batch != null) batch.forEach(f -> files.add(f.getAsJsonObject()));
			pageToken = string(page, "nextPageToken");
		} while (pageToken != null);
		return files;
	}

	private JsonObject createFolder(String name, String parentId, String type) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		metadata.addProperty("mimeType", FOLDER);
		if (parentId != null) metadata.add("parents", single(parentId));
		metadata.add("appProperties", tag(type));
		return json(send(token -> HttpRequest.newBuilder(URI.create(API + "/files?fields=id,name"))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))));
	}

	private void createSmallFile(String name, String parentId, JsonObject appProperties, String contentType, byte[] content) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		metadata.add("parents", single(parentId));
		metadata.add("appProperties", appProperties);
		String boundary = "wgds-" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
				+ metadata + "\r\n--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.writeBytes(content);
		body.writeBytes(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
		byte[] bytes = body.toByteArray();
		send(token -> HttpRequest.newBuilder(URI.create(UPLOAD_API + "/files?uploadType=multipart&fields=id"))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "multipart/related; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)));
	}

	private byte[] download(String fileId) throws IOException {
		return send(token -> HttpRequest.newBuilder(URI.create(API + "/files/" + fileId + "?alt=media"))
				.header("Authorization", "Bearer " + token).GET()).body();
	}

	/**
	 * Sends a Drive request built for the current access token. A 401 renews the token once; rate
	 * limits and server errors are retried with backoff. Any other non-2xx answer is an IOException.
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

	private static void backOff(int attempt) throws IOException {
		long millis = (long) (500 * Math.pow(2, attempt - 1) * (0.5 + Math.random()));
		try {
			Thread.sleep(millis);
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

	private static JsonObject tag(String type) {
		JsonObject properties = new JsonObject();
		properties.addProperty("t", type);
		return properties;
	}

	private static JsonArray single(String value) {
		JsonArray array = new JsonArray();
		array.add(value);
		return array;
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
