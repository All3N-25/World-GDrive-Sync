package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.settings.Settings;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
	/** How long to wait after writing a Lock for a simultaneous one to show up in the cloud store. */
	private static final Duration LOCK_SETTLE_TIME = Duration.ofSeconds(3);

	private final CloudStore cloud;
	private final SessionClock clock;
	private final Path localCopies;
	private final Player me;
	private final Supplier<Settings> settings;

	/** A coordinator with the default {@link Settings}. */
	public SessionCoordinator(CloudStore cloud, SessionClock clock, Path localCopies, Player me) {
		this(cloud, clock, localCopies, me, () -> Settings.DEFAULTS);
	}

	/**
	 * @param cloud       where Shared Worlds are stored
	 * @param clock       the time source for every timestamp the coordinator records, and for waiting
	 * @param localCopies the Local Copy directory ({@code sharedsaves/}, ADR-0002); one folder per
	 *                    Shared World, named by its id
	 * @param me          the player on this computer, who becomes Host when they press Play
	 * @param settings    the player's current settings, read each time one is needed
	 */
	public SessionCoordinator(CloudStore cloud, SessionClock clock, Path localCopies, Player me, Supplier<Settings> settings) {
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
			result.add(sharedWorld(world, metadata.get(), status(world.id())));
		}
		return result;
	}

	/** This player's Local Copy of a Shared World, if they have one. */
	public Optional<LocalCopy> localCopy(String worldId) {
		Path folder = localCopies.resolve(worldId);
		return LocalCopyRecord.read(folder).map(record -> new LocalCopy(folder, record.revision()));
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
	 * @return {@link PlayResult.Ready} with the Local Copy to open, {@link PlayResult.Refused}, or
	 *         {@link PlayResult.Stale} if the Lock someone else holds is Stale (offer {@link #takeOver})
	 */
	public PlayResult play(String worldId, ProgressListener progress) throws IOException {
		Optional<Lock> held = currentLock(worldId);
		if (held.isPresent() && !held.get().heldBy(me)) {
			return isStale(held.get()) ? new PlayResult.Stale(held.get()) : new PlayResult.Refused(held.get());
		}
		return becomeHost(worldId, progress);
	}

	/**
	 * Takeover: Play despite a Stale Lock, after the player accepted that its Host's unsaved progress
	 * may be lost. Replaces the Stale Lock with this player's and starts from the latest Current Save.
	 * Refused, changing nothing, if that Host's Heartbeat has resumed meanwhile.
	 *
	 * @return as {@link #play}, but never {@link PlayResult.Stale}
	 */
	public PlayResult takeOver(String worldId, ProgressListener progress) throws IOException {
		Optional<Lock> held = currentLock(worldId);
		if (held.isPresent() && !held.get().heldBy(me)) {
			if (!isStale(held.get())) return new PlayResult.Refused(held.get());
			cloud.deleteLock(worldId, held.get().hostId());
		}
		return becomeHost(worldId, progress);
	}

	/**
	 * Takes the Lock, then brings the Local Copy up to date; releases the Lock again on failure.
	 *
	 * <p>The cloud store can't create a Lock only if none exists (ADR-0001), so two players who press
	 * Play together may both write one. After writing, wait for the other's Lock to show up, then the
	 * earliest Lock wins; the loser deletes their own and is told who is hosting.
	 */
	private PlayResult becomeHost(String worldId, ProgressListener progress) throws IOException {
		cloud.writeLock(worldId, Lock.takenBy(me, clock.instant()));
		try {
			try {
				clock.sleep(LOCK_SETTLE_TIME);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while taking the Lock");
			}
			Optional<Lock> winner = currentLock(worldId);
			if (winner.isPresent() && !winner.get().heldBy(me)) {
				releaseLock(worldId);
				return new PlayResult.Refused(winner.get());
			}
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
	 * the upload succeeds; if it fails, this throws and the Lock is kept.
	 */
	public void quit(String worldId, ProgressListener progress) throws IOException {
		LocalCopy local = localCopy(worldId).orElseThrow(() -> new IOException("No Local Copy of " + worldId + " to upload"));
		upload(worldId, local.folder(), progress);
		releaseLock(worldId);
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

	/**
	 * Heartbeat: refreshes this player's Lock so friends see they are still hosting. The game calls it
	 * every minute while hosting. If the Lock is gone (a friend Took Over after it went Stale), it is
	 * not put back.
	 *
	 * @return whether this player still holds the Lock
	 */
	public boolean heartbeat(String worldId) throws IOException {
		Optional<Lock> mine = cloud.readLocks(worldId).stream().filter(lock -> lock.heldBy(me)).findFirst();
		if (mine.isEmpty()) return false;
		cloud.writeLock(worldId, mine.get().heartbeatAt(clock.instant()));
		return true;
	}

	private void releaseLock(String worldId) throws IOException {
		cloud.deleteLock(worldId, me.id());
	}

	/**
	 * The Lock that counts for a world: the earliest one, if several exist. "Earliest" is the cloud
	 * store's own order (see {@link CloudStore#readLocks}), not the players' clocks, which may disagree.
	 */
	private Optional<Lock> currentLock(String worldId) throws IOException {
		return cloud.readLocks(worldId).stream().findFirst();
	}

	private WorldStatus status(String worldId) throws IOException {
		return currentLock(worldId)
				.<WorldStatus>map(lock -> isStale(lock)
						? new WorldStatus.Stale(lock.hostName(), lock.lastHeartbeat())
						: new WorldStatus.Hosted(lock.hostName(), lock.startedAt()))
				.orElse(WorldStatus.FREE);
	}

	/** Whether {@code lock} has had no Heartbeat for longer than the stale timeout. */
	private boolean isStale(Lock lock) {
		return Duration.between(lock.lastHeartbeat(), clock.instant()).compareTo(settings.get().staleTimeout()) > 0;
	}

	private static SharedWorld sharedWorld(CloudWorld world, WorldMetadata metadata, WorldStatus status) {
		return new SharedWorld(world.id(), world.name(), metadata.revision(), metadata.savedAt(), status);
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
