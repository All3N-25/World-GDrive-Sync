package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import net.aii3n.worldgdrivesync.settings.Settings;

/**
 * Owns every Shared World rule: sharing, listing with status, and (in later tickets) Play, Locks,
 * Heartbeats, uploads, Pending Uploads and Backups. Pure Java: no Minecraft or Google classes, so
 * it is tested directly with an in-memory cloud store, a test clock and a temporary Local Copy
 * directory.
 *
 * <p>Every method blocks on the cloud store, so the game calls it off the render thread.
 */
public final class SessionCoordinator {
	private static final String STAGING_PREFIX = ".wgds-";

	private final CloudStore cloud;
	private final InstantSource clock;
	private final Path localCopies;
	private final Player me;
	private final Supplier<Settings> settings;

	/**
	 * @param cloud       where Shared Worlds are stored
	 * @param clock       the time source for every timestamp the coordinator records
	 * @param localCopies the Local Copy directory ({@code sharedsaves/}, ADR-0002); one folder per
	 *                    Shared World, named by its id
	 * @param me          the player on this computer, who becomes Host when they press Play
	 */
	public SessionCoordinator(CloudStore cloud, InstantSource clock, Path localCopies, Player me) {
		this(cloud, clock, localCopies, me, () -> Settings.DEFAULTS);
	}

	/**
	 * @param settings the player's current settings, read each time one is needed, so changes take
	 *                 effect without rebuilding the coordinator
	 */
	public SessionCoordinator(CloudStore cloud, InstantSource clock, Path localCopies, Player me, Supplier<Settings> settings) {
		this.cloud = cloud;
		this.clock = clock;
		this.localCopies = localCopies;
		this.me = me;
		this.settings = settings;
	}

	/**
	 * Every Shared World on the account, read fresh from the cloud store. A world whose sharing never
	 * finished (no metadata yet) is left out.
	 */
	public List<SharedWorld> listSharedWorlds() throws IOException {
		List<SharedWorld> result = new ArrayList<>();
		for (CloudWorld world : cloud.listWorlds()) {
			Optional<WorldMetadata> metadata = cloud.readMetadata(world.id());
			if (metadata.isEmpty()) continue;
			result.add(sharedWorld(world, metadata.get(), status(world.id()), hasPendingUpload(world.id())));
		}
		return result;
	}

	/** This player's Local Copy of a Shared World, if they have one. */
	public Optional<LocalCopy> localCopy(String worldId) {
		Path folder = localCopies.resolve(worldId);
		return LocalCopyRecord.read(folder).map(record -> new LocalCopy(folder, record.revision(), record.pendingUpload()));
	}

	/**
	 * The Shared Worlds whose Local Copy on this computer is a Pending Upload. Reads only the Local
	 * Copy directory, so it works offline; the game retries each one on launch.
	 */
	public List<String> pendingUploads() throws IOException {
		if (!Files.isDirectory(localCopies)) return List.of();
		try (Stream<Path> folders = Files.list(localCopies)) {
			return folders.map(folder -> folder.getFileName().toString())
					.filter(worldId -> !worldId.startsWith(STAGING_PREFIX) && hasPendingUpload(worldId))
					.sorted()
					.toList();
		}
	}

	private boolean hasPendingUpload(String worldId) {
		return localCopy(worldId).map(LocalCopy::pendingUpload).orElse(false);
	}

	/**
	 * Shares an existing singleplayer world: uploads it into a new cloud folder as Revision 1 and
	 * keeps a Local Copy of it. {@code worldFolder} is only read; the original world stays where it
	 * is. If anything fails, the world is not listed and no Local Copy is left behind.
	 *
	 * @param worldFolder a world folder that isn't open in the game
	 * @param name        the name everyone sees on the Shared Worlds screen
	 */
	public SharedWorld share(Path worldFolder, String name, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path staging = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			// Copy first, then zip the copy, so the upload and the Local Copy are the same world.
			WorldZip.copy(worldFolder, staging);
			WorldZip.zip(staging, zip, progress);
			long size = Files.size(zip);

			CloudWorld world = cloud.createWorld(name);
			cloud.uploadCurrentSave(world.id(), zip, sent -> progress.onProgress(ProgressListener.Step.UPLOADING, sent, size));
			WorldMetadata metadata = new WorldMetadata(1, clock.instant());
			cloud.writeMetadata(world.id(), metadata);

			new LocalCopyRecord(metadata.revision()).write(staging);
			Files.move(staging, localCopies.resolve(world.id()));
			return sharedWorld(world, metadata, WorldStatus.FREE);
		} finally {
			Files.deleteIfExists(zip);
			deleteRecursively(staging);
		}
	}

	/**
	 * Play: makes this player the Host. Takes the Lock before anything else, so nobody else can start
	 * at the same time; is refused, changing nothing, while someone else holds it. Then makes sure
	 * the Local Copy is the Current Revision, downloading it if this computer has an older one or
	 * none. If that fails, the Lock is released again.
	 *
	 * @return {@link PlayResult.Ready} with the Local Copy to open, or {@link PlayResult.Refused}
	 */
	public PlayResult play(String worldId, ProgressListener progress) throws IOException {
		Optional<Lock> held = currentLock(worldId);
		if (held.isPresent() && !held.get().heldBy(me)) return new PlayResult.Refused(held.get());
		cloud.writeLock(worldId, Lock.takenBy(me, clock.instant()));
		try {
			return new PlayResult.Ready(bringLocalCopyUpToDate(worldId, progress));
		} catch (IOException | RuntimeException e) {
			releaseLock(worldId);
			throw e;
		}
	}

	/** The Local Copy at the Current Revision, downloaded first if this computer's copy is older or missing. */
	private LocalCopy bringLocalCopyUpToDate(String worldId, ProgressListener progress) throws IOException {
		WorldMetadata current = cloud.readMetadata(worldId)
				.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
		Optional<LocalCopy> local = localCopy(worldId);
		if (local.isPresent() && local.get().revision() >= current.revision()) return local.get();
		return download(worldId, current, progress);
	}

	/**
	 * Replaces the Local Copy with the Current Save. The new copy is unpacked beside the old one and
	 * swapped in only once complete, so a failed download leaves the old Local Copy as it was.
	 */
	private LocalCopy download(String worldId, WorldMetadata current, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path staging = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			cloud.downloadCurrentSave(worldId, zip, (done, total) -> progress.onProgress(ProgressListener.Step.DOWNLOADING, done, total));
			WorldZip.unzip(zip, staging, progress);
			new LocalCopyRecord(current.revision()).write(staging);
			Path folder = localCopies.resolve(worldId);
			deleteRecursively(folder);
			Files.move(staging, folder);
			return new LocalCopy(folder, current.revision());
		} finally {
			Files.deleteIfExists(zip);
			deleteRecursively(staging);
		}
	}

	/**
	 * Gives up hosting after a successful {@link #play} but before the world was opened (the player
	 * backed out of a prompt, or the world failed to load). Nothing changed, so nothing is uploaded.
	 */
	public void leaveWithoutPlaying(String worldId) throws IOException {
		releaseLock(worldId);
	}

	/**
	 * Save-and-quit: uploads the Local Copy as the new Current Save, then releases the Lock. Call it
	 * once the game has closed the world, so everything is on disk. The Lock is released only after
	 * the upload succeeds. If it fails, this throws, the Lock is kept, and the Local Copy becomes a
	 * Pending Upload (see {@link #retryPendingUpload}).
	 */
	public void quit(String worldId, ProgressListener progress) throws IOException {
		LocalCopy local = localCopy(worldId).orElseThrow(() -> new IOException("No Local Copy of " + worldId + " to upload"));
		try {
			upload(worldId, local.folder(), progress);
		} catch (IOException | RuntimeException e) {
			markPendingUpload(local, e);
			throw e;
		}
		releaseLock(worldId);
	}

	/**
	 * Retries a Pending Upload left by a failed {@link #quit}: uploads the Local Copy as the next
	 * Revision, clears the Pending Upload and releases the Lock, just like a normal quit. If it fails
	 * again, this throws and the Lock and the Pending Upload are kept.
	 *
	 * <p>It never overwrites a newer Revision or uploads under another Host: if someone else holds
	 * the Lock, or the cloud has moved past the Local Copy's Revision (someone Took Over), this
	 * throws without uploading and the Pending Upload is kept for crash recovery on Play.
	 */
	public void retryPendingUpload(String worldId, ProgressListener progress) throws IOException {
		LocalCopy local = localCopy(worldId).filter(LocalCopy::pendingUpload)
				.orElseThrow(() -> new IOException("No Pending Upload of " + worldId + " on this computer"));
		Optional<Lock> held = currentLock(worldId);
		if (held.isPresent() && !held.get().heldBy(me)) {
			throw new IOException(held.get().hostName() + " is hosting this world now, so your Pending Upload can't be uploaded");
		}
		WorldMetadata current = cloud.readMetadata(worldId)
				.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
		if (current.revision() > local.revision()) {
			throw new IOException("Someone uploaded Revision " + current.revision() + " since, so your Pending Upload won't overwrite it");
		}
		quit(worldId, progress);
	}

	/** Remembers in the Local Copy that it still has to be uploaded; {@code failure} is why. */
	private static void markPendingUpload(LocalCopy local, Exception failure) {
		try {
			new LocalCopyRecord(local.revision(), true).write(local.folder());
		} catch (IOException e) {
			// The upload failure stays the one reported; the Lock is kept either way.
			failure.addSuppressed(e);
		}
	}

	/**
	 * Whether this player is hosting the world and a periodic upload is due: periodic upload is
	 * enabled and a whole interval has passed since the later of the last upload and the start of
	 * hosting. Reads the cloud store, so check it now and then rather than every tick.
	 */
	public boolean periodicUploadDue(String worldId) throws IOException {
		Settings current = settings.get();
		if (!current.periodicUploadEnabled()) return false;
		Optional<Lock> lock = currentLock(worldId);
		if (lock.isEmpty() || !lock.get().heldBy(me)) return false;
		Instant since = lock.get().startedAt();
		Optional<WorldMetadata> metadata = cloud.readMetadata(worldId);
		if (metadata.isPresent() && metadata.get().savedAt().isAfter(since)) since = metadata.get().savedAt();
		return !clock.instant().isBefore(since.plus(current.periodicUploadInterval()));
	}

	/**
	 * Uploads the world while it is being hosted, as the next Revision. The Host keeps the Lock and
	 * nobody is kicked. {@code snapshot} is asked to save the open world and copy it into a folder
	 * this method then uploads and deletes.
	 *
	 * @return the new Current Save's metadata
	 */
	public WorldMetadata periodicUpload(String worldId, WorldSnapshot snapshot, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path folder = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		try {
			snapshot.saveTo(folder);
			return upload(worldId, folder, progress);
		} finally {
			deleteRecursively(folder);
		}
	}

	/**
	 * The one upload path, shared by every kind of upload: makes {@code worldFolder} the Current Save
	 * at the next Revision, and records that Revision in the Local Copy.
	 *
	 * @param worldFolder the world to upload, already saved to disk and not being written to: the
	 *                    Local Copy itself after the world has closed, or a snapshot of it
	 * @return the new Current Save's metadata
	 */
	private WorldMetadata upload(String worldId, Path worldFolder, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			WorldZip.zip(worldFolder, zip, progress);
			long size = Files.size(zip);
			WorldMetadata previous = cloud.readMetadata(worldId)
					.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));

			cloud.uploadCurrentSave(worldId, zip, sent -> progress.onProgress(ProgressListener.Step.UPLOADING, sent, size));
			WorldMetadata next = new WorldMetadata(previous.revision() + 1, clock.instant());
			cloud.writeMetadata(worldId, next);
			new LocalCopyRecord(next.revision()).write(localCopies.resolve(worldId));
			return next;
		} finally {
			Files.deleteIfExists(zip);
		}
	}

	private void releaseLock(String worldId) throws IOException {
		cloud.deleteLock(worldId, me.id());
	}

	/** The Lock that counts for a world: the earliest one, if several exist. */
	private Optional<Lock> currentLock(String worldId) throws IOException {
		return cloud.readLocks(worldId).stream().min(Comparator.comparing(Lock::startedAt));
	}

	private WorldStatus status(String worldId) throws IOException {
		return currentLock(worldId)
				.<WorldStatus>map(lock -> new WorldStatus.Hosted(lock.hostName(), lock.startedAt()))
				.orElse(WorldStatus.FREE);
	}

	private static SharedWorld sharedWorld(CloudWorld world, WorldMetadata metadata, WorldStatus status) {
		return sharedWorld(world, metadata, status, false);
	}

	private static SharedWorld sharedWorld(CloudWorld world, WorldMetadata metadata, WorldStatus status, boolean pendingUpload) {
		return new SharedWorld(world.id(), world.name(), metadata.revision(), metadata.savedAt(), status, pendingUpload);
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
