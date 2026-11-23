package net.aii3n.worldgdrivesync.google;

import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * One sign-in attempt in progress: the player finishes it in their browser. Nothing here blocks;
 * {@link #result()} completes (off the caller's thread) with the connected account's email.
 */
public final class SignIn {
	private final URI authorizationUrl;
	private final CompletableFuture<String> code;
	private final CompletableFuture<String> result;

	SignIn(URI authorizationUrl, CompletableFuture<String> code, CompletableFuture<String> result) {
		this.authorizationUrl = authorizationUrl;
		this.code = code;
		this.result = result;
	}

	/** The Google sign-in page, for showing or copying if the browser didn't open. */
	public URI authorizationUrl() {
		return authorizationUrl;
	}

	/** Completes with the connected account's email, or exceptionally if sign-in failed. */
	public CompletableFuture<String> result() {
		return result;
	}

	/** Stops waiting for the browser. Has no effect once the browser has answered. */
	public void cancel() {
		code.completeExceptionally(new CancellationException("Sign-in cancelled"));
	}
}
