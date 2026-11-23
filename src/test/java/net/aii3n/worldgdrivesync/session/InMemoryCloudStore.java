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

	private static final class Folder {
		final String name;
		WorldMetadata metadata;
		byte[] currentSave;

		Folder(String name) {
			this.name = name;
		}
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
		byte[] bytes = Files.readAllBytes(zip);
		for (int sent = 0; sent < bytes.length; ) {
			if (failUploads) throw new IOException("Network down (simulated)");
			sent = Math.min(bytes.length, sent + CHUNK);
			bytesSent.accept(sent);
		}
		folder.currentSave = bytes;
	}

	// --- Test controls and inspection ---

	/** From now on every upload fails partway, like a dropped connection. */
	void failUploads() {
		failUploads = true;
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
		byte[] zip = worlds.get(worldId).currentSave;
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
