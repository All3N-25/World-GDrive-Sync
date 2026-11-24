package net.aii3n.worldgdrivesync.session;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where this game is in hosting a Shared World, from Play to save-and-quit, and what that calls for.
 * The game feeds it what happens (Play opened a world, the game started or stopped running it, a
 * tick) and does what it answers. No game types, so it can be tested on its own; the game's
 * {@code HostingSession} wires it to the game's events.
 *
 * <p>States: {@link #opened} records the world being opened; the game starting to run that folder
 * makes it hosted; the game stopping it (the world is saved and closed) makes the final upload due,
 * which a tick with no world shown then asks for. Thread-safe: events arrive on more than one of the
 * game's threads.
 */
public final class HostingSessionState {
	/** What the game should do after an event. */
	public sealed interface Action {
		/** Nothing to do. */
		record Nothing() implements Action {
		}

		/** The world has closed: show the final upload of {@code worldId}. */
		record FinalUpload(String worldId) implements Action {
		}

		/** Play's world never loaded: release the Lock on {@code worldId} (leave without playing). */
		record ReleaseLock(String worldId) implements Action {
		}
	}

	private static final Action NOTHING = new Action.Nothing();

	private record Session(String worldId, Path folder) {
	}

	private Session opening;
	private Session hosting;
	private Session finalUploadDue;

	/** Play is opening {@code worldId}'s Local Copy in {@code folder}. */
	public synchronized void opened(String worldId, Path folder) {
		opening = new Session(worldId, normalize(folder));
	}

	/** The player backed out of a prompt before {@code worldId} loaded; the caller releases the Lock. */
	public synchronized void cancelled(String worldId) {
		if (opening != null && opening.worldId().equals(worldId)) opening = null;
	}

	/** The game started running the world in {@code folder}. */
	public synchronized Action worldStarted(Path folder) {
		if (opening != null && opening.folder().equals(normalize(folder))) {
			hosting = opening;
			opening = null;
		}
		return NOTHING;
	}

	/** The game stopped running the world, after saving and closing it, in {@code folder}. */
	public synchronized Action worldStopped(Path folder) {
		if (opening != null && opening.folder().equals(normalize(folder))) {
			// Stopped before it ever started: it failed to load, so nothing was played.
			String worldId = opening.worldId();
			opening = null;
			return new Action.ReleaseLock(worldId);
		}
		if (hosting != null) {
			finalUploadDue = hosting;
			hosting = null;
		}
		return NOTHING;
	}

	/**
	 * A game tick.
	 *
	 * @param worldShown   the game shows a world (it is loaded on screen)
	 * @param worldRunning the game is running a world of its own (an integrated server)
	 * @param onTitleScreen the title screen is showing
	 */
	public synchronized Action tick(boolean worldShown, boolean worldRunning, boolean onTitleScreen) {
		if (opening != null && onTitleScreen && !worldShown && !worldRunning) {
			// Play's loading screens never lead back here: the game gave up loading (e.g. an unreadable world).
			String worldId = opening.worldId();
			opening = null;
			return new Action.ReleaseLock(worldId);
		}
		if (finalUploadDue == null || worldShown || worldRunning) return NOTHING;
		String worldId = finalUploadDue.worldId();
		finalUploadDue = null;
		return new Action.FinalUpload(worldId);
	}

	/** The Shared World this player is hosting right now, if any. */
	public synchronized Optional<String> hostedWorldId() {
		return Optional.ofNullable(hosting).map(Session::worldId);
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}
}
