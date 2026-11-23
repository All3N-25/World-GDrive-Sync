package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Packs a world folder into a zip whose entries are relative to the world folder. */
final class WorldZip {
	/**
	 * Top-level files never packed or copied: Minecraft's {@code session.lock} (held open while the
	 * world runs, and unreadable on Windows), and the Local Copy's own record.
	 */
	static final Set<String> SKIPPED = Set.of("session.lock", LocalCopyRecord.FILE_NAME);

	/** Copies a world folder, leaving out the {@link #SKIPPED} files. */
	static void copy(Path from, Path to) throws IOException {
		for (Path file : worldFiles(from)) {
			Path target = to.resolve(from.relativize(file).toString());
			Files.createDirectories(target.getParent());
			Files.copy(file, target);
		}
	}

	private static List<Path> worldFiles(Path worldFolder) throws IOException {
		try (Stream<Path> walk = Files.walk(worldFolder)) {
			return walk.filter(Files::isRegularFile)
					.filter(f -> !(f.getParent().equals(worldFolder) && SKIPPED.contains(f.getFileName().toString())))
					.sorted()
					.toList();
		}
	}

	private WorldZip() {
	}

	static void zip(Path worldFolder, Path zipFile, ProgressListener progress) throws IOException {
		List<Path> files = worldFiles(worldFolder);
		try (OutputStream out = Files.newOutputStream(zipFile); ZipOutputStream zip = new ZipOutputStream(out)) {
			int done = 0;
			for (Path file : files) {
				zip.putNextEntry(new ZipEntry(entryName(worldFolder, file)));
				Files.copy(file, zip);
				zip.closeEntry();
				progress.onProgress(ProgressListener.Step.ZIPPING, ++done, files.size());
			}
		}
	}

	/** Unpacks a zip made by {@link #zip} into {@code worldFolder}, which must be empty or not exist yet. */
	static void unzip(Path zipFile, Path worldFolder, ProgressListener progress) throws IOException {
		Path root = worldFolder.toAbsolutePath().normalize();
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			int total = zip.size();
			int done = 0;
			for (ZipEntry entry : Collections.list(zip.entries())) {
				Path target = root.resolve(entry.getName()).normalize();
				if (!target.startsWith(root) || target.equals(root)) throw new IOException("Bad entry in world zip: " + entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					try (InputStream in = zip.getInputStream(entry)) {
						Files.copy(in, target);
					}
				}
				progress.onProgress(ProgressListener.Step.UNZIPPING, ++done, total);
			}
		}
	}

	private static String entryName(Path root, Path file) {
		StringBuilder name = new StringBuilder();
		for (Path part : root.relativize(file)) {
			if (!name.isEmpty()) name.append('/');
			name.append(part);
		}
		return name.toString();
	}
}
