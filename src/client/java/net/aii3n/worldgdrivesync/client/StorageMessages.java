package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.session.ByteSize;
import net.aii3n.worldgdrivesync.session.NotEnoughSpaceException;
import net.aii3n.worldgdrivesync.session.StorageFullException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/** What the player is told about the room left in Google Drive, on screens and in toasts. */
public final class StorageMessages {
	private static final SystemToast.SystemToastId NOT_ENOUGH_SPACE = new SystemToast.SystemToastId(15_000L);
	private static final SystemToast.SystemToastId NEARLY_FULL = new SystemToast.SystemToastId(10_000L);

	private StorageMessages() {
	}

	/**
	 * What to tell the player if {@code failure} was for want of room in Google Drive: why, and what
	 * to do about it. Empty for any other failure.
	 */
	public static Optional<Component> outOfSpace(Throwable failure) {
		return NotEnoughSpaceException.in(failure).map(StorageMessages::notEnoughSpace)
				.or(() -> StorageFullException.in(failure).map(full -> Component.translatable("world-gdrive-sync.storageFull")));
	}

	private static Component notEnoughSpace(NotEnoughSpaceException refused) {
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
	static void showOutOfSpaceToast(Minecraft minecraft, Component message) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), NOT_ENOUGH_SPACE,
				Component.translatable("world-gdrive-sync.noSpace.title"), message);
	}

	// Client thread.
	static void showNearlyFullToast(Minecraft minecraft, long free) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), NEARLY_FULL,
				Component.translatable("world-gdrive-sync.nearlyFull.title"), nearlyFull(free));
	}
}
