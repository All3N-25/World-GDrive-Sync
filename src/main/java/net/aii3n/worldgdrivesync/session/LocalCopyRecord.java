package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.io.AtomicFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * What a Local Copy remembers about itself, kept in a small file inside the world folder so it
 * moves and is replaced together with the world. Never zipped (see {@link WorldZip}).
 *
 * @param revision         the Revision this copy came from (or was last uploaded as)
 * @param pendingUpload    a final upload of this copy failed and still has to happen
 * @param sessionStartedAt set while a hosting session of this copy is open: from Play until the
 *                         final upload succeeds. Still set when the game starts again means the
 *                         session crashed (or the game was closed), so the copy may hold progress
 *                         the cloud doesn't have, even if its Revision matches the cloud's (a
 *                         periodic upload updates the Revision mid-session).
 * @param sentUpload       the last upload of this copy that was sent and not answered. A Current
 *                         Save carrying exactly what it was sent as is that upload, stored although
 *                         its answer was lost (see {@link #confirmedBy}).
 *
 * <p>Listing Shared Worlds reads records while an upload or download replaces them on another
 * thread. {@link #read} and {@link #write} take turns on this class's monitor, because Windows
 * refuses to rename over, or delete the folder of, a file that is open. Take it too before
 * replacing an existing Local Copy's folder.
 */
record LocalCopyRecord(int revision, boolean pendingUpload, Optional<Instant> sessionStartedAt,
		Optional<SentUpload> sentUpload) {
	static final String FILE_NAME = "world-gdrive-sync.properties";

	/**
	 * An upload that was started.
	 *
	 * @param as       the metadata it was sent with
	 * @param contents the {@link WorldZip#contentsDigest} of what was sent
	 */
	record SentUpload(WorldMetadata as, String contents) {
	}

	/** A record with nothing waiting to be uploaded and no open session. */
	LocalCopyRecord(int revision) {
		this(revision, false, Optional.empty(), Optional.empty());
	}

	/** Whether this copy may hold progress the cloud doesn't have: a crashed session or a Pending Upload. */
	boolean hasUnsavedProgress() {
		return pendingUpload || sessionStartedAt.isPresent();
	}

	/**
	 * This record, knowing that {@code current} describes the Current Save: if that is the upload
	 * this copy sent and never heard back about, the copy is at its Revision, not behind it.
	 */
	LocalCopyRecord confirmedBy(WorldMetadata current) {
		return sent(current) ? withRevision(current.revision()) : this;
	}

	/**
	 * Whether {@code current}, describing the Current Save, is an upload this copy sent and never
	 * heard back about, and {@code worldFolder} (this copy's) hasn't changed since: the cloud then
	 * holds all of it.
	 */
	boolean isStoredAs(WorldMetadata current, Path worldFolder) throws IOException {
		return sent(current) && sentUpload.orElseThrow().contents().equals(WorldZip.contentsDigest(worldFolder));
	}

	private boolean sent(WorldMetadata current) {
		return sentUpload.map(SentUpload::as).equals(Optional.of(current));
	}

	LocalCopyRecord withRevision(int revision) {
		return new LocalCopyRecord(revision, pendingUpload, sessionStartedAt, sentUpload);
	}

	LocalCopyRecord withPendingUpload(boolean pendingUpload) {
		return new LocalCopyRecord(revision, pendingUpload, sessionStartedAt, sentUpload);
	}

	LocalCopyRecord withSessionStartedAt(Optional<Instant> sessionStartedAt) {
		return new LocalCopyRecord(revision, pendingUpload, sessionStartedAt, sentUpload);
	}

	LocalCopyRecord withSentUpload(Optional<SentUpload> sentUpload) {
		return new LocalCopyRecord(revision, pendingUpload, sessionStartedAt, sentUpload);
	}

	static synchronized Optional<LocalCopyRecord> read(Path worldFolder) {
		Path file = worldFolder.resolve(FILE_NAME);
		if (!Files.isRegularFile(file)) return Optional.empty();
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(in);
			return Optional.of(new LocalCopyRecord(Integer.parseInt(properties.getProperty("revision")),
					Boolean.parseBoolean(properties.getProperty("pendingUpload")),
					Optional.ofNullable(properties.getProperty("sessionStartedAt")).map(Instant::parse),
					Optional.ofNullable(properties.getProperty("sentRevision")).map(sent ->
							new SentUpload(new WorldMetadata(Integer.parseInt(sent), Instant.parse(properties.getProperty("sentSavedAt"))),
									properties.getProperty("sentContents", "")))));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	void write(Path worldFolder) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("revision", Integer.toString(revision));
		if (pendingUpload) properties.setProperty("pendingUpload", "true");
		sessionStartedAt.ifPresent(startedAt -> properties.setProperty("sessionStartedAt", startedAt.toString()));
		sentUpload.ifPresent(sent -> {
			properties.setProperty("sentRevision", Integer.toString(sent.as().revision()));
			properties.setProperty("sentSavedAt", sent.as().savedAt().toString());
			properties.setProperty("sentContents", sent.contents());
		});
		synchronized (LocalCopyRecord.class) {
			// Atomic: a half-written record reads as no record, and Play would then replace the Local Copy.
			AtomicFile.write(worldFolder.resolve(FILE_NAME),
					out -> properties.store(out, "World GDrive Sync: what this Local Copy came from"));
		}
	}
}
