package net.aii3n.worldgdrivesync.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	private boolean failBackupDeletes;

	private static final class Folder {
		final String name;
		WorldMetadata metadata;
		byte[] currentSave;
		final Map<String, Lock> locks = new LinkedHashMap<>();
		final Map<String, StoredBackup> backups = new LinkedHashMap<>();

		Folder(String name) {
			this.name = name;
		}
	}

	private record StoredBackup(Backup backup, byte[] zip) {
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
		return Optional.ofNullable(folder(worldId).metadata);
	}

	@Override
	public synchronized void writeMetadata(String worldId, WorldMetadata metadata) throws IOException {
		folder(worldId).metadata = metadata;
	}

	@Override
	public synchronized void uploadCurrentSave(String worldId, Path zip, LongConsumer bytesSent) throws IOException {
		Folder folder = folder(worldId);
		folder.currentSave = send(zip, bytesSent);
	}

	@Override
	public synchronized Backup replaceCurrentSave(String worldId, Path zip, BackupLabel previous, LongConsumer bytesSent) throws IOException {
		Folder folder = folder(worldId);
		if (folder.currentSave == null) throw new IOException("No Current Save in " + worldId + " to replace");
		byte[] bytes = send(zip, bytesSent);
		Backup backup = new Backup("backup-" + nextId++, previous);
		folder.backups.put(backup.id(), new StoredBackup(backup, folder.currentSave));
		folder.currentSave = bytes;
		return backup;
	}

	@Override
	public synchronized List<Backup> listBackups(String worldId) throws IOException {
		return folder(worldId).backups.values().stream().map(StoredBackup::backup).toList();
	}

	@Override
	public synchronized Backup restoreBackup(String worldId, String backupId, BackupLabel previous) throws IOException {
		Folder folder = folder(worldId);
		if (folder.currentSave == null) throw new IOException("No Current Save in " + worldId + " to replace");
		StoredBackup chosen = folder.backups.remove(backupId);
		if (chosen == null) throw new IOException("No such Backup: " + backupId);
		Backup backup = new Backup("backup-" + nextId++, previous);
		folder.backups.put(backup.id(), new StoredBackup(backup, folder.currentSave));
		folder.currentSave = chosen.zip();
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
		return List.copyOf(folder(worldId).locks.values());
	}

	@Override
	public synchronized void writeLock(String worldId, Lock lock) throws IOException {
		folder(worldId).locks.put(lock.hostId(), lock);
	}

	@Override
	public synchronized void deleteLock(String worldId, String hostId) throws IOException {
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

	/** From now on every download fails partway, like a dropped connection. */
	void failDownloads() {
		failDownloads = true;
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
