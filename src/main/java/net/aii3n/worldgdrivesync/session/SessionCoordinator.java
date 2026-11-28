package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link #heartbeatHeldLocks} and {@link #listSharedWorlds} may each run on a thread of their own
 * alongside any other call.
 */
public final class SessionCoordinator {
	private static final String STAGING_PREFIX = ".wgds-";
	/** How long to wait after writing a Lock for a simultaneous one to show up in the cloud store. */
	private static final Duration LOCK_SETTLE_TIME = Duration.ofSeconds(3);
	private static final System.Logger LOGGER = System.getLogger(SessionCoordinator.class.getName());

	private final CloudStore cloud;
	private final SessionClock clock;
	private final Path localCopies;
	private final Player me;
	private final Supplier<Settings> settings;
	private final FolderMover mover;
	/** The worlds this player took the Lock on through this coordinator and hasn't released. */
	private final Set<String> heldLocks = ConcurrentHashMap.newKeySet();
	/**
	 * Worlds whose Lock {@link #quit} couldn't delete after its upload succeeded; Heartbeats retry.
	 * Synchronized on while retrying and while {@link #takeLock} cancels a retry, so a retry never
	 * deletes a Lock just taken again.
	 */
	private final Set<String> locksToRelease = ConcurrentHashMap.newKeySet();

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
		this(cloud, clock, localCopies, me, settings, Files::move);
	}

	/** As the public constructor, with {@code mover} swapping Local Copies, so a test can make a swap fail. */
	SessionCoordinator(CloudStore cloud, SessionClock clock, Path localCopies, Player me, Supplier<Settings> settings, FolderMover mover) {
		this.cloud = cloud;
		this.clock = clock;
		this.localCopies = localCopies;
		this.me = me;
		this.settings = settings;
		this.mover = mover;
	}

	/** Renames a folder to a path where nothing exists. */
	@FunctionalInterface
	interface FolderMover {
		void move(Path from, Path to) throws IOException;
	}

	/**
	 * Every Shared World on the account, read fresh from the cloud store. A world whose sharing never
	 * finished (no metadata yet) is left out.
	 *
	 * <p>Changes nothing, so it may run alongside any other call: it only reads the cloud store and
	 * the Local Copy records.
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
	 * The Shared Worlds whose Local Copy on this computer still has to be uploaded: a Pending Upload,
	 * or a hosting session that ended without its final upload (the game crashed or its window was
	 * closed). Reads only the Local Copy directory, so it works offline. The game retries each one
	 * with {@link #retryPendingUpload} on launch, before it hosts anything, so none of these is a
	 * session still open in this game.
	 */
	public List<String> pendingUploads() throws IOException {
		if (!Files.isDirectory(localCopies)) return List.of();
		try (Stream<Path> folders = Files.list(localCopies)) {
			return folders.map(folder -> folder.getFileName().toString())
					.filter(worldId -> !worldId.startsWith(STAGING_PREFIX) && hasUnsavedProgress(worldId))
					.sorted()
					.toList();
		}
	}

	private boolean hasUnsavedProgress(String worldId) {
		return LocalCopyRecord.read(localCopies.resolve(worldId)).map(LocalCopyRecord::hasUnsavedProgress).orElse(false);
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
			// Copy first, then zip the copy, so the upload and the Local Copy are the same world (apart
			// from the Local-only Data, which only the Local Copy gets).
			WorldZip.copyForLocalCopy(worldFolder, staging);
			WorldZip.zip(staging, zip, progress);
			long size = Files.size(zip);

			CloudWorld world = cloud.createWorld(name);
			WorldMetadata metadata = new WorldMetadata(1, clock.instant());
			cloud.uploadCurrentSave(world.id(), zip, metadata, sent -> progress.onProgress(ProgressListener.Step.UPLOADING, sent, size));

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
	 * the Local Copy is at the Current Save's Revision, downloading it if this computer has an older one or
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
		return becomeHost(worldId, held.isPresent(), progress);
	}

	/**
	 * Takeover: Play despite a Stale Lock, after the player accepted that its Host's unsaved progress
	 * may be lost. Replaces the Stale Lock with this player's and starts from the Current Save.
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
		return becomeHost(worldId, held.isPresent() && held.get().heldBy(me), progress);
	}

	/**
	 * Takes the Lock, then brings the Local Copy up to date; releases the Lock again on failure.
	 *
	 * <p>The cloud store can't create a Lock only if none exists (ADR-0001), so two players who press
	 * Play together may both write one. After writing, wait for the other's Lock to show up, then the
	 * earliest Lock wins; the loser deletes their own and is told who is hosting.
	 *
	 * @param alreadyHeld this player held the Lock before this call (left by a crashed session or a
	 *                    Pending Upload), so it protects unsaved progress and must outlive a failure
	 */
	private PlayResult becomeHost(String worldId, boolean alreadyHeld, ProgressListener progress) throws IOException {
		Instant startedAt = clock.instant();
		Optional<Lock> winner = takeLock(worldId, startedAt, alreadyHeld);
		if (winner.isPresent()) return new PlayResult.Refused(winner.get());
		try {
			Optional<Backup> unmerged = bringLocalCopyUpToDate(worldId, progress);
			markSessionOpen(worldId, startedAt);
			return new PlayResult.Ready(localCopy(worldId).orElseThrow(), unmerged);
		} catch (IOException | RuntimeException e) {
			try {
				giveUpHosting(worldId);
			} catch (IOException | RuntimeException release) {
				e.addSuppressed(release);
			}
			throw e;
		}
	}

	/**
	 * Ends this player's hosting without an upload. The Lock is released, unless the Local Copy holds
	 * unsaved progress (a crashed session or a Pending Upload) that can still become the Current
	 * Save: then the Lock is kept and the Local Copy becomes a Pending Upload, so nobody starts over
	 * it. Progress the cloud has already moved past doesn't need the Lock: it becomes an Unmerged
	 * Backup.
	 */
	private void giveUpHosting(String worldId) throws IOException {
		Path folder = localCopies.resolve(worldId);
		Optional<LocalCopyRecord> record = LocalCopyRecord.read(folder);
		if (record.isPresent() && record.get().hasUnsavedProgress() && !cloudMovedPast(worldId, record.get())) {
			record.get().withPendingUpload(true).write(folder);
			return;
		}
		releaseLock(worldId);
	}

	/** Whether someone uploaded a newer Revision than this Local Copy's; false if that can't be told. */
	private boolean cloudMovedPast(String worldId, LocalCopyRecord record) {
		try {
			return cloud.readMetadata(worldId).map(current -> current.revision() > record.revision()).orElse(false);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Writes this player's Lock, waits for a simultaneous one to show up, and settles who won.
	 *
	 * @param alreadyHeld see {@link #becomeHost}: if set, a failure leaves the Lock in place
	 * @return empty if this player now holds the Lock; otherwise the winner's Lock (this player's own
	 *         has been deleted again)
	 */
	private Optional<Lock> takeLock(String worldId, Instant startedAt, boolean alreadyHeld) throws IOException {
		synchronized (locksToRelease) {
			locksToRelease.remove(worldId); // the Lock left behind by a quit is this new one now
		}
		cloud.writeLock(worldId, Lock.takenBy(me, startedAt));
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
				return winner;
			}
			heldLocks.add(worldId);
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			if (!alreadyHeld) releaseLock(worldId);
			throw e;
		}
	}

	/**
	 * Applies the Revision rules to this computer's Local Copy:
	 * <ol>
	 *   <li>at or past the Current Save's Revision: open it as it is (after a crash, this resumes the
	 *       crashed session's progress);</li>
	 *   <li>older, with unsaved progress (a crashed session or a Pending Upload), so someone else
	 *       uploaded since: upload it as an Unmerged Backup, then download the Current Save;</li>
	 *   <li>older or missing otherwise: download the Current Save.</li>
	 * </ol>
	 *
	 * @return the Unmerged Backup, if one was made
	 */
	private Optional<Backup> bringLocalCopyUpToDate(String worldId, ProgressListener progress) throws IOException {
		WorldMetadata current = cloud.readMetadata(worldId)
				.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
		Optional<LocalCopyRecord> record = LocalCopyRecord.read(localCopies.resolve(worldId));
		if (record.isPresent() && record.get().revision() >= current.revision()) return Optional.empty();
		Optional<Backup> unmerged = Optional.empty();
		if (record.isPresent() && record.get().hasUnsavedProgress()) unmerged = Optional.of(keepAsUnmergedBackup(worldId, record.get(), progress));
		download(worldId, current, progress);
		return unmerged;
	}

	/**
	 * Uploads the Local Copy into the Backups as an Unmerged Backup, labelled with this player and
	 * their crashed session, without touching the Current Save. Then forgets that the Local Copy had
	 * unsaved progress, so a failed download afterwards doesn't make a second Unmerged Backup.
	 */
	private Backup keepAsUnmergedBackup(String worldId, LocalCopyRecord record, ProgressListener progress) throws IOException {
		Path folder = localCopies.resolve(worldId);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			WorldZip.zip(folder, zip, progress);
			long size = Files.size(zip);
			Instant now = clock.instant();
			CrashedSession session = new CrashedSession(me.id(), me.name(), record.sessionStartedAt().orElse(now));
			BackupLabel label = new BackupLabel(record.revision(), now, Optional.of(session));
			Backup backup = cloud.uploadBackup(worldId, zip, label, sent -> progress.onProgress(ProgressListener.Step.UPLOADING, sent, size));
			new LocalCopyRecord(record.revision()).write(folder);
			pruneBackups(worldId);
			return backup;
		} finally {
			Files.deleteIfExists(zip);
		}
	}

	/**
	 * Remembers in the Local Copy that a hosting session of it is open, until {@link #quit} uploads
	 * it. A session resumed after a crash keeps the crashed session's start.
	 */
	private void markSessionOpen(String worldId, Instant startedAt) throws IOException {
		Path folder = localCopies.resolve(worldId);
		LocalCopyRecord record = LocalCopyRecord.read(folder).orElseThrow(() -> new IOException("No Local Copy of " + worldId));
		if (record.sessionStartedAt().isPresent()) return;
		record.withSessionStartedAt(Optional.of(startedAt)).write(folder);
	}

	/**
	 * Replaces the Local Copy with the Current Save. The new copy is unpacked beside the old one and
	 * swapped in only once complete, so a failed download leaves the old Local Copy as it was. The
	 * old copy's Local-only Data is carried into the new one.
	 */
	private void download(String worldId, WorldMetadata current, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path staging = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			cloud.downloadCurrentSave(worldId, zip, (done, total) -> progress.onProgress(ProgressListener.Step.DOWNLOADING, done, total));
			WorldZip.unzip(zip, staging, progress);
			new LocalCopyRecord(current.revision()).write(staging);
			Path folder = localCopies.resolve(worldId);
			LocalOnlyData.carryOver(folder, staging);
			swapIn(staging, folder);
		} finally {
			Files.deleteIfExists(zip);
			deleteRecursively(staging);
		}
	}

	/**
	 * Gives up hosting after a successful {@link #play} but before the world was opened (the player
	 * backed out of a prompt, or the world failed to load). Nothing changed, so nothing is uploaded,
	 * and the Lock is released. But if that Play resumed a crashed session, its progress still has to
	 * be uploaded: the Lock is kept and the Local Copy becomes a Pending Upload.
	 */
	public void leaveWithoutPlaying(String worldId) throws IOException {
		// The session this Play opened is over with nothing to upload. A crashed session it resumed
		// started before this Lock, and still holds unsaved progress, so that one stays open.
		Optional<Lock> mine = cloud.readLocks(worldId).stream().filter(lock -> lock.heldBy(me)).findFirst();
		Path folder = localCopies.resolve(worldId);
		Optional<LocalCopyRecord> record = LocalCopyRecord.read(folder);
		if (mine.isPresent() && record.isPresent() && record.get().sessionStartedAt().equals(Optional.of(mine.get().startedAt()))) {
			record.get().withSessionStartedAt(Optional.empty()).write(folder);
		}
		giveUpHosting(worldId);
	}

	/**
	 * Save-and-quit: uploads the Local Copy as the new Current Save, then releases the Lock. Call it
	 * once the game has closed the world, so everything is on disk. The Lock is released only after
	 * the upload succeeds; if releasing it fails, the upload still counts and {@link
	 * #heartbeatHeldLocks} keeps trying to release it. If the upload fails, this throws, the Lock is kept, and the Local Copy becomes a
	 * Pending Upload (see {@link #retryPendingUpload}). It also fails that way if this player no
	 * longer holds the Lock (a friend Took Over): the Local Copy is then never uploaded over the new
	 * Host's world, and becomes an Unmerged Backup later instead.
	 *
	 * @return the new Current Save's metadata
	 */
	public WorldMetadata quit(String worldId, ProgressListener progress) throws IOException {
		LocalCopy local = localCopy(worldId).orElseThrow(() -> new IOException("No Local Copy of " + worldId + " to upload"));
		WorldMetadata uploaded;
		try {
			uploaded = upload(worldId, local.folder(), true, progress);
		} catch (IOException | RuntimeException e) {
			markPendingUpload(local.folder(), e);
			throw e;
		}
		try {
			releaseLock(worldId);
		} catch (IOException | RuntimeException e) {
			locksToRelease.add(worldId);
			LOGGER.log(System.Logger.Level.WARNING, "Uploaded " + worldId + " but couldn't release its Lock; will retry with the next Heartbeat", e);
		}
		return uploaded;
	}

	/**
	 * Retries a Pending Upload left by a failed {@link #quit}, or by a hosting session that ended
	 * without one (see {@link #pendingUploads}): uploads the Local Copy as the next Revision, clears
	 * the Pending Upload and releases the Lock, just like a normal quit. If it fails, this throws and
	 * the Lock and the Pending Upload are kept.
	 *
	 * <p>It never overwrites a newer Revision or uploads under another Host. If the cloud has moved
	 * past the Local Copy's Revision (someone Took Over and uploaded), the Pending Upload is kept as
	 * an Unmerged Backup instead and the Local Copy replaced with the newer Revision; the Lock isn't
	 * touched. If someone else holds the Lock but hasn't uploaded yet, this throws and the Pending
	 * Upload is kept. If nobody holds the Lock (it was Taken Over and given up), this player takes it
	 * again for the upload.
	 */
	public RetryResult retryPendingUpload(String worldId, ProgressListener progress) throws IOException {
		Path folder = localCopies.resolve(worldId);
		LocalCopyRecord record = LocalCopyRecord.read(folder).filter(LocalCopyRecord::hasUnsavedProgress)
				.orElseThrow(() -> new IOException("No Pending Upload of " + worldId + " on this computer"));
		if (!record.pendingUpload()) {
			// A session that ended without its final upload: from now on it is a Pending Upload.
			record = record.withPendingUpload(true);
			record.write(folder);
		}
		WorldMetadata current = cloud.readMetadata(worldId)
				.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
		if (current.revision() > record.revision()) {
			Backup unmerged = keepAsUnmergedBackup(worldId, record, progress);
			download(worldId, current, progress);
			return new RetryResult.KeptAsUnmergedBackup(unmerged);
		}
		Optional<Lock> held = currentLock(worldId);
		if (held.isEmpty()) held = takeLock(worldId, clock.instant(), false);
		if (held.isPresent() && !held.get().heldBy(me)) {
			throw new IOException(held.get().hostName() + " is hosting this world now, so your Pending Upload can't be uploaded");
		}
		heldLocks.add(worldId); // Kept since before a restart, or just taken: Heartbeats from now on.
		return new RetryResult.Uploaded(quit(worldId, progress));
	}

	/** Remembers in the Local Copy that it still has to be uploaded; {@code failure} is why. */
	private static void markPendingUpload(Path folder, Exception failure) {
		try {
			LocalCopyRecord record = LocalCopyRecord.read(folder).orElseThrow(() -> new IOException("The Local Copy's record is gone"));
			record.withPendingUpload(true).write(folder);
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
	 * nobody is kicked. {@code copier} is asked to save the open world and copy it into a folder
	 * this method then uploads and deletes.
	 *
	 * @return the new Current Save's metadata
	 */
	public WorldMetadata periodicUpload(String worldId, OpenWorldCopier copier, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path folder = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		try {
			copier.copyTo(folder);
			return upload(worldId, folder, false, progress);
		} finally {
			deleteRecursively(folder);
		}
	}

	/**
	 * The one upload path, shared by every kind of upload: makes {@code worldFolder} the Current Save
	 * at the next Revision, and records that Revision in the Local Copy. The previous Current Save
	 * becomes a Backup, and the oldest Backups are deleted beyond the configured count.
	 *
	 * @param worldFolder the world to upload, already saved to disk and not being written to: the
	 *                    Local Copy itself after the world has closed, or a copy of the open world
	 * @param endsSession whether this is the session's final upload: the Local Copy then no longer
	 *                    counts as having unsaved progress. A periodic upload leaves that as it is.
	 * @return the new Current Save's metadata
	 */
	private WorldMetadata upload(String worldId, Path worldFolder, boolean endsSession, ProgressListener progress) throws IOException {
		Files.createDirectories(localCopies);
		Path zip = Files.createTempFile(localCopies, STAGING_PREFIX, ".zip");
		try {
			WorldZip.zip(worldFolder, zip, progress);
			long size = Files.size(zip);
			WorldMetadata previous = cloud.readMetadata(worldId)
					.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
			ensureStillHost(worldId, previous);

			WorldMetadata next = new WorldMetadata(previous.revision() + 1, clock.instant());
			cloud.replaceCurrentSave(worldId, zip, next, BackupLabel.of(previous),
					sent -> progress.onProgress(ProgressListener.Step.UPLOADING, sent, size));
			Path localFolder = localCopies.resolve(worldId);
			LocalCopyRecord uploaded = LocalCopyRecord.read(localFolder).orElse(new LocalCopyRecord(next.revision()))
					.withRevision(next.revision()).withPendingUpload(false);
			if (endsSession) uploaded = uploaded.withSessionStartedAt(Optional.empty());
			uploaded.write(localFolder);
			pruneBackups(worldId);
			return next;
		} finally {
			Files.deleteIfExists(zip);
		}
	}

	/**
	 * The guard on every upload: never upload over a newer Revision. Throws unless this player still
	 * holds the Lock (a friend may have Taken Over while this game hung or lost its connection) and
	 * the Current Save is still the Revision this Local Copy came from or last uploaded.
	 */
	private void ensureStillHost(String worldId, WorldMetadata current) throws IOException {
		Optional<Lock> held = currentLock(worldId);
		if (held.isEmpty() || !held.get().heldBy(me)) {
			throw new IOException(held.map(lock -> lock.hostName() + " Took Over").orElse("Your Lock is gone")
					+ ", so this upload won't replace the Shared World; your progress is kept on this computer");
		}
		Optional<LocalCopyRecord> local = LocalCopyRecord.read(localCopies.resolve(worldId));
		if (local.isPresent() && current.revision() > local.get().revision()) {
			throw new IOException("Someone uploaded Revision " + current.revision() + " since, so this upload won't overwrite it");
		}
	}

	/**
	 * Heartbeat: refreshes this player's Lock so friends see they are still hosting. The game calls it
	 * every minute while hosting. Only ever updates this player's Lock in place: if it is gone (a
	 * friend Took Over after it went Stale), even while this Heartbeat is being sent, it is not put
	 * back.
	 *
	 * @return whether this player still holds the Lock that counts (the earliest)
	 */
	public boolean heartbeat(String worldId) throws IOException {
		Optional<Lock> current = currentLock(worldId);
		if (current.isEmpty() || !current.get().heldBy(me)) return false;
		return cloud.refreshLock(worldId, current.get().heartbeatAt(clock.instant()));
	}

	/**
	 * Sends a Heartbeat for every Lock this player holds, from the moment Play (or a retry, or a
	 * restore) takes it until it is released: through the download, the hosting session and the
	 * final upload, and on while a Pending Upload keeps it. The game calls this once a minute on a
	 * thread of its own, so a long download or upload doesn't hold the Heartbeats up.
	 *
	 * Also retries releasing any Lock {@link #quit} couldn't release after its upload.
	 *
	 * @return the worlds whose Lock this player has lost (a friend Took Over); each is reported once
	 * @throws IOException if a Heartbeat or release couldn't be sent; the others are still tried
	 */
	public List<String> heartbeatHeldLocks() throws IOException {
		List<String> lost = new ArrayList<>();
		IOException failure = null;
		for (String worldId : List.copyOf(locksToRelease)) {
			try {
				synchronized (locksToRelease) {
					if (locksToRelease.contains(worldId)) {
						cloud.deleteLock(worldId, me.id());
						locksToRelease.remove(worldId);
					}
				}
			} catch (IOException e) {
				if (failure == null) failure = e;
				else failure.addSuppressed(e);
			}
		}
		for (String worldId : List.copyOf(heldLocks)) {
			try {
				// A Lock released meanwhile is no longer in the set: not lost, just let go.
				if (!heartbeat(worldId) && heldLocks.remove(worldId)) lost.add(worldId);
			} catch (IOException e) {
				if (failure == null) failure = e;
				else failure.addSuppressed(e);
			}
		}
		if (failure != null) throw failure;
		return lost;
	}

	/** A Shared World's Backups, newest first. */
	public List<Backup> backups(String worldId) throws IOException {
		List<Backup> backups = new ArrayList<>(cloud.listBackups(worldId));
		backups.sort(Comparator.comparing(Backup::savedAt).reversed());
		return backups;
	}

	/**
	 * Restores a Backup: the previous Current Save becomes a Backup, so the restore can be undone,
	 * and the chosen Backup becomes the Current Save at the next Revision, so every player's next
	 * Play downloads it. Refused, changing nothing, while anyone holds a Lock, fresh or Stale: a
	 * Host's later upload would replace the restored world, and a crashed Host's progress would be
	 * lost from view.
	 *
	 * <p>The restore holds the Lock while it works, taken like Play's (earliest Lock wins), so
	 * nobody starts hosting halfway through; it releases it when done.
	 */
	public RestoreResult restoreBackup(String worldId, String backupId) throws IOException {
		Optional<Lock> held = currentLock(worldId);
		if (held.isPresent()) return new RestoreResult.Refused(held.get());
		Optional<Lock> winner = takeLock(worldId, clock.instant(), false);
		if (winner.isPresent()) return new RestoreResult.Refused(winner.get());
		try {
			WorldMetadata previous = cloud.readMetadata(worldId)
					.orElseThrow(() -> new IOException("Shared World " + worldId + " has no Current Save"));
			WorldMetadata next = new WorldMetadata(previous.revision() + 1, clock.instant());
			Backup replaced = cloud.restoreBackup(worldId, backupId, next, BackupLabel.of(previous));
			pruneBackups(worldId);
			return new RestoreResult.Restored(next, replaced);
		} finally {
			releaseLock(worldId);
		}
	}

	/**
	 * Deletes the oldest Backups until only the configured count remain. Unmerged Backups don't count
	 * and are never deleted: each holds a crashed session's progress that exists nowhere else, and
	 * they are rare. Best effort: the new Current Save is already in place, so a failure here only
	 * leaves extra Backups, which the next upload prunes.
	 */
	private void pruneBackups(String worldId) {
		try {
			List<Backup> newestFirst = backups(worldId).stream().filter(backup -> backup.unmerged().isEmpty()).toList();
			int keep = settings.get().backupCount();
			for (Backup old : newestFirst.subList(Math.min(keep, newestFirst.size()), newestFirst.size())) {
				cloud.deleteBackup(worldId, old.id());
			}
		} catch (IOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete old Backups of " + worldId + "; will retry after the next upload", e);
		}
	}

	private void releaseLock(String worldId) throws IOException {
		// Forgotten first, so a Heartbeat racing this release can't report the Lock as lost.
		heldLocks.remove(worldId);
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
		return sharedWorld(world, metadata, status, false);
	}

	private static SharedWorld sharedWorld(CloudWorld world, WorldMetadata metadata, WorldStatus status, boolean pendingUpload) {
		return new SharedWorld(world.id(), world.name(), metadata.revision(), metadata.savedAt(), status, pendingUpload);
	}

	/**
	 * Puts {@code newCopy} where the Local Copy at {@code folder} is. The old one is moved aside
	 * first, put back if the new one can't be moved in, and deleted only once the new one is in
	 * place, so a failure never loses it.
	 */
	private void swapIn(Path newCopy, Path folder) throws IOException {
		Path aside = Files.createTempDirectory(localCopies, STAGING_PREFIX);
		Path old = aside.resolve("old");
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
			// After a failed swap, anything still in there is the only old copy.
			if (swapped || Files.notExists(old)) deleteLeftover(aside);
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

	/** Best effort: nothing depends on it, so a failure only leaves a folder behind. */
	private static void deleteLeftover(Path folder) {
		try {
			deleteRecursively(folder);
		} catch (IOException | UncheckedIOException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Couldn't delete " + folder, e);
		}
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
