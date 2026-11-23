package net.aii3n.worldgdrivesync.google;

/** Sign-in or a Google call failed in a way the player should be told about. */
public class GoogleAuthException extends RuntimeException {
	public GoogleAuthException(String message) {
		super(message);
	}

	public GoogleAuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
