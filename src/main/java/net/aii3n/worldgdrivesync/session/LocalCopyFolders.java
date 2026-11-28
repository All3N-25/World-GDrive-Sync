package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The folders of the Local Copy directory: one per Shared World, named by its id, and the temporary
 * {@code .wgds-*} folders and zips beside them. Makes the temporary ones, swaps a new Local Copy in
 * for an old one, and tidies up what a crash or a failed delete left behind. Knows nothing of the
 * cloud store.
 */
final class LocalCopyFolders {
	private static final String STAGING_PREFIX = ".wgds-";
	/** A folder holding the Local Copy a swap moved aside, in a folder named by its world's id. */
	private static final String ASIDE_PREFIX = STAGING_PREFIX + "aside-";
	/** A moved-aside copy on its way to being deleted. */
	private static final String TRASH_PREFIX = STAGING_PREFIX + "trash-";
	/** Where swaps used to move the old copy, inside a {@code .wgds-*} folder: its world isn't known. */
	private static final String UNKNOWN_WORLDS_COPY = "old";
	private static final System.Logger LOGGER = System.getLogger(LocalCopyFolders.class.getName());

	private final Path directory;
	private final Mover mover;

	/** Renames a folder to a path where nothing exists. */
	@FunctionalInterface
	interface Mover {
		void move(Path from, Path to) throws IOException;
	}

	/**
	 * @param directory the Local Copy directory ({@code sharedsaves/}, ADR-0002)
	 * @param mover     moves folders while swapping and tidying up, so a test can make a move fail
	 */
	LocalCopyFolders(Path directory, Mover mover) {
		this.directory = directory;
		this.mover = mover;
	}

	/** Where the Local Copy of a Shared World is, if there is one. */
	Path of(String worldId) {
		return directory.resolve(worldId);
	}

	/** The ids of the worlds with a folder here, whether or not it holds a Local Copy. */
	List<String> worldIds() throws IOException {
		if (!Files.isDirectory(directory)) return List.of();
		try (Stream<Path> folders = Files.list(directory)) {
			return folders.map(folder -> folder.getFileName().toString())
					.filter(name -> !name.startsWith(STAGING_PREFIX))
					.sorted()
					.toList();
		}
	}

	/** A new, empty temporary folder. */
	Path newStagingFolder() throws IOException {
		Files.createDirectories(directory);
		return Files.createTempDirectory(directory, STAGING_PREFIX);
	}

	/** A new, empty temporary zip. */
	Path newStagingZip() throws IOException {
		Files.createDirectories(directory);
		return Files.createTempFile(directory, STAGING_PREFIX, ".zip");
	}

	/**
	 * Tidies up what a crash or a failed delete left in the Local Copy directory: see
	 * {@link SessionCoordinator#recoverLocalCopiesAndDeleteLeftovers}.
	 *
	 * @throws IOException if the Local Copy directory can't be listed
	 */
	void recoverAndDeleteLeftovers() throws IOException {
		if (!Files.isDirectory(directory)) return;
		List<Path> leftovers;
		try (Stream<Path> entries = Files.list(directory)) {
			leftovers = entries.filter(entry -> entry.getFileName().toString().startsWith(STAGING_PREFIX)).toList();
		}
		Map<String, List<MovedAside>> movedAside = new HashMap<>();
		for (Path leftover : leftovers) {
			String name = leftover.getFileName().toString();
			if (name.startsWith(ASIDE_PREFIX) && Files.isDirectory(leftover)) {
				findMovedAside(leftover, movedAside);
			} else if (name.startsWith(TRASH_PREFIX) || !Files.isDirectory(leftover.resolve(UNKNOWN_WORLDS_COPY))) {
				deleteLeftover(leftover);
			}
		}
		movedAside.forEach(this::restoreOrDiscard);
	}

	/** An old Local Copy a swap moved aside, with the Revision its record says it is. */
	private record MovedAside(Path folder, int revision) {
	}

	/**
	 * Adds the copies in a move-aside folder that have a readable record to {@code found}, by world
	 * id. Deletes the folder if there is no copy in it at all.
	 */
	private static void findMovedAside(Path aside, Map<String, List<MovedAside>> found) {
		List<Path> copies;
		try (Stream<Path> entries = Files.list(aside)) {
			copies = entries.filter(Files::isDirectory).toList();
		} catch (IOException | UncheckedIOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't look for an old Local Copy in " + aside, e);
			return;
		}
		if (copies.isEmpty()) deleteLeftover(aside);
		for (Path copy : copies) {
			Optional<LocalCopyRecord> record = LocalCopyRecord.read(copy);
			if (record.isEmpty()) {
				LOGGER.log(System.Logger.Level.WARNING, "Keeping " + copy + ": it has no readable record");
				continue;
			}
			found.computeIfAbsent(copy.getFileName().toString(), worldId -> new ArrayList<>())
					.add(new MovedAside(copy, record.get().revision()));
		}
	}

	/** Settles the moved-aside copies of one world. */
	private void restoreOrDiscard(String worldId, List<MovedAside> copies) {
		Path folder = of(worldId);
		List<MovedAside> spare = new ArrayList<>(copies);
		if (Files.notExists(folder, LinkOption.NOFOLLOW_LINKS)) {
			MovedAside newest = Collections.max(copies, Comparator.comparingInt(MovedAside::revision));
			try {
				synchronized (LocalCopyRecord.class) { // not while a listing reads the record
					mover.move(newest.folder(), folder);
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.log(System.Logger.Level.WARNING, "Couldn't put the Local Copy back; it is in " + newest.folder(), e);
				return;
			}
			LOGGER.log(System.Logger.Level.INFO, "Put the old Local Copy of " + worldId + " back");
			deleteIfEmpty(newest.folder().getParent());
			spare.remove(newest);
		}
		if (LocalCopyRecord.read(folder).isEmpty()) {
			LOGGER.log(System.Logger.Level.WARNING, "Keeping the old Local Copy of " + worldId + ": " + folder + " isn't a Local Copy");
			return;
		}
		spare.forEach(copy -> discard(copy.folder()));
	}

	/**
	 * Puts {@code newCopy} where the Local Copy at {@code folder} is. The old one is moved aside
	 * first, put back if the new one can't be moved in, and deleted only once the new one is in
	 * place, so a failure never loses it. If it is left behind, a later launch puts it back or
	 * deletes it (see {@link #recoverAndDeleteLeftovers}), so it must hold no unsaved progress by
	 * now.
	 */
	void swapIn(Path newCopy, Path folder) throws IOException {
		Path aside = Files.createTempDirectory(directory, ASIDE_PREFIX);
		// Named by its world, so that it can still be put back after a crash.
		Path old = aside.resolve(folder.getFileName());
		boolean swapped = false;
		try {
			synchronized (LocalCopyRecord.class) { // not while a listing reads the old record
				boolean replacing = Files.exists(folder);
				if (replacing) mover.move(folder, old);
				try {
					mover.move(newCopy, folder);
				} catch (IOException | RuntimeException e) {
					if (replacing) putBack(old, folder, e);
					throw e;
				}
				swapped = true;
			}
		} finally {
			// After a failed swap, a copy still in there is the only old one.
			if (swapped && Files.exists(old)) discard(old);
			else if (Files.notExists(old)) deleteLeftover(aside);
		}
	}

	private void putBack(Path old, Path folder, Exception failure) {
		try {
			mover.move(old, folder);
		} catch (IOException e) {
			LOGGER.log(System.Logger.Level.ERROR, "Couldn't put the Local Copy back; it is in " + old, e);
			failure.addSuppressed(e);
		}
	}

	/**
	 * Deletes a moved-aside copy that is no longer wanted. It is renamed first, so that one only
	 * partly deleted is never put back, and the next launch finishes deleting it. Best effort.
	 */
	private void discard(Path copy) {
		Path trash = directory.resolve(TRASH_PREFIX + UUID.randomUUID());
		try {
			mover.move(copy, trash);
		} catch (IOException | RuntimeException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete " + copy, e);
			return;
		}
		deleteLeftover(trash);
		deleteIfEmpty(copy.getParent());
	}

	/** Deletes a folder if there is nothing in it. Best effort, as {@link #deleteLeftover}. */
	private static void deleteIfEmpty(Path folder) {
		try {
			Files.delete(folder);
		} catch (DirectoryNotEmptyException e) {
			// Kept: the next launch looks at it again.
		} catch (IOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete " + folder, e);
		}
	}

	/**
	 * Deletes a {@code .wgds-*} folder or zip, if it is there. Best effort: nothing depends on it, so a
	 * failure only leaves it for the next launch.
	 */
	static void deleteLeftover(Path leftover) {
		try {
			deleteRecursively(leftover);
		} catch (IOException | UncheckedIOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete " + leftover, e);
		}
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
