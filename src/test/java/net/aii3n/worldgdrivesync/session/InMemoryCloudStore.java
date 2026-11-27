package net.aii3n.worldgdrivesync.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A cloud store held in memory. Several coordinators can share one instance to act out several
 * players on the same account. Tests also read it directly to see what is "in the cloud".
 */
final class InMemoryCloudStore implements CloudStore {
	private static final int CHUNK = 4;

	private final Map<String, Folder> worlds = new LinkedHashMap<>();
	private int nextId = 1;
	private boolean failUploads;
	private boolean failDownloads;
	private boolean failMetadataReads;
	private boolean failLockReads;
	private boolean failLockDeletes;
	private boolean failLockWrites;
	private boolean dropConnectionAfterNextReplace;
	private final Map<Call, Hook> hooks = new EnumMap<>(Call.class);

	/** Cloud calls a test can step into with {@link #beforeNext}. */
	enum Call {
		/** Writing or refreshing a Lock. */
		LOCK_WRITE,
		/** Restoring a Backup. */
		RESTORE,
		/** Downloading the Current Save. */
		DOWNLOAD,
		/** Replacing the Current Save (every upload but the first). */
		REPLACE
	}

	/** Something a test runs in the middle of a cloud call, such as another player's Play. */
	@FunctionalInterface
	interface Hook {
		void run() throws IOException;
	}
	private boolean failBackupDeletes;

	private static final class Folder {
		final String name;
		WorldMetadata metadata;
		byte[] currentSave;
		final Map<String, Lock> locks = new LinkedHashMap<>();
		final Map<String, StoredBackup> backups = new LinkedHashMap<>();
		final List<CurrentSave> history = new ArrayList<>();

		Folder(String name) {
			this.name = name;
		}
	}

	private record StoredBackup(Backup backup, byte[] zip) {
	}

	/** How a zip became the Current Save. */
	enum Change {
		/** The first upload, when the world was shared. */
		SHARED,
		/** An upload replacing the previous Current Save. */
		REPLACED,
		/** A Backup restored. */
		RESTORED
	}

	/**
	 * One Current Save the world has had, as {@link #currentSaveHistory} lists it.
	 *
	 * @param entries     the zip's files and their contents, by path inside the zip
	 * @param lockHolderIds the {@link Player#id()}s of the players holding a Lock at the moment it became current
	 */
	record CurrentSave(Change change, int revision, Map<String, String> entries, List<String> lockHolderIds) {
	}

	private static void recordCurrentSave(Folder folder, Change change) {
		folder.history.add(new CurrentSave(change, folder.metadata.revision(), entries(folder.currentSave), List.copyOf(folder.locks.keySet())));
	}

	// --- CloudStore ---

	@Override
	public synchronized List<CloudWorld> listWorlds() {
		List<CloudWorld> result = new ArrayList<>();
		worlds.forEach((id, folder) -> result.add(new CloudWorld(id, folder.name)));
		return result;
	}

	@Override
	public synchronized CloudWorld createWorld(String name) {
		String id = "world-" + nextId++;
		worlds.put(id, new Folder(name));
		return new CloudWorld(id, name);
	}

	@Override
	public synchronized Optional<WorldMetadata> readMetadata(String worldId) throws IOException {
		if (failMetadataReads) throw new IOException("Network down (simulated)");
		return Optional.ofNullable(folder(worldId).metadata);
	}

	@Override
	public synchronized void uploadCurrentSave(String worldId, Path zip, WorldMetadata metadata, LongConsumer bytesSent) throws IOException {
		Folder folder = folder(worldId);
		folder.currentSave = send(zip, bytesSent);
		folder.metadata = metadata;
		recordCurrentSave(folder, Change.SHARED);
	}

	@Override
	public synchronized Backup replaceCurrentSave(String worldId, Path zip, WorldMetadata next, BackupLabel previous, LongConsumer bytesSent) throws IOException {
		runHook(Call.REPLACE);
		Folder folder = folder(worldId);
		if (folder.currentSave == null) throw new IOException("No Current Save in " + worldId + " to replace");
		byte[] bytes = send(zip, bytesSent);
		Backup backup = new Backup("backup-" + nextId++, previous);
		folder.backups.put(backup.id(), new StoredBackup(backup, folder.currentSave));
		folder.currentSave = bytes;
		folder.metadata = next;
		recordCurrentSave(folder, Change.REPLACED);
		if (dropConnectionAfterNextReplace) {
			dropConnectionAfterNextReplace = false;
			throw new IOException("Connection dropped before the answer arrived (simulated)");
		}
		return backup;
	}

	@Override
	public synchronized Backup uploadBackup(String worldId, Path zip, BackupLabel label, LongConsumer bytesSent) throws IOException {
		Folder folder = folder(worldId);
		byte[] bytes = send(zip, bytesSent);
		Backup backup = new Backup("backup-" + nextId++, label);
		folder.backups.put(backup.id(), new StoredBackup(backup, bytes));
		return backup;
	}

	@Override
	public synchronized List<Backup> listBackups(String worldId) throws IOException {
		return folder(worldId).backups.values().stream().map(StoredBackup::backup).toList();
	}

	@Override
	public synchronized Backup restoreBackup(String worldId, String backupId, WorldMetadata next, BackupLabel previous) throws IOException {
		runHook(Call.RESTORE);
		Folder folder = folder(worldId);
		if (folder.currentSave == null) throw new IOException("No Current Save in " + worldId + " to replace");
		StoredBackup chosen = folder.backups.remove(backupId);
		if (chosen == null) throw new IOException("No such Backup: " + backupId);
		Backup backup = new Backup("backup-" + nextId++, previous);
		folder.backups.put(backup.id(), new StoredBackup(backup, folder.currentSave));
		folder.currentSave = chosen.zip();
		folder.metadata = next;
		recordCurrentSave(folder, Change.RESTORED);
		return backup;
	}

	@Override
	public synchronized void deleteBackup(String worldId, String backupId) throws IOException {
		if (failBackupDeletes) throw new IOException("Network down (simulated)");
		if (folder(worldId).backups.remove(backupId) == null) throw new IOException("No such Backup: " + backupId);
	}

	/** "Sends" a zip in small chunks, failing partway when uploads are set to fail. */
	private byte[] send(Path zip, LongConsumer bytesSent) throws IOException {
		byte[] bytes = Files.readAllBytes(zip);
		for (int sent = 0; sent < bytes.length; ) {
			if (failUploads) throw new IOException("Network down (simulated)");
			sent = Math.min(bytes.length, sent + CHUNK);
			bytesSent.accept(sent);
		}
		return bytes;
	}

	@Override
	public synchronized void downloadCurrentSave(String worldId, Path zip, BytesProgress progress) throws IOException {
		runHook(Call.DOWNLOAD);
		byte[] bytes = folder(worldId).currentSave;
		if (bytes == null) throw new IOException("No Current Save in " + worldId);
		for (int received = 0; received < bytes.length; ) {
			received = Math.min(bytes.length, received + CHUNK);
			if (failDownloads) throw new IOException("Network down (simulated)");
			progress.onProgress(received, bytes.length);
		}
		Files.write(zip, bytes);
	}

	@Override
	public synchronized List<Lock> readLocks(String worldId) throws IOException {
		if (failLockReads) throw new IOException("Network down (simulated)");
		return List.copyOf(folder(worldId).locks.values());
	}

	@Override
	public synchronized void writeLock(String worldId, Lock lock) throws IOException {
		runHook(Call.LOCK_WRITE);
		if (failLockWrites) throw new IOException("Network down (simulated)");
		folder(worldId).locks.put(lock.hostId(), lock);
	}

	@Override
	public synchronized boolean refreshLock(String worldId, Lock lock) throws IOException {
		runHook(Call.LOCK_WRITE);
		if (failLockWrites) throw new IOException("Network down (simulated)");
		Map<String, Lock> locks = folder(worldId).locks;
		if (!locks.containsKey(lock.hostId())) return false;
		locks.put(lock.hostId(), lock);
		return true;
	}

	private void runHook(Call call) throws IOException {
		Hook hook = hooks.remove(call);
		if (hook != null) hook.run();
	}

	@Override
	public synchronized void deleteLock(String worldId, String hostId) throws IOException {
		if (failLockDeletes) throw new IOException("Network down (simulated)");
		folder(worldId).locks.remove(hostId);
	}

	// --- Test controls and inspection ---

	/** From now on every upload fails partway, like a dropped connection. */
	void failUploads() {
		failUploads = true;
	}

	/** The connection is back: uploads succeed again after {@link #failUploads}. */
	void uploadsWork() {
		failUploads = false;
	}

	/**
	 * The next Current Save replacement is stored completely, but then the call fails, as if the
	 * connection dropped before the answer arrived.
	 */
	void dropConnectionAfterNextReplace() {
		dropConnectionAfterNextReplace = true;
	}

	/** From now on reading a world's metadata fails, like a dropped connection. */
	void failMetadataReads() {
		failMetadataReads = true;
	}

	/** The connection is back: metadata reads succeed again after {@link #failMetadataReads}. */
	void metadataReadsWork() {
		failMetadataReads = false;
	}

	/** From now on reading a world's Locks fails, like a dropped connection. */
	void failLockReads() {
		failLockReads = true;
	}

	/** The connection is back: Lock reads succeed again after {@link #failLockReads}. */
	void lockReadsWork() {
		failLockReads = false;
	}

	/** From now on deleting a Lock fails, like a dropped connection. */
	void failLockDeletes() {
		failLockDeletes = true;
	}

	/** The connection is back: Lock deletes succeed again after {@link #failLockDeletes}. */
	void lockDeletesWork() {
		failLockDeletes = false;
	}

	/** From now on writing or refreshing a Lock fails, like a dropped connection. */
	void failLockWrites() {
		failLockWrites = true;
	}

	/** The connection is back: Lock writes succeed again after {@link #failLockWrites}. */
	void lockWritesWork() {
		failLockWrites = false;
	}

	/** The connection is back: Backup deletes succeed again after {@link #failBackupDeletes}. */
	void backupDeletesWork() {
		failBackupDeletes = false;
	}

	/** From now on every download fails partway, like a dropped connection. */
	void failDownloads() {
		failDownloads = true;
	}

	/** The connection is back: downloads succeed again after {@link #failDownloads}. */
	void downloadsWork() {
		failDownloads = false;
	}

	/**
	 * Runs {@code hook} once, just before the next {@code call} does anything, as if another player's
	 * calls (or this game's Heartbeat thread) landed in the middle of this one.
	 */
	synchronized void beforeNext(Call call, Hook hook) {
		hooks.put(call, hook);
	}

	/** The names of the players holding a Lock on the world, oldest Lock first. */
	synchronized List<String> lockHolders(String worldId) {
		return worlds.get(worldId).locks.values().stream().map(Lock::hostName).toList();
	}

	/** From now on deleting a Backup fails. */
	void failBackupDeletes() {
		failBackupDeletes = true;
	}

	synchronized int worldCount() {
		return worlds.size();
	}

	synchronized String worldName(String worldId) {
		return worlds.get(worldId).name;
	}

	synchronized WorldMetadata metadata(String worldId) {
		return worlds.get(worldId).metadata;
	}

	/** Every Current Save the world has had, oldest first, the present one last. */
	synchronized List<CurrentSave> currentSaveHistory(String worldId) {
		return List.copyOf(worlds.get(worldId).history);
	}

	/** The Current Save zip's files and their contents, by path inside the zip. */
	synchronized Map<String, String> currentSaveEntries(String worldId) {
		return entries(worlds.get(worldId).currentSave);
	}

	/** A Backup zip's files and their contents, by path inside the zip. */
	synchronized Map<String, String> backupEntries(String worldId, String backupId) {
		return entries(worlds.get(worldId).backups.get(backupId).zip());
	}

	private static Map<String, String> entries(byte[] zip) {
		if (zip == null) return null;
		Map<String, String> entries = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (!entry.isDirectory()) entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return entries;
	}

	private Folder folder(String worldId) throws IOException {
		Folder folder = worlds.get(worldId);
		if (folder == null) throw new IOException("No such world folder: " + worldId);
		return folder;
	}
}
