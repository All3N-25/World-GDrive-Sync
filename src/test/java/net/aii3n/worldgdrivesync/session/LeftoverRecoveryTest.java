package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What launching the game does with the {@code .wgds-*} folders and zips left in {@code sharedsaves/}. */
class LeftoverRecoveryTest {
	private static final Instant SHARED_AT = Instant.parse("2026-11-24T18:00:00Z");
	private static final Player ALICE = new Player("uuid-alice", "Alice");
	private static final Player BOB = new Player("uuid-bob", "Bob");
	private static final String CHUNKS = "region/r.0.0.mca";
	private static final String LODS = "data/DistantHorizons.sqlite";

	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;
	@TempDir
	Path bobsSharedsaves;

	InMemoryCloudStore cloud;
	TestClock clock;

	@BeforeEach
	void setUp() {
		cloud = new InMemoryCloudStore();
		clock = new TestClock(SHARED_AT);
	}

	@Test
	void theNextLaunchPutsBackALocalCopyStrandedByACrashMidSwap() throws Exception {
		SharedWorld shared = shareAWorld();
		LocalCopy before = coordinator().localCopy(shared.id()).orElseThrow();
		write(before.folder().resolve(LODS), "Alice's LODs");
		bobUploads(shared.id(), "chunks with Bob's farm");
		crashBetweenTheSwapsTwoMoves(shared.id());

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(Optional.of(before), coordinator().localCopy(shared.id()));
		assertEquals("chunks", Files.readString(before.folder().resolve(CHUNKS)));
		assertEquals("Alice's LODs", Files.readString(before.folder().resolve(LODS)));
	}

	@Test
	void ofSeveralStrandedCopiesOfAMissingWorldTheHighestRevisionIsPutBackAndTheRestDeleted() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		crashBetweenTheSwapsTwoMoves(shared.id()); // strands Revision 1
		coordinator().play(shared.id(), ProgressListener.NONE);
		coordinator().leaveWithoutPlaying(shared.id());
		bobUploads(shared.id(), "chunks at Revision 3");
		crashBetweenTheSwapsTwoMoves(shared.id()); // strands Revision 2

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		LocalCopy restored = coordinator().localCopy(shared.id()).orElseThrow();
		assertEquals(2, restored.revision());
		assertEquals("chunks at Revision 2", Files.readString(restored.folder().resolve(CHUNKS)));
		assertEquals(List.of(restored.folder()), inSharedsaves());
	}

	@Test
	void aMovedAsideCopyIsDeletedOnceALocalCopyOfItsWorldIsInPlace() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		crashBetweenTheSwapsTwoMoves(shared.id());
		coordinator().play(shared.id(), ProgressListener.NONE); // downloads Revision 2 again
		LocalCopy current = coordinator().localCopy(shared.id()).orElseThrow();

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(Optional.of(current), coordinator().localCopy(shared.id()));
		assertEquals(List.of(current.folder()), inSharedsaves());
	}

	// --- When unsure, keep it ---

	/** Where swaps used to move the old copy: nothing says which world it belongs to. */
	@Test
	void aMovedAsideCopyOfAnUnknownWorldIsLeftAsItIs() throws Exception {
		Path old = Files.createDirectories(sharedsaves.resolve(".wgds-123/old"));
		new LocalCopyRecord(1).write(old);
		write(old.resolve(CHUNKS), "chunks");

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals("chunks", Files.readString(old.resolve(CHUNKS)));
		assertEquals(Optional.of(new LocalCopyRecord(1)), LocalCopyRecord.read(old));
	}

	@Test
	void aMovedAsideCopyWithoutAReadableRecordIsLeftAsItIs() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		crashBetweenTheSwapsTwoMoves(shared.id());
		Path stranded = fileInSharedsaves(CHUNKS, "chunks");
		Path record = stranded.getParent().getParent().resolve(LocalCopyRecord.FILE_NAME);
		write(record, "not a record");

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(Optional.empty(), coordinator().localCopy(shared.id()));
		assertEquals("chunks", Files.readString(stranded));
		assertEquals("not a record", Files.readString(record));

		// Nor is it deleted once a Local Copy of the world is in place.
		coordinator().play(shared.id(), ProgressListener.NONE);
		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals("chunks", Files.readString(stranded));
	}

	@Test
	void aMovedAsideCopyIsKeptWhileSomethingElseOccupiesItsWorldsFolder() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		crashBetweenTheSwapsTwoMoves(shared.id());
		Path stranded = fileInSharedsaves(CHUNKS, "chunks");
		Path stray = sharedsaves.resolve(shared.id()).resolve("stray.txt");
		write(stray, "written by another program");

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals("chunks", Files.readString(stranded));
		assertEquals("written by another program", Files.readString(stray));

		// The next launch looks again.
		Files.delete(stray);
		Files.delete(stray.getParent());
		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(1, coordinator().localCopy(shared.id()).orElseThrow().revision());
	}

	// --- Other leftovers ---

	@Test
	void stagingFoldersZipsAndEmptyMoveAsideFoldersAreDeleted() throws Exception {
		SharedWorld shared = shareAWorld();
		write(sharedsaves.resolve(".wgds-111/level.dat"), "half a download");
		write(sharedsaves.resolve(".wgds-111/region/r.0.0.mca"), "half a download");
		write(sharedsaves.resolve(".wgds-222.zip"), "half a zip");
		Files.createDirectory(sharedsaves.resolve(".wgds-333"));
		Files.createDirectory(sharedsaves.resolve(".wgds-aside-444"));
		write(sharedsaves.resolve("someone-elses-folder/notes.txt"), "not ours");

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(sorted(sharedsaves.resolve("someone-elses-folder"), sharedsaves.resolve(shared.id())), inSharedsaves());
	}

	@Test
	void withNoSharedsavesFolderThereIsNothingToDo() throws Exception {
		Path missing = sharedsaves.resolve("not-yet");

		new SessionCoordinator(cloud, clock, missing, ALICE).recoverLocalCopiesAndDeleteLeftovers();

		assertFalse(Files.exists(missing));
	}

	/** On Windows the delete fails when a file is still open; here, a read-only folder makes it fail. */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void aLeftoverThatCantBeDeletedStopsNeitherTheRestOfTheCleanupNorPlay() throws Exception {
		SharedWorld shared = shareAWorld();
		// Several, so that some are cleaned up after one that failed, whatever order they come in.
		List<Path> stuck = new ArrayList<>();
		for (String name : List.of(".wgds-111", ".wgds-555", ".wgds-999")) {
			write(sharedsaves.resolve(name).resolve(CHUNKS), "stuck");
			write(sharedsaves.resolve(name + ".zip"), "half a zip");
			readOnly(sharedsaves.resolve(name).resolve("region"));
			stuck.add(sharedsaves.resolve(name));
		}
		try {
			coordinator().recoverLocalCopiesAndDeleteLeftovers();

			assertEquals(sorted(stuck.get(0), stuck.get(1), stuck.get(2), sharedsaves.resolve(shared.id())), inSharedsaves());
			assertInstanceOf(PlayResult.Ready.class, coordinator().play(shared.id(), ProgressListener.NONE));
		} finally {
			allWritable();
		}
	}

	/**
	 * A delete that stops partway may leave the record but not the whole world, so what is left must
	 * never be put back, nor kept for ever.
	 */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void aPartlyDeletedOldCopyIsNeverPutBackAndIsDeletedByALaterLaunch() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		Path folder = sharedsaves.resolve(shared.id());
		readOnly(folder.resolve("region"));
		try {
			coordinator().play(shared.id(), ProgressListener.NONE); // the old copy can't be deleted
			// The player deletes the new Local Copy by hand.
			deleteTree(folder);

			coordinator().recoverLocalCopiesAndDeleteLeftovers();

			assertEquals(Optional.empty(), coordinator().localCopy(shared.id()));
		} finally {
			allWritable();
		}

		coordinator().recoverLocalCopiesAndDeleteLeftovers();

		assertEquals(List.of(), inSharedsaves());
	}

	@Test
	void aStrandedCopyThatCantBeMovedBackIsKeptAndTheRestIsStillCleanedUp() throws Exception {
		SharedWorld shared = shareAWorld();
		bobUploads(shared.id(), "chunks at Revision 2");
		crashBetweenTheSwapsTwoMoves(shared.id());
		Path stranded = fileInSharedsaves(CHUNKS, "chunks");
		write(sharedsaves.resolve(".wgds-222.zip"), "half a zip");
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			throw new AccessDeniedException(from.toString());
		});

		alice.recoverLocalCopiesAndDeleteLeftovers();

		assertEquals("chunks", Files.readString(stranded));
		assertFalse(Files.exists(sharedsaves.resolve(".wgds-222.zip")));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void anUploadSucceedsEvenIfItsStagingFolderCantBeDeleted() throws Exception {
		SharedWorld shared = shareAWorld();
		coordinator().play(shared.id(), ProgressListener.NONE);
		try {
			WorldMetadata uploaded = coordinator().periodicUpload(shared.id(), folder -> {
				write(folder.resolve(CHUNKS), "chunks");
				readOnly(folder.resolve("region"));
			}, ProgressListener.NONE);

			assertEquals(2, uploaded.revision());
		} finally {
			allWritable();
		}
	}

	// --- What cleanup relies on ---

	/** Cleanup deletes moved-aside copies, so none may hold progress the cloud doesn't have. */
	@Test
	void aDownloadNeverMovesAsideACopyWithUnsavedProgressStillRecorded() throws Exception {
		SharedWorld shared = shareAWorld();
		Path folder = sharedsaves.resolve(shared.id());
		List<Boolean> unsavedWhenMovedAside = new ArrayList<>();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (from.equals(folder)) unsavedWhenMovedAside.add(LocalCopyRecord.read(from).orElseThrow().hasUnsavedProgress());
			Files.move(from, to);
		});
		// A Pending Upload replaced by Play, then another replaced by the retry.
		leaveAPendingUpload(shared.id());
		bobUploads(shared.id(), "chunks at Revision 2");
		alice.play(shared.id(), ProgressListener.NONE);
		alice.leaveWithoutPlaying(shared.id());
		leaveAPendingUpload(shared.id());
		bobUploads(shared.id(), "chunks at Revision 3");
		alice.retryPendingUpload(shared.id(), ProgressListener.NONE);

		assertEquals(List.of(false, false), unsavedWhenMovedAside);
		assertEquals(3, alice.localCopy(shared.id()).orElseThrow().revision());
	}

	private SessionCoordinator coordinator() {
		return new SessionCoordinator(cloud, clock, sharedsaves, ALICE);
	}

	private SessionCoordinator coordinatorMovingWith(SessionCoordinator.FolderMover mover) {
		return new SessionCoordinator(cloud, clock, sharedsaves, ALICE, () -> Settings.DEFAULTS, mover);
	}

	private SharedWorld shareAWorld() throws IOException {
		return coordinator().share(singleplayerWorld(), "Survival", ProgressListener.NONE);
	}

	/** Alice plays, but her final upload fails. */
	private void leaveAPendingUpload(String worldId) throws IOException {
		coordinator().play(worldId, ProgressListener.NONE);
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(worldId, ProgressListener.NONE));
		cloud.uploadsWork();
	}

	/**
	 * Bob, on another computer, plays a session and uploads the next Revision, first letting any Lock
	 * Alice left behind go Stale so he can Take Over.
	 */
	private void bobUploads(String worldId, String chunks) throws IOException {
		SessionCoordinator bob = new SessionCoordinator(cloud, clock, bobsSharedsaves, BOB);
		clock.advance(Duration.ofMinutes(10));
		LocalCopy local = ((PlayResult.Ready) bob.takeOver(worldId, ProgressListener.NONE)).localCopy();
		write(local.folder().resolve(CHUNKS), chunks);
		clock.advance(Duration.ofHours(1));
		bob.quit(worldId, ProgressListener.NONE);
	}

	/**
	 * Alice presses Play, and the game dies after the old Local Copy has been moved aside but before
	 * the downloaded one has been moved into its place.
	 */
	private void crashBetweenTheSwapsTwoMoves(String worldId) {
		Path folder = sharedsaves.resolve(worldId);
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (to.equals(folder)) throw new Crash();
			Files.move(from, to);
		});
		assertThrows(Crash.class, () -> alice.play(worldId, ProgressListener.NONE));
	}

	/** An {@link Error}, so that nothing catches it and puts things right, as nothing would after a crash. */
	private static final class Crash extends Error {
	}

	private Path singleplayerWorld() throws IOException {
		Path world = saves.resolve("Survival");
		write(world.resolve("level.dat"), "level data");
		write(world.resolve(CHUNKS), "chunks");
		return world;
	}

	private List<Path> inSharedsaves() throws IOException {
		try (Stream<Path> entries = Files.list(sharedsaves)) {
			return entries.sorted().toList();
		}
	}

	private static List<Path> sorted(Path... paths) {
		return Stream.of(paths).sorted().toList();
	}

	/** The file at {@code path} in some folder of {@code sharedsaves/} that holds {@code content}. */
	private Path fileInSharedsaves(String path, String content) throws IOException {
		try (Stream<Path> files = Files.walk(sharedsaves)) {
			for (Path file : files.filter(file -> file.endsWith(path)).toList()) {
				if (Files.readString(file).equals(content)) return file;
			}
		}
		throw new AssertionError("No " + path + " holding " + content);
	}

	private static void readOnly(Path folder) throws IOException {
		Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
	}

	/** Wherever the read-only folders ended up, lets them be deleted again. */
	private void allWritable() throws IOException {
		try (Stream<Path> walk = Files.walk(sharedsaves)) {
			for (Path folder : walk.filter(Files::isDirectory).toList()) {
				Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
		}
	}

	private static void deleteTree(Path folder) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
