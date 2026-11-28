package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;

/** An upload that doesn't fit in the cloud storage account's free space. */
public final class NotEnoughSpaceException extends IOException {
	private final String worldName;
	private final long needed;
	private final Long free;

	NotEnoughSpaceException(String worldName, long needed, OptionalLong free, Throwable cause) {
		// For the log. What the player is told is worded in the language file only.
		super("No room for " + worldName + " (" + needed + " bytes, free: " + free + ")", cause);
		this.worldName = worldName;
		this.needed = needed;
		this.free = free.isPresent() ? free.getAsLong() : null;
	}

	/** This failure, if {@code failure} is one or was caused by one. */
	public static Optional<NotEnoughSpaceException> in(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof NotEnoughSpaceException found) return Optional.of(found);
		}
		return Optional.empty();
	}

	/** The name of the Shared World that didn't fit. */
	public String worldName() {
		return worldName;
	}

	/** The size of the zip, in bytes. */
	public long needed() {
		return needed;
	}

	/** The account's free space in bytes, if it could be told. */
	public OptionalLong free() {
		return free == null ? OptionalLong.empty() : OptionalLong.of(free);
	}
}
