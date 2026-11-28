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
import java.util.OptionalLong;
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
	private boolean storeNextLockTwice;
	private StorageQuota quota = new StorageQuota(OptionalLong.empty(), 0);
	private boolean failQuotaReads;
	private boolean refuseUploadsAsFull;
	private int worldsCreated;
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
		REPLACE,
		/** Reading a world's Locks. */
		LOCK_READ
	}

	/** Something a test runs in the middle of a cloud call, such as another player's Play. */
	@FunctionalInterface
	interface Hook {
		void run() throws IOException;
	}
	private boolean failBackupDeletes;
	private boolean failWorldDeletes;
	private Runnable afterChunkSent = () -> {
	};

	private Runnable afterChunkReceived = () -> {
	};

	private static final class Folder {
		final String name;
		WorldMetadata metadata;
		byte[] currentSave;
		/** Oldest first. Like Drive's Lock files, one player may have more than one. */
		final List<Lock> locks = new ArrayList<>();
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
		folder.history.add(new CurrentSave(change, folder.metadata.revision(), entries(folder.currentSave), folder.locks.stream().map(Lock::hostId).distinct().toList()));
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
		worldsCreated++;
		worlds.put(id, new Folder(name));
		return new CloudWorld(id, name);
	}

	@Override
	public synchronized void deleteWorld(String worldId) throws IOException {
		if (failWorldDeletes) throw new IOException("Couldn't delete the folder (simulated)");
		folder(worldId);
		worlds.remove(worldId);
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
	public Backup replaceCurrentSave(String worldId, Path zip, WorldMetadata next, BackupLabel previous, LongConsumer bytesSent) throws IOException {
		// Outside the monitor, so a hook that blocks (a slow upload) doesn't hold up other calls.
		runHook(Call.REPLACE);
		return storeReplacement(worldId, zip, next, previous, bytesSent);
	}

	private synchronized Backup storeReplacement(String worldId, Path zip, WorldMetadata next, BackupLabel previous, LongConsumer bytesSent) throws IOException {
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
		if (refuseUploadsAsFull) throw new NotEnoughSpaceException(bytes.length, null);
		for (int sent = 0; sent < bytes.length; ) {
			if (failUploads) throw new IOException("Network down (simulated)");
			sent = Math.min(bytes.length, sent + CHUNK);
			bytesSent.accept(sent);
			afterChunkSent.run();
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
			afterChunkReceived.run();
		}
		Files.write(zip, bytes);
	}

	@Override
	public synchronized List<Lock> readLocks(String worldId) throws IOException {
		runHook(Call.LOCK_READ);
		if (failLockReads) throw new IOException("Network down (simulated)");
		return List.copyOf(folder(worldId).locks);
	}

	@Override
	public synchronized void writeLock(String worldId, Lock lock) throws IOException {
		runHook(Call.LOCK_WRITE);
		if (failLockWrites) throw new IOException("Network down (simulated)");
		List<Lock> locks = folder(worldId).locks;
		if (replaceOldest(locks, lock)) return;
		locks.add(lock);
		if (storeNextLockTwice) {
			storeNextLockTwice = false;
			locks.add(lock);
		}
	}

	@Override
	public synchronized boolean refreshLock(String worldId, Lock lock) throws IOException {
		runHook(Call.LOCK_WRITE);
		if (failLockWrites) throw new IOException("Network down (simulated)");
		return replaceOldest(folder(worldId).locks, lock);
	}

	/** Puts {@code lock} in the place of its player's oldest Lock; false if they have none. */
	private static boolean replaceOldest(List<Lock> locks, Lock lock) {
		for (int i = 0; i < locks.size(); i++) {
			if (locks.get(i).hostId().equals(lock.hostId())) {
				locks.set(i, lock);
				return true;
			}
		}
		return false;
	}

	private void runHook(Call call) throws IOException {
		Hook hook;
		synchronized (this) {
			hook = hooks.remove(call);
		}
		if (hook != null) hook.run();
	}

	@Override
	public synchronized boolean deleteLock(String worldId, String hostId) throws IOException {
		if (failLockDeletes) throw new IOException("Network down (simulated)");
		List<Lock> locks = folder(worldId).locks;
		// One copy per call, the least CloudStore allows.
		Optional<Lock> oldest = locks.stream().filter(lock -> lock.hostId().equals(hostId)).findFirst();
		oldest.ifPresent(locks::remove);
		return oldest.isPresent();
	}

	@Override
	public synchronized StorageQuota storageQuota() throws IOException {
		if (failQuotaReads) throw new IOException("Network down (simulated)");
		return quota;
	}

	@Override
	public synchronized long currentSaveSize(String worldId) throws IOException {
		byte[] zip = folder(worldId).currentSave;
		return zip == null ? 0 : zip.length;
	}

	// --- Test controls and inspection ---

	/** The account has a storage limit with this much of it left, however much is stored here. */
	synchronized void setFreeSpace(long bytes) {
		quota = new StorageQuota(OptionalLong.of(bytes), 0);
	}

	/** From now on reading the storage quota fails, like a dropped connection. */
	synchronized void failQuotaReads() {
		failQuotaReads = true;
	}

	/** From now on every upload is refused for want of room, as by a full Drive. */
	synchronized void refuseUploadsAsFull() {
		refuseUploadsAsFull = true;
	}

	/** How many world folders were ever made, deleted ones included. */
	synchronized int worldsCreated() {
		return worldsCreated;
	}

	/** From now on every upload fails partway, like a dropped connection. */
	void failUploads() {
		failUploads = true;
	}

	/** Runs {@code watcher} each time an upload has reported another chunk as sent. */
	synchronized void afterEachChunkSent(Runnable watcher) {
		afterChunkSent = watcher;
	}

	/** Runs {@code watcher} each time a download has reported another chunk as received. */
	synchronized void afterEachChunkReceived(Runnable watcher) {
		afterChunkReceived = watcher;
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

	/**
	 * The next Lock to be created is stored twice, as when the answer to Drive's create request is
	 * lost and the request is sent again.
	 */
	synchronized void storeNextLockTwice() {
		storeNextLockTwice = true;
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
		return worlds.get(worldId).locks.stream().map(Lock::hostName).toList();
	}

	/** From now on deleting a Backup fails. */
	void failBackupDeletes() {
		failBackupDeletes = true;
	}

	/** From now on deleting a world folder fails. */
	void failWorldDeletes() {
		failWorldDeletes = true;
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
