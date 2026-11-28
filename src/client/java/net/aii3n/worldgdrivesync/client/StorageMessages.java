package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.session.ByteSize;
import net.aii3n.worldgdrivesync.session.NotEnoughSpaceException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** What the player is told about the room left in Google Drive, on screens and in toasts. */
public final class StorageMessages {
	private static final SystemToast.SystemToastId NOT_ENOUGH_SPACE = new SystemToast.SystemToastId(15_000L);
	private static final SystemToast.SystemToastId NEARLY_FULL = new SystemToast.SystemToastId(10_000L);

	private StorageMessages() {
	}

	/** Why an upload didn't fit, and what to do about it. */
	public static Component notEnoughSpace(NotEnoughSpaceException refused) {
		String needed = ByteSize.format(refused.needed());
		return refused.free().isPresent()
				? Component.translatable("world-gdrive-sync.noSpace", refused.worldName(), needed, ByteSize.format(refused.free().getAsLong()))
				: Component.translatable("world-gdrive-sync.noSpace.unknownFree", refused.worldName(), needed);
	}

	/** The warning that Google Drive has only {@code free} bytes left. */
	public static Component nearlyFull(long free) {
		return Component.translatable("world-gdrive-sync.nearlyFull", ByteSize.format(free));
	}

	// Client thread.
	static void showNotEnoughSpaceToast(Minecraft minecraft, NotEnoughSpaceException refused) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), NOT_ENOUGH_SPACE,
				Component.translatable("world-gdrive-sync.noSpace.title"), notEnoughSpace(refused));
	}

	// Client thread.
	static void showNearlyFullToast(Minecraft minecraft, long free) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), NEARLY_FULL,
				Component.translatable("world-gdrive-sync.nearlyFull.title"), nearlyFull(free));
	}
}
