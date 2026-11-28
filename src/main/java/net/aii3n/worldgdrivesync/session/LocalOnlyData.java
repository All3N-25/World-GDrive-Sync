package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Local-only Data (see {@code GLOSSARY.md}): never uploaded (see {@link WorldZip}), and kept when a
 * downloaded Current Save replaces the Local Copy. It is Distant Horizons' LOD database and the
 * journal files SQLite keeps beside it, at any depth in the world folder.
 */
final class LocalOnlyData {
	private static final String DISTANT_HORIZONS_DATABASE = "DistantHorizons.sqlite";
	private static final System.Logger LOGGER = System.getLogger(LocalOnlyData.class.getName());

	private LocalOnlyData() {
	}

	static boolean includes(Path file) {
		return file.getFileName().toString().startsWith(DISTANT_HORIZONS_DATABASE);
	}

	/**
	 * Puts the Local-only Data of the world folder {@code from} (which may not exist) into
	 * {@code to}, at the same relative paths. In each folder where {@code from} has some, it
	 * replaces whatever {@code to} has there, because a journal only fits its own database;
	 * elsewhere {@code to} is left as it is. {@code from} is not changed.
	 *
	 * <p>Never fails: the game rebuilds this data, so a folder that can't be carried over is logged
	 * and left without any.
	 */
	static void carryOver(Path from, Path to) {
		if (!Files.isDirectory(from)) return;
		Map<Path, List<Path>> byFolder;
		try {
			byFolder = filesIn(from, Integer.MAX_VALUE).stream().collect(Collectors.groupingBy(Path::getParent));
		} catch (IOException | UncheckedIOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't look for Local-only Data in " + from + "; it will be rebuilt", e);
			return;
		}
		byFolder.forEach((folder, files) -> {
			Path target = to.resolve(from.relativize(folder).toString());
			try {
				Files.createDirectories(target);
				deleteDirectlyIn(target);
				for (Path file : files) linkOrCopy(file, target.resolve(file.getFileName().toString()));
			} catch (IOException | UncheckedIOException e) {
				LOGGER.log(System.Logger.Level.WARNING, "Couldn't keep the Local-only Data in " + folder + "; it will be rebuilt", e);
				discard(target);
			}
		});
	}

	private static List<Path> filesIn(Path folder, int depth) throws IOException {
		try (Stream<Path> walk = Files.walk(folder, depth)) {
			return walk.filter(file -> Files.isRegularFile(file) && includes(file)).toList();
		}
	}

	/** A hard link takes no time or disk space, however big the file; not every file system has them. */
	private static void linkOrCopy(Path file, Path target) throws IOException {
		try {
			Files.createLink(target, file);
		} catch (IOException | UnsupportedOperationException e) {
			Files.copy(file, target);
		}
	}

	/** Removes a partly carried over folder's data, so no database is left without its journal. */
	private static void discard(Path folder) {
		if (!Files.isDirectory(folder)) return;
		try {
			deleteDirectlyIn(folder);
		} catch (IOException | UncheckedIOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't remove the partly kept Local-only Data in " + folder, e);
		}
	}

	/** Deletes the Local-only Data in {@code folder} itself, not in the folders inside it. */
	private static void deleteDirectlyIn(Path folder) throws IOException {
		for (Path file : filesIn(folder, 1)) Files.delete(file);
	}
}
