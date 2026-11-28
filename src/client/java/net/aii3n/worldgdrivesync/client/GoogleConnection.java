package net.aii3n.worldgdrivesync.client;

import com.mojang.blaze3d.Blaze3D;
import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.google.GoogleEndpoints;
import net.aii3n.worldgdrivesync.google.OAuthClient;
import net.aii3n.worldgdrivesync.google.SignIn;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * The game's one connection to the shared Google account, plus the sign-in in progress (if any),
 * so the sign-in keeps going when the player leaves the settings screen.
 * Only touched from the client thread.
 */
public final class GoogleConnection {
	private static @Nullable GoogleConnection instance;

	private final GoogleAccount account;
	private @Nullable SignIn signIn;
	private @Nullable String lastError;

	private GoogleConnection(GoogleAccount account) {
		this.account = account;
	}

	static void init() {
		instance = new GoogleConnection(new GoogleAccount(
				FabricLoader.getInstance().getConfigDir().resolve(WorldGDriveSync.MOD_ID).resolve("google-token.json"),
				OAuthClient.BUILT_IN,
				GoogleEndpoints.GOOGLE));
	}

	public static GoogleConnection get() {
		if (instance == null) throw new IllegalStateException("GoogleConnection used before client init");
		return instance;
	}

	public GoogleAccount account() {
		return account;
	}

	/** True while the mod still has the placeholder OAuth client, so sign-in can't work. */
	public boolean isClientMissing() {
		return OAuthClient.BUILT_IN.isPlaceholder();
	}

	public @Nullable SignIn signInInProgress() {
		return signIn;
	}

	/** Why the last sign-in failed, until the next attempt. */
	public @Nullable String lastError() {
		return lastError;
	}

	/** Starts signing in through the system browser; returns at once. */
	public SignIn startSignIn() {
		if (signIn != null) return signIn;
		lastError = null;
		SignIn started = account.signIn(Blaze3D::openUri);
		signIn = started;
		started.result().whenCompleteAsync((email, error) -> {
			if (signIn == started) signIn = null;
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				if (!(cause instanceof CancellationException)) {
					lastError = cause.getMessage();
					WorldGDriveSync.LOGGER.warn("Google sign-in failed", cause);
				}
			}
		}, Minecraft.getInstance());
		return started;
	}

	public void cancelSignIn() {
		if (signIn != null) signIn.cancel();
	}

	public void disconnect() {
		lastError = null;
		account.disconnect().exceptionally(error -> {
			WorldGDriveSync.LOGGER.warn("Couldn't fully disconnect the Google account", error);
			return null;
		});
	}
}
