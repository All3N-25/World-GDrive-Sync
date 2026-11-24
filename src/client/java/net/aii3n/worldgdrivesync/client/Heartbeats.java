package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Refreshes every Lock this player holds with a Heartbeat once a minute (fixed), so friends see them
 * as Hosted rather than Stale: from the moment Play takes the Lock until it is released, including
 * during the download and the final upload. Runs on a thread of its own, so the network thread's
 * long zips, uploads and downloads never hold a Heartbeat up. If a friend Took Over, the player is
 * told with a toast.
 */
final class Heartbeats {
	private static final long INTERVAL_SECONDS = 60;
	/** How long the "Lock lost" toast stays up. */
	private static final SystemToast.SystemToastId LOCK_LOST = new SystemToast.SystemToastId(15_000L);

	private Heartbeats() {
	}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(Heartbeats::start);
	}

	private static void start(Minecraft minecraft) {
		ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread heartbeat = new Thread(task, "WGDS-Heartbeat");
			heartbeat.setDaemon(true);
			return heartbeat;
		});
		thread.scheduleWithFixedDelay(() -> beat(minecraft), INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	// Heartbeat thread. Never throws: an escaping exception would cancel the schedule.
	private static void beat(Minecraft minecraft) {
		try {
			List<String> lost = SharedWorlds.get().coordinator().heartbeatHeldLocks();
			for (String worldId : lost) {
				WorldGDriveSync.LOGGER.warn("Lost the Lock on {}: a friend Took Over after it went Stale", worldId);
				minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.gui.toastManager(), LOCK_LOST,
						Component.translatable("world-gdrive-sync.heartbeat.lockLost.title"),
						Component.translatable("world-gdrive-sync.heartbeat.lockLost")));
			}
		} catch (Exception e) {
			WorldGDriveSync.LOGGER.warn("Heartbeat failed; will retry in a minute", e);
		}
	}
}
