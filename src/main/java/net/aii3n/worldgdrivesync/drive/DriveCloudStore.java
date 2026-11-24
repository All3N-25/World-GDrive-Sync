package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.session.Backup;
import net.aii3n.worldgdrivesync.session.BackupLabel;
import net.aii3n.worldgdrivesync.session.CloudStore;
import net.aii3n.worldgdrivesync.session.CloudWorld;
import net.aii3n.worldgdrivesync.session.CrashedSession;
import net.aii3n.worldgdrivesync.session.Lock;
import net.aii3n.worldgdrivesync.session.WorldMetadata;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * The cloud store on the shared Google account's Drive (ADR-0001): where each thing lives in Drive
 * and how it is tagged. It only translates calls; every rule lives in the session coordinator, and
 * HTTP, authorization and retries live in {@link DriveHttp}.
 *
 * <p>Layout. Every file is tagged with a private {@code appProperties} key {@code t}, so it can be
 * found again; with the {@code drive.file} permission the mod only ever sees what it created.
 * <pre>
 * World GDrive Sync/            folder        t=root
 *   &lt;world name&gt;/              folder        t=world   (its Drive id is the world id)
 *     current.zip               Current Save  t=current, r=&lt;Revision&gt;, s=&lt;savedAt&gt;
 *     metadata.json             only in worlds shared before r/s moved onto current.zip  t=meta
 *     lock-&lt;host id&gt;.json       one per Lock  t=lock, h=&lt;host id&gt;
 *     incoming.zip              a replacing upload in progress  t=incoming
 *     backups/                  folder        t=backups
 *       backup-r&lt;rev&gt;-&lt;savedAt&gt;.zip   one per Backup  t=backup, r, s (+ uh, un, us if Unmerged)
 * </pre>
 */
public final class DriveCloudStore implements CloudStore {
	private static final String API = DriveHttp.API;
	private static final String FOLDER = "application/vnd.google-apps.folder";
	private static final String ROOT_NAME = "World GDrive Sync";
	private static final System.Logger LOGGER = System.getLogger(DriveCloudStore.class.getName());

	private final DriveHttp drive;
	private volatile String rootId;

	public DriveCloudStore(GoogleAccount account) {
		this.drive = new DriveHttp(account);
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

	/**
	 * The Revision and save time on the Current Save's own {@code appProperties}. If several files are
	 * tagged {@code t=current} (a replacement whose clean-up failed), the highest Revision is the
	 * Current Save. A world shared before Revisions moved onto {@code current.zip} has none there,
	 * and its {@code metadata.json} is read instead.
	 */
	@Override
	public Optional<WorldMetadata> readMetadata(String worldId) throws IOException {
		Optional<JsonObject> current = currentSave(worldId);
		if (current.isEmpty()) return Optional.empty();
		Optional<WorldMetadata> tagged = revisionOf(current.get());
		return tagged.isPresent() ? tagged : legacyMetadata(worldId);
	}

	private Optional<WorldMetadata> legacyMetadata(String worldId) throws IOException {
		Optional<String> file = child(worldId, "meta");
		if (file.isEmpty()) return Optional.empty();
		String body = new String(drive.download(file.get()), StandardCharsets.UTF_8);
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			return Optional.of(new WorldMetadata(json.get("revision").getAsInt(), Instant.parse(json.get("savedAt").getAsString())));
		} catch (RuntimeException e) {
			throw new IOException("Unreadable metadata for world " + worldId, e);
		}
	}

	/** The file that is the Current Save: of the files tagged {@code t=current}, the highest Revision. */
	private Optional<JsonObject> currentSave(String worldId) throws IOException {
		return currentSaveFiles(worldId).stream().max(Comparator.comparingInt(DriveCloudStore::revisionNumber));
	}

	private List<JsonObject> currentSaveFiles(String worldId) throws IOException {
		return search("'" + worldId + "' in parents and " + tagged("current"));
	}

	private static int revisionNumber(JsonObject file) {
		return revisionOf(file).map(WorldMetadata::revision).orElse(-1);
	}

	private static Optional<WorldMetadata> revisionOf(JsonObject file) {
		JsonObject properties = file.getAsJsonObject("appProperties");
		if (properties == null || !properties.has("r") || !properties.has("s")) return Optional.empty();
		try {
			return Optional.of(new WorldMetadata(Integer.parseInt(string(properties, "r")), Instant.parse(string(properties, "s"))));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	@Override
	public void uploadCurrentSave(String worldId, Path zip, WorldMetadata metadata, LongConsumer bytesSent) throws IOException {
		JsonObject file = new JsonObject();
		file.addProperty("name", "current.zip");
		file.add("parents", single(worldId));
		file.add("appProperties", currentProperties(metadata));
		drive.uploadNewFile(file, zip, bytesSent);
	}

	/**
	 * Uploads the new zip as a separate file tagged {@code t=incoming}. Once it is complete, one
	 * PATCH names it {@code current.zip} and tags it {@code t=current} with its Revision: from that
	 * call on it is the Current Save, as the highest Revision. Then the old Current Save moves into
	 * {@code backups/}. Leftover incoming files from an earlier failed attempt are deleted first.
	 */
	@Override
	public Backup replaceCurrentSave(String worldId, Path zip, WorldMetadata next, BackupLabel previous, LongConsumer bytesSent) throws IOException {
		JsonObject old = currentSave(worldId).orElseThrow(() -> new IOException("World " + worldId + " has no Current Save to replace"));
		for (JsonObject leftover : search("'" + worldId + "' in parents and " + tagged("incoming"))) {
			drive.delete(string(leftover, "id"));
		}

		JsonObject incoming = new JsonObject();
		incoming.addProperty("name", "incoming.zip");
		incoming.add("parents", single(worldId));
		incoming.add("appProperties", tag("incoming"));
		String newId = string(drive.uploadNewFile(incoming, zip, bytesSent), "id");
		if (newId == null) throw new IOException("Drive didn't say where the upload went");

		drive.patchJson(API + "/files/" + newId + "?fields=id", currentFile(next));
		moveOldCurrentSavesToBackups(worldId, newId, string(old, "id"), previous);
		return new Backup(string(old, "id"), previous);
	}

	/**
	 * One resumable upload straight into {@code backups/}, tagged {@code t=backup} with its label.
	 * Drive creates the file only when the upload completes, so a failed one leaves nothing behind.
	 */
	@Override
	public Backup uploadBackup(String worldId, Path zip, BackupLabel label, LongConsumer bytesSent) throws IOException {
		JsonObject file = new JsonObject();
		file.addProperty("name", backupFileName(label));
		file.add("parents", single(backupsFolder(worldId)));
		file.add("appProperties", backupProperties(label));
		String id = string(drive.uploadNewFile(file, zip, bytesSent), "id");
		if (id == null) throw new IOException("Drive didn't say where the upload went");
		return new Backup(id, label);
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

	/**
	 * No re-upload: one PATCH moves the chosen Backup into the world folder as {@code current.zip},
	 * tagged {@code t=current} with the new Revision, which makes it the Current Save. Then the old
	 * Current Save moves into {@code backups/}.
	 */
	@Override
	public Backup restoreBackup(String worldId, String backupId, WorldMetadata next, BackupLabel previous) throws IOException {
		JsonObject old = currentSave(worldId).orElseThrow(() -> new IOException("World " + worldId + " has no Current Save to replace"));
		String backupsId = backupsFolder(worldId);
		drive.patchJson(API + "/files/" + backupId + "?addParents=" + enc(worldId) + "&removeParents=" + enc(backupsId) + "&fields=id", currentFile(next));
		moveOldCurrentSavesToBackups(worldId, backupId, string(old, "id"), previous);
		return new Backup(string(old, "id"), previous);
	}

	/**
	 * Moves every {@code t=current} file except {@code currentId} into {@code backups/}: the one that
	 * was replaced ({@code replacedId}, labelled {@code replaced}), and any left over from an earlier
	 * clean-up that failed (labelled by their own Revision). Best effort: the new Current Save is in
	 * place already, so a failure only leaves an old file beside it until the next upload.
	 */
	private void moveOldCurrentSavesToBackups(String worldId, String currentId, String replacedId, BackupLabel replaced) {
		try {
			for (JsonObject file : currentSaveFiles(worldId)) {
				String id = string(file, "id");
				if (id.equals(currentId)) continue;
				BackupLabel label = id.equals(replacedId) ? replaced
						: revisionOf(file).map(metadata -> new BackupLabel(metadata.revision(), metadata.savedAt(), Optional.empty())).orElse(replaced);
				moveToBackups(worldId, id, label);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't move the old Current Save of " + worldId + " to Backups; will retry after the next upload", e);
		}
	}

	/** Moves a file from the world folder into {@code backups/}, renamed and tagged as a Backup labelled {@code label}. */
	private void moveToBackups(String worldId, String fileId, BackupLabel label) throws IOException {
		JsonObject backup = new JsonObject();
		backup.addProperty("name", backupFileName(label));
		backup.add("appProperties", backupProperties(label));
		drive.patchJson(API + "/files/" + fileId + "?addParents=" + enc(backupsFolder(worldId)) + "&removeParents=" + enc(worldId) + "&fields=id", backup);
	}

	/** {@code backup-r<Revision>-<savedAt>[-unmerged].zip}, with {@code :} made file-name safe. */
	private static String backupFileName(BackupLabel label) {
		return "backup-r" + label.revision() + "-" + label.savedAt().toString().replace(':', '-')
				+ (label.unmerged().isPresent() ? "-unmerged" : "") + ".zip";
	}

	/** The PATCH body that makes a file the Current Save carrying {@code metadata}. */
	private static JsonObject currentFile(WorldMetadata metadata) {
		JsonObject current = new JsonObject();
		current.addProperty("name", "current.zip");
		current.add("appProperties", currentProperties(metadata));
		return current;
	}

	/**
	 * The Current Save's tags: {@code t=current}, {@code r} Revision, {@code s} savedAt. The Unmerged
	 * Backup keys are set to null so Drive removes them from a file that was a Backup.
	 */
	private static JsonObject currentProperties(WorldMetadata metadata) {
		JsonObject properties = tag("current");
		properties.addProperty("r", Integer.toString(metadata.revision()));
		properties.addProperty("s", metadata.savedAt().toString());
		for (String key : List.of("uh", "un", "us")) properties.add(key, JsonNull.INSTANCE);
		return properties;
	}

	@Override
	public void deleteBackup(String worldId, String backupId) throws IOException {
		drive.delete(backupId);
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

	@Override
	public void downloadCurrentSave(String worldId, Path zip, BytesProgress progress) throws IOException {
		String fileId = currentSave(worldId).map(file -> string(file, "id"))
				.orElseThrow(() -> new IOException("World " + worldId + " has no Current Save"));
		drive.downloadLargeFile(fileId, zip, progress);
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
				body = new String(drive.download(string(file, "id")), StandardCharsets.UTF_8);
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
		Optional<String> existing = lockFile(worldId, lock.hostId());
		if (existing.isPresent()) {
			updateLockFile(existing.get(), lock);
		} else {
			JsonObject properties = tag("lock");
			properties.addProperty("h", lock.hostId());
			createSmallFile("lock-" + lock.hostId() + ".json", worldId, properties, "application/json", lockJson(lock));
		}
	}

	/** Rewrites the Lock file's content by its file id, so a file deleted meanwhile is never re-created. */
	@Override
	public boolean refreshLock(String worldId, Lock lock) throws IOException {
		Optional<String> existing = lockFile(worldId, lock.hostId());
		if (existing.isEmpty()) return false;
		try {
			updateLockFile(existing.get(), lock);
			return true;
		} catch (FileNotFoundException e) {
			return false; // Deleted since the search: a friend Took Over.
		}
	}

	private void updateLockFile(String fileId, Lock lock) throws IOException {
		drive.replaceContent(fileId, "application/json", lockJson(lock));
	}

	private static byte[] lockJson(Lock lock) {
		JsonObject json = new JsonObject();
		json.addProperty("hostId", lock.hostId());
		json.addProperty("hostName", lock.hostName());
		json.addProperty("startedAt", lock.startedAt().toString());
		json.addProperty("lastHeartbeat", lock.lastHeartbeat().toString());
		return json.toString().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public void deleteLock(String worldId, String hostId) throws IOException {
		Optional<String> file = lockFile(worldId, hostId);
		if (file.isEmpty()) return;
		try {
			drive.delete(file.get());
		} catch (FileNotFoundException e) {
			// Already gone: two players Took Over the same Stale Lock at once.
		}
	}

	private Optional<String> lockFile(String worldId, String hostId) throws IOException {
		List<JsonObject> found = search("'" + worldId + "' in parents and " + tagged("lock")
				+ " and appProperties has { key='h' and value='" + hostId.replace("\\", "\\\\").replace("'", "\\'") + "' }");
		return found.isEmpty() ? Optional.empty() : Optional.of(string(found.getFirst(), "id"));
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
			JsonObject page = drive.getJson(url);
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
		return drive.postJson(API + "/files?fields=id,name", metadata);
	}

	private void createSmallFile(String name, String parentId, JsonObject appProperties, String contentType, byte[] content) throws IOException {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("name", name);
		metadata.add("parents", single(parentId));
		metadata.add("appProperties", appProperties);
		drive.createSmallFile(metadata, contentType, content);
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
