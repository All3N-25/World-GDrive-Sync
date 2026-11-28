package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.util.Optional;

/**
 * The cloud store refused to store anything more: the account is full. Any {@link CloudStore} call
 * that writes may throw it. The coordinator turns one that stops an upload into a
 * {@link NotEnoughSpaceException}, which says which world didn't fit.
 */
public final class StorageFullException extends IOException {
	/** @param cause the cloud store's own account of the refusal, for the log */
	public StorageFullException(Throwable cause) {
		super("Google Drive is full", cause);
	}

	/** This failure, if {@code failure} is one or was caused by one. */
	public static Optional<StorageFullException> in(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof StorageFullException found) return Optional.of(found);
		}
		return Optional.empty();
	}
}
