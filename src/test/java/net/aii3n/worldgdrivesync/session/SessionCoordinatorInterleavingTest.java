package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Plays out random sessions of two or three players sharing one world, through {@link
 * SessionCoordinator}'s public interface, and checks the Shared World rules after every step.
 * Each run has a fixed seed; a failure names the broken invariant, the seed and every step taken.
 *
 * <p>Replay one run with {@code ./gradlew test --tests '*Interleaving*' -Dwgds.interleavings.seed=<seed>}.
 * Run more with {@code -Dwgds.interleavings.runs=<n>} (and {@code .actions=<n>} per run).
 *
 * <h2>The model</h2>
 * Each player's world content is a {@code progress.txt} file in their Local Copy, one unique marker
 * per line; every "play a while" step adds a marker, so progress is checkable. Each player's game has
 * a Heartbeat thread that beats every simulated minute, as the real one does, unless the game is
 * frozen (hung) or closed. Steps run in the middle of other steps' cloud calls ({@link
 * InMemoryCloudStore#beforeNext}): time passing during a download or upload, the caller's game
 * freezing, and another player pressing Play.
 *
 * <p>A player <em>believes they are Host</em> while their world is open (or its final upload is
 * running), they haven't been told their Lock was lost, and they are in contact: their game hasn't
 * frozen or failed a Heartbeat since the last Heartbeat that went through. A game out of contact
 * can't know a friend Took Over, so it doesn't count.
 *
 * <h2>The accepted gap</h2>
 * An upload (or restore) checks the Lock, then writes. A Takeover can land in between only if the
 * Host's game froze for longer than the stale timeout right there; the write then still lands. This
 * test lets that happen and checks it only costs the new Host's session an Unmerged Backup: the
 * Revision invariants and "no progress lost" still hold, and invariant 5 is waived for a restore
 * whose game froze during it.
 */
class SessionCoordinatorInterleavingTest {
	private static final int RUNS = Integer.getInteger("wgds.interleavings.runs", 500);
	private static final int ACTIONS = Integer.getInteger("wgds.interleavings.actions", 50);
	private static final Long ONE_SEED = Long.getLong("wgds.interleavings.seed");
	private static final long FIRST_SEED = 20_261_127L;

	private static final Instant START = Instant.parse("2026-11-27T18:00:00Z");
	private static final Settings SETTINGS = Settings.DEFAULTS.withPeriodicUploadInterval(Duration.ofMinutes(10)).withBackupCount(2);
	private static final String PROGRESS = "progress.txt";
	/** The cloud calls steps run in the middle of. Not Lock reads, which nearly every step makes. */
	private static final InMemoryCloudStore.Call[] INTERLEAVED = {InMemoryCloudStore.Call.LOCK_WRITE,
			InMemoryCloudStore.Call.RESTORE, InMemoryCloudStore.Call.DOWNLOAD, InMemoryCloudStore.Call.REPLACE};
	private static final List<Player> PLAYERS = List.of(
			new Player("uuid-alice", "Alice"), new Player("uuid-bob", "Bob"), new Player("uuid-carol", "Carol"));

	@Test
	void theSharedWorldRulesHoldWhateverThePlayersDoAndWhateverFails(@TempDir Path root) throws IOException {
		if (ONE_SEED != null) {
			new Run(ONE_SEED, root.resolve("replay")).play();
			return;
		}
		for (int i = 0; i < RUNS; i++) {
			Path dir = root.resolve("run-" + i);
			new Run(FIRST_SEED + i, dir).play();
			deleteRecursively(dir);
		}
	}

	@FunctionalInterface
	private interface Step<T> {
		T run() throws IOException;
	}

	/** One player's computer. */
	private static final class Sim {
		final Player player;
		final Path sharedsaves;
		/** The game, while it is running. */
		SessionCoordinator game;
		boolean inWorld;
		boolean quitting;
		boolean frozen;
		/** The last Heartbeat went through and nothing since put the game out of contact. */
		boolean inContact;
		/** The Heartbeat thread reported the Lock lost (the game showed its toast). */
		boolean toldLost;
		/** Goes up each time the game freezes or a Heartbeat fails. */
		int contactLosses;
		/** Goes up each time a friend Takes Over this player's Lock. */
		int takenOver;
		int markers;

		Sim(Player player, Path sharedsaves) {
			this.player = player;
			this.sharedsaves = sharedsaves;
		}

		boolean believesHost() {
			return game != null && (inWorld || quitting) && !frozen && inContact && !toldLost;
		}

		String name() {
			return player.name();
		}
	}

	private static final class Run {
		final long seed;
		final Random random;
		final Path dir;
		final InMemoryCloudStore cloud = new InMemoryCloudStore();
		final TestClock clock = new TestClock(START);
		final List<Sim> sims = new ArrayList<>();
		final List<String> log = new ArrayList<>();
		final Deque<Sim> acting = new ArrayDeque<>();
		/** Every marker any player ever wrote into their world. */
		final Set<String> written = new LinkedHashSet<>();
		final Map<String, Set<String>> unmergedBackupMarkers = new HashMap<>();
		String worldId;
		boolean uploadsFail;
		boolean downloadsFail;
		boolean connectionDown;
		boolean backupDeletesFail;
		/** Revisions restored while the restoring game froze: the accepted gap, exempt from invariant 5. */
		final Set<Integer> restoredDuringFreeze = new LinkedHashSet<>();
		/** In the final settling phase, where nothing interleaves. */
		boolean settling;
		/**
		 * Inside a call that takes seconds (writing a Lock, restoring): whatever interleaves with it,
		 * however deeply nested, takes no time either, or that call would have taken minutes.
		 */
		boolean inFastCall;

		Run(long seed, Path dir) {
			this.seed = seed;
			this.random = new Random(seed);
			this.dir = dir;
		}

		void play() throws IOException {
			int players = 2 + random.nextInt(2);
			for (int i = 0; i < players; i++) {
				Sim sim = new Sim(PLAYERS.get(i), dir.resolve(PLAYERS.get(i).name().toLowerCase()));
				sim.game = newGame(sim);
				sims.add(sim);
			}
			Path original = dir.resolve("saves/Survival");
			Files.createDirectories(original);
			Files.writeString(original.resolve("level.dat"), "level");
			Files.writeString(original.resolve(PROGRESS), "");
			worldId = sims.getFirst().game.share(original, "Survival", ProgressListener.NONE).id();
			log("%s shares the world", sims.getFirst().name());

			for (int i = 0; i < ACTIONS; i++) {
				if (random.nextInt(4) == 0) scheduleInterleaving();
				act();
				checkInvariants();
			}
			settle();
		}

		SessionCoordinator newGame(Sim sim) {
			return new SessionCoordinator(cloud, tickingClock(), sim.sharedsaves, sim.player, () -> SETTINGS);
		}

		/**
		 * The shared clock, moved on a microsecond at each reading, as real clocks are: a Local Copy
		 * knows its own upload by the Revision and save time it was sent with, so two players' uploads
		 * must never share an instant.
		 */
		SessionClock tickingClock() {
			return new SessionClock() {
				@Override
				public Instant instant() {
					clock.advance(Duration.ofNanos(1_000));
					return clock.instant();
				}

				@Override
				public void sleep(Duration duration) {
				}
			};
		}

		// --- Steps ---

		void act() throws IOException {
			Sim sim = sims.get(random.nextInt(sims.size()));
			if (sim.game == null) {
				relaunch(sim);
				return;
			}
			if (sim.frozen) {
				unfreeze(sim);
				return;
			}
			int roll = random.nextInt(100);
			if (roll < 15) {
				passTime(1 + random.nextInt(12));
			} else if (roll < 23) {
				toggleFailure();
			} else if (roll < 27) {
				heartbeat(sim);
			} else if (roll < 31) {
				freeze(sim);
			} else if (roll < 35) {
				crash(sim);
			} else if (sim.inWorld) {
				if (roll < 65) playAWhile(sim);
				else if (roll < 80) periodicUpload(sim);
				else quit(sim);
			} else {
				if (roll < 75) pressPlay(sim);
				else if (roll < 88) retry(sim);
				else restore(sim);
			}
		}

		void relaunch(Sim sim) {
			sim.game = newGame(sim);
			sim.inWorld = sim.quitting = sim.frozen = sim.inContact = sim.toldLost = false;
			log("%s starts the game", sim.name());
			List<String> pending = attempt(sim, "lists Pending Uploads", () -> sim.game.pendingUploads());
			if (pending == null) return;
			for (String id : pending) attempt(sim, "retries the Pending Upload on launch", () -> sim.game.retryPendingUpload(id, ProgressListener.NONE));
		}

		void crash(Sim sim) {
			sim.game = null;
			sim.inWorld = sim.quitting = sim.frozen = false;
			log("%s's game crashes (or is closed)", sim.name());
		}

		void freeze(Sim sim) {
			sim.frozen = true;
			sim.inContact = false;
			sim.contactLosses++;
			log("%s's game freezes", sim.name());
		}

		void unfreeze(Sim sim) {
			sim.frozen = false;
			log("%s's game unfreezes", sim.name());
		}

		void passTime(int minutes) {
			log("%d minutes pass", minutes);
			for (int i = 0; i < minutes; i++) {
				clock.advance(Duration.ofMinutes(1));
				for (Sim sim : sims) if (sim.game != null && !sim.frozen) heartbeat(sim);
			}
		}

		/** One run of the game's Heartbeat thread. Logged only when it fails or loses a Lock. */
		void heartbeat(Sim sim) {
			int contactLosses = sim.contactLosses;
			List<String> lost = attempt(sim, "Heartbeat", () -> sim.game.heartbeatHeldLocks(), result -> !result.isEmpty());
			if (lost == null) {
				sim.inContact = false;
				sim.contactLosses++;
				return;
			}
			// A Heartbeat the game froze in the middle of went out with the time it started at.
			sim.inContact = sim.contactLosses == contactLosses;
			if (lost.contains(worldId)) sim.toldLost = true;
		}

		void toggleFailure() {
			switch (random.nextInt(5)) {
				case 0 -> {
					uploadsFail = !uploadsFail;
					if (uploadsFail) cloud.failUploads();
					else cloud.uploadsWork();
					log("uploads %s", uploadsFail ? "start failing" : "work again");
				}
				case 1 -> {
					downloadsFail = !downloadsFail;
					if (downloadsFail) cloud.failDownloads();
					else cloud.downloadsWork();
					log("downloads %s", downloadsFail ? "start failing" : "work again");
				}
				case 2 -> setConnection(!connectionDown);
				case 3 -> {
					backupDeletesFail = !backupDeletesFail;
					if (backupDeletesFail) cloud.failBackupDeletes();
					else cloud.backupDeletesWork();
					log("deleting old Backups %s", backupDeletesFail ? "starts failing" : "works again");
				}
				default -> {
					cloud.dropConnectionAfterNextReplace();
					log("the next upload's answer will be lost");
				}
			}
		}

		void setConnection(boolean down) {
			connectionDown = down;
			if (down) {
				cloud.failMetadataReads();
				cloud.failLockReads();
				cloud.failLockDeletes();
				cloud.failLockWrites();
			} else {
				cloud.metadataReadsWork();
				cloud.lockReadsWork();
				cloud.lockDeletesWork();
				cloud.lockWritesWork();
			}
			log("the connection %s", down ? "drops" : "is back");
		}

		void playAWhile(Sim sim) throws IOException {
			String marker = sim.name() + "-" + ++sim.markers;
			Files.writeString(localFolder(sim).resolve(PROGRESS), marker + "\n", StandardOpenOption.APPEND);
			written.add(marker);
			log("%s plays a while (%s)", sim.name(), marker);
		}

		void periodicUpload(Sim sim) {
			Boolean due = attempt(sim, "checks if a periodic upload is due", () -> sim.game.periodicUploadDue(worldId));
			if (Boolean.TRUE.equals(due) || random.nextInt(4) == 0) {
				attempt(sim, "periodic upload", () -> sim.game.periodicUpload(worldId,
						folder -> WorldZip.copyForUpload(localFolder(sim), folder), ProgressListener.NONE));
			}
		}

		void quit(Sim sim) {
			sim.inWorld = false;
			sim.quitting = true;
			try {
				attempt(sim, "saves and quits", () -> sim.game.quit(worldId, ProgressListener.NONE));
			} finally {
				sim.quitting = false;
			}
		}

		void retry(Sim sim) {
			List<String> pending = attempt(sim, "lists Pending Uploads", () -> sim.game.pendingUploads());
			if (pending != null && pending.contains(worldId)) {
				attempt(sim, "presses Retry", () -> sim.game.retryPendingUpload(worldId, ProgressListener.NONE));
			}
		}

		void pressPlay(Sim sim) {
			int contactLosses = sim.contactLosses;
			PlayResult result = attempt(sim, "presses Play", () -> sim.game.play(worldId, ProgressListener.NONE));
			if (result instanceof PlayResult.Stale stale && random.nextBoolean()) {
				Sim holder = sim(stale.heldBy().hostId());
				result = attempt(sim, "Takes Over from " + holder.name(), () -> sim.game.takeOver(worldId, ProgressListener.NONE));
				if (!holdsLock(holder)) holder.takenOver++;
			}
			if (!(result instanceof PlayResult.Ready)) return;
			sim.inContact = sim.contactLosses == contactLosses;
			sim.toldLost = false;
			if (random.nextInt(5) == 0) {
				attempt(sim, "backs out before the world loads", () -> {
					sim.game.leaveWithoutPlaying(worldId);
					return null;
				});
			} else {
				sim.inWorld = true;
				log("%s's world is open", sim.name());
			}
		}

		void restore(Sim sim) {
			List<Backup> backups = attempt(sim, "opens Backups", () -> sim.game.backups(worldId));
			if (backups == null || backups.isEmpty()) return;
			Backup chosen = backups.get(random.nextInt(backups.size()));
			int contactLosses = sim.contactLosses;
			RestoreResult result = attempt(sim, "restores " + chosen.id(), () -> sim.game.restoreBackup(worldId, chosen.id()));
			// The accepted gap: see the class comment.
			if (result instanceof RestoreResult.Restored restored && sim.contactLosses != contactLosses) {
				restoredDuringFreeze.add(restored.current().revision());
			}
		}

		/** Something that happens in the middle of a cloud call. */
		void scheduleInterleaving() {
			InMemoryCloudStore.Call call = INTERLEAVED[random.nextInt(INTERLEAVED.length)];
			boolean freezeCaller = random.nextInt(3) == 0;
			// Only a download or an upload takes long; writing a Lock or restoring takes seconds, unless the game froze.
			boolean slow = freezeCaller || call == InMemoryCloudStore.Call.DOWNLOAD || call == InMemoryCloudStore.Call.REPLACE;
			int minutes = random.nextInt(11);
			boolean friendPlays = random.nextBoolean();
			cloud.beforeNext(call, () -> {
				if (settling) return;
				Sim caller = acting.peek();
				log("--- during %s's %s:", caller == null ? "someone" : caller.name(), call);
				boolean outerFast = inFastCall;
				boolean froze = freezeCaller && !outerFast && caller != null && !caller.frozen;
				if (froze) freeze(caller);
				inFastCall = outerFast || !slow;
				try {
					passTime(inFastCall ? 0 : minutes);
					if (friendPlays) {
						List<Sim> idle = sims.stream().filter(s -> s.game != null && !s.frozen && !s.inWorld && !s.quitting && !acting.contains(s)).toList();
						if (!idle.isEmpty()) pressPlay(idle.get(random.nextInt(idle.size())));
					}
					checkInvariants();
				} finally {
					inFastCall = outerFast;
				}
				if (froze) unfreeze(caller);
				log("--- back to the %s", call);
			});
		}

		/**
		 * Runs one player's call, logging it. An {@link IOException} is the call failing, which the
		 * game survives: it is logged and null returned. Afterwards checks invariant 4 for that player.
		 */
		<T> T attempt(Sim sim, String what, Step<T> step) {
			return attempt(sim, what, step, result -> true);
		}

		<T> T attempt(Sim sim, String what, Step<T> step, Predicate<T> worthLogging) {
			boolean heldBefore = holdsLock(sim);
			int takenOverBefore = sim.takenOver;
			acting.push(sim);
			T result;
			String outcome;
			try {
				result = step.run();
				outcome = worthLogging.test(result) ? String.valueOf(result) : null;
			} catch (IOException e) {
				result = null;
				outcome = "failed (" + e.getMessage() + ")";
			} catch (RuntimeException e) {
				throw failure("unexpected exception from " + sim.name() + " " + what + ": " + e, e);
			} finally {
				acting.pop();
			}
			if (outcome != null) log("%s %s: %s", sim.name(), what, outcome);
			if (heldBefore && !holdsLock(sim) && sim.takenOver == takenOverBefore) checkReleaseKeepsProgressSafe(sim, what);
			return result;
		}

		// --- Invariants ---

		void checkInvariants() {
			checkRevisions();
			checkOneHost();
			checkNoProgressLost(false);
			checkRestores();
		}

		/**
		 * 5. A restore never happens while any Lock exists: when a Backup became the Current Save, the
		 * only Lock was the restoring player's own, taken for the restore.
		 */
		void checkRestores() {
			for (InMemoryCloudStore.CurrentSave save : cloud.currentSaveHistory(worldId)) {
				if (save.change() == InMemoryCloudStore.Change.RESTORED && save.lockHolderIds().size() != 1
						&& !restoredDuringFreeze.contains(save.revision())) {
					fail(5, "Revision " + save.revision() + " was restored while these players held Locks: " + save.lockHolderIds());
				}
			}
		}

		/** 1. The Revision never goes down, and no upload replaces a Revision it wasn't based on. */
		void checkRevisions() {
			List<InMemoryCloudStore.CurrentSave> history = cloud.currentSaveHistory(worldId);
			for (int i = 1; i < history.size(); i++) {
				InMemoryCloudStore.CurrentSave previous = history.get(i - 1), next = history.get(i);
				if (next.revision() <= previous.revision()) {
					fail(1, "Revision " + previous.revision() + " was followed by Revision " + next.revision());
				}
				if (next.change() == InMemoryCloudStore.Change.REPLACED && !markers(next.entries()).containsAll(markers(previous.entries()))) {
					fail(1, "Revision " + previous.revision() + " " + markers(previous.entries()) + " was replaced by Revision "
							+ next.revision() + " " + markers(next.entries()) + ", an upload not based on it");
				}
			}
		}

		/** 2. At most one player believes they are Host, and it's the holder of the earliest Lock. */
		void checkOneHost() {
			List<Sim> hosts = sims.stream().filter(Sim::believesHost).toList();
			if (hosts.size() > 1) fail(2, "several players believe they are Host: " + hosts.stream().map(Sim::name).toList());
			Optional<String> earliest = cloud.lockHolders(worldId).stream().findFirst();
			for (Sim host : hosts) {
				if (!earliest.equals(Optional.of(host.name()))) {
					fail(2, host.name() + " believes they are Host but the earliest Lock is " + earliest.orElse("nobody's"));
				}
			}
		}

		/**
		 * 3. No progress is silently lost: every marker is in a Current Save the world has had, in an
		 * Unmerged Backup, or (until {@code finished}) in a Local Copy that still has unsaved progress.
		 */
		void checkNoProgressLost(boolean finished) {
			Set<String> safe = safeMarkers();
			Set<String> stillLocal = new LinkedHashSet<>();
			if (!finished) for (Sim sim : sims) if (hasUnsavedProgress(sim)) stillLocal.addAll(localMarkers(sim));
			for (String marker : written) {
				if (!safe.contains(marker) && !stillLocal.contains(marker)) {
					fail(3, "progress " + marker + " is in no Current Save, no Unmerged Backup"
							+ (finished ? " (after everyone retried and played again)" : " and no Local Copy with unsaved progress"));
				}
			}
		}

		/**
		 * 4. A player's own call never releases their Lock while their Local Copy has unsaved progress,
		 * unless that progress is already safe, or the cloud has moved past it (it can then only ever
		 * become an Unmerged Backup, which needs no Lock).
		 */
		void checkReleaseKeepsProgressSafe(Sim sim, String what) {
			if (!hasUnsavedProgress(sim)) return;
			if (safeMarkers().containsAll(localMarkers(sim))) return;
			Optional<LocalCopy> local = new SessionCoordinator(cloud, clock, sim.sharedsaves, sim.player).localCopy(worldId);
			if (local.isPresent() && cloud.metadata(worldId).revision() > local.get().revision()) return;
			fail(4, sim.name() + " " + what + " released their Lock while their Local Copy holds unsaved progress " + localMarkers(sim));
		}

		Set<String> safeMarkers() {
			Set<String> safe = new LinkedHashSet<>();
			for (InMemoryCloudStore.CurrentSave save : cloud.currentSaveHistory(worldId)) safe.addAll(markers(save.entries()));
			try {
				for (Backup backup : cloud.listBackups(worldId)) {
					if (backup.unmerged().isEmpty()) continue;
					safe.addAll(unmergedBackupMarkers.computeIfAbsent(backup.id(), id -> markers(cloud.backupEntries(worldId, id))));
				}
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			return safe;
		}

		// --- Settling ---

		/**
		 * The connection comes back, every game is closed and started again (retrying its Pending
		 * Uploads), and anyone still holding unsaved progress plays again, until everything settles.
		 * Then all progress must be in a Current Save or an Unmerged Backup.
		 */
		void settle() throws IOException {
			settling = true;
			log("=== settling");
			if (uploadsFail) cloud.uploadsWork();
			if (downloadsFail) cloud.downloadsWork();
			if (connectionDown) setConnection(false);
			if (backupDeletesFail) cloud.backupDeletesWork();
			uploadsFail = downloadsFail = backupDeletesFail = false;
			for (Sim sim : sims) {
				if (sim.frozen) unfreeze(sim);
				if (sim.inWorld) quit(sim);
			}
			for (int round = 0; round < 4 && sims.stream().anyMatch(this::hasUnsavedProgress); round++) {
				passTime((int) SETTINGS.staleTimeout().toMinutes() + 1);
				for (Sim sim : sims) {
					crash(sim);
					relaunch(sim);
				}
				for (Sim sim : sims) {
					if (!hasUnsavedProgress(sim)) continue;
					PlayResult result = attempt(sim, "presses Play", () -> sim.game.play(worldId, ProgressListener.NONE));
					if (result instanceof PlayResult.Stale) result = attempt(sim, "Takes Over", () -> sim.game.takeOver(worldId, ProgressListener.NONE));
					if (result instanceof PlayResult.Ready) quit(sim);
				}
				checkInvariants();
			}
			checkNoProgressLost(true);
			for (Sim sim : sims) {
				if (hasUnsavedProgress(sim)) fail(3, sim.name() + "'s unsaved progress was never uploaded or kept as an Unmerged Backup");
			}
		}

		// --- Observing ---

		boolean hasUnsavedProgress(Sim sim) {
			try {
				return new SessionCoordinator(cloud, clock, sim.sharedsaves, sim.player).pendingUploads().contains(worldId);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}

		boolean holdsLock(Sim sim) {
			return cloud.lockHolders(worldId).contains(sim.name());
		}

		Path localFolder(Sim sim) {
			return sim.sharedsaves.resolve(worldId);
		}

		Set<String> localMarkers(Sim sim) {
			Path file = localFolder(sim).resolve(PROGRESS);
			try {
				return Files.exists(file) ? markers(Files.readString(file, StandardCharsets.UTF_8)) : Set.of();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}

		static Set<String> markers(Map<String, String> worldEntries) {
			return markers(worldEntries.getOrDefault(PROGRESS, ""));
		}

		static Set<String> markers(String progress) {
			Set<String> markers = new LinkedHashSet<>();
			for (String line : progress.split("\n")) if (!line.isBlank()) markers.add(line);
			return markers;
		}

		Sim sim(String playerId) {
			return sims.stream().filter(s -> s.player.id().equals(playerId)).findFirst().orElseThrow();
		}

		void log(String format, Object... args) {
			log.add("  ".repeat(acting.size()) + String.format(format, args));
		}

		void fail(int invariant, String what) {
			throw failure("invariant " + invariant + " broken: " + what, null);
		}

		AssertionError failure(String what, Throwable cause) {
			return new AssertionError(what + "\nseed " + seed + " (replay with -Dwgds.interleavings.seed=" + seed + ")\nsteps:\n"
					+ String.join("\n", log), cause);
		}
	}

	private static void deleteRecursively(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (Stream<Path> walk = Files.walk(folder)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}
}
