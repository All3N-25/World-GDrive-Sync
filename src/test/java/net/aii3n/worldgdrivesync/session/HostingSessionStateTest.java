package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.session.HostingSessionState.Action;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostingSessionStateTest {
	private static final Path FOLDER = Path.of("sharedsaves", "world-1");
	private static final Action NOTHING = new Action.Nothing();

	private final HostingSessionState state = new HostingSessionState();

	@Test
	void savingAndQuittingAsksForTheFinalUploadOnceTheWorldHasClosed() {
		state.opened("world-1", FOLDER);
		state.worldStarted(FOLDER);
		assertEquals(Optional.of("world-1"), state.hostedWorldId());

		state.worldStopped(FOLDER);

		assertEquals(Optional.empty(), state.hostedWorldId());
		assertEquals(NOTHING, state.tick(true, false, false), "not while the client still shows the world");
		assertEquals(new Action.FinalUpload("world-1"), state.tick(false, false, false));
		assertEquals(NOTHING, state.tick(false, false, false), "only once");
	}

	@Test
	void aLoadThatGivesUpOnTheTitleScreenReleasesTheLock() {
		state.opened("world-1", FOLDER);

		assertEquals(NOTHING, state.tick(false, false, false), "still on Play's loading screens");
		assertEquals(new Action.ReleaseLock("world-1"), state.tick(false, false, true));
		assertEquals(NOTHING, state.tick(false, false, true), "only once");
		assertEquals(Optional.empty(), state.hostedWorldId());
	}

	@Test
	void aWorldThatStopsBeforeItStartedReleasesTheLockInsteadOfUploading() {
		state.opened("world-1", FOLDER);

		assertEquals(new Action.ReleaseLock("world-1"), state.worldStopped(FOLDER));

		assertEquals(NOTHING, state.tick(false, false, true), "no final upload, and released only once");
	}
}
