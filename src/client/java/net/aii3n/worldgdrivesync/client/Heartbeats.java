package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * While this player hosts a Shared World, refreshes their Lock with a Heartbeat every minute
 * (fixed), so friends see them as Hosted rather than Stale.
 */
final class Heartbeats {
	private static final long INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

	private static @Nullable String worldId;
	private static long lastBeat;
	private static final AtomicBoolean inFlight = new AtomicBoolean();

	private Heartbeats() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(Heartbeats::onEndTick);
	}

	// Client thread.
	private static void onEndTick(Minecraft minecraft) {
		String hosted = HostingSession.hostedWorldId();
		long now = System.nanoTime();
		if (hosted == null) {
			worldId = null;
			return;
		}
		if (!hosted.equals(worldId)) {
			// Hosting just started; Play wrote the Lock moments ago.
			worldId = hosted;
			lastBeat = now;
			return;
		}
		if (now - lastBeat < INTERVAL_NANOS || !inFlight.compareAndSet(false, true)) return;
		lastBeat = now;
		SharedWorlds.get().run(coordinator -> coordinator.heartbeat(hosted)).whenComplete((stillMine, error) -> {
			inFlight.set(false);
			if (error != null) {
				WorldGDriveSync.LOGGER.warn("Heartbeat for {} failed; will retry in a minute", hosted, error);
			} else if (!stillMine) {
				WorldGDriveSync.LOGGER.warn("Lost the Lock on {}: a friend Took Over after it went Stale", hosted);
			}
		});
	}
}
