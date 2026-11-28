package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.io.AtomicFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Packs a world folder into a zip whose entries are relative to the world folder, and copies world folders. */
public final class WorldZip {
	/**
	 * File name endings (lower case) of formats that are compressed already: deflating them again
	 * costs time and hardly shrinks them.
	 */
	private static final Set<String> ALREADY_COMPRESSED = Set.of(
			".sqlite", ".png", ".gz", ".zip", ".jar", ".ogg", ".mp3", ".jpg", ".jpeg", ".webp");

	/**
	 * Copies a saved world folder for an upload: leaves out what no upload contains, which is the
	 * open world's {@code session.lock}, the Local Copy's own record and the Local-only Data.
	 */
	public static void copyForUpload(Path from, Path to) throws IOException {
		copy(from, filesToUpload(from), to);
	}

	/** Copies a world folder to become a Local Copy: as {@link #copyForUpload}, plus the Local-only Data. */
	static void copyForLocalCopy(Path from, Path to) throws IOException {
		copy(from, worldFiles(from), to);
	}

	private static void copy(Path from, List<Path> files, Path to) throws IOException {
		for (Path file : files) {
			Path target = to.resolve(from.relativize(file).toString());
			Files.createDirectories(target.getParent());
			Files.copy(file, target);
		}
	}

	private static List<Path> worldFiles(Path worldFolder) throws IOException {
		try (Stream<Path> walk = Files.walk(worldFolder)) {
			return walk.filter(Files::isRegularFile)
					.filter(f -> !(f.getParent().equals(worldFolder) && isSkippedAtTopLevel(f.getFileName().toString())))
					.sorted()
					.toList();
		}
	}

	/**
	 * Top-level files never packed or copied: Minecraft's {@code session.lock} (held open while the
	 * world runs, and unreadable on Windows), the Local Copy's own record, and any temp file left by a
	 * write of that record.
	 */
	private static boolean isSkippedAtTopLevel(String name) {
		return name.equals("session.lock") || name.equals(LocalCopyRecord.FILE_NAME)
				|| AtomicFile.isTempFileOf(name, LocalCopyRecord.FILE_NAME);
	}

	private static List<Path> filesToUpload(Path worldFolder) throws IOException {
		return worldFiles(worldFolder).stream().filter(file -> !LocalOnlyData.includes(file)).toList();
	}

	private WorldZip() {
	}

	/** Zips a world folder for an upload, leaving out the same files as {@link #copyForUpload}. */
	static void zip(Path worldFolder, Path zipFile, ProgressListener progress) throws IOException {
		List<Path> files = filesToUpload(worldFolder);
		try (OutputStream out = Files.newOutputStream(zipFile); ZipOutputStream zip = new ZipOutputStream(out)) {
			int done = 0;
			for (Path file : files) {
				zip.setLevel(compressionLevel(file));
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

	/**
	 * Every entry stays DEFLATED, even at no compression: a STORED entry needs its CRC before its
	 * data, which would mean reading the file twice.
	 */
	private static int compressionLevel(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return ALREADY_COMPRESSED.stream().anyMatch(name::endsWith) ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED;
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
