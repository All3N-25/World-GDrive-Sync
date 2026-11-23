package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

	private final CloudStore cloud;
	private final InstantSource clock;
	private final Path localCopies;

	/**
	 * @param cloud       where Shared Worlds are stored
	 * @param clock       the time source for every timestamp the coordinator records
	 * @param localCopies the Local Copy directory ({@code sharedsaves/}, ADR-0002); one folder per
	 *                    Shared World, named by its id
	 */
	public SessionCoordinator(CloudStore cloud, InstantSource clock, Path localCopies) {
		this.cloud = cloud;
		this.clock = clock;
		this.localCopies = localCopies;
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
			result.add(sharedWorld(world, metadata.get()));
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
			return sharedWorld(world, metadata);
		} finally {
			Files.deleteIfExists(zip);
			deleteRecursively(staging);
		}
	}

	private static SharedWorld sharedWorld(CloudWorld world, WorldMetadata metadata) {
		return new SharedWorld(world.id(), world.name(), metadata.revision(), metadata.savedAt(), WorldStatus.FREE);
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
