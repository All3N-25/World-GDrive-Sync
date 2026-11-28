package net.aii3n.worldgdrivesync.google;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The short-lived access token that Drive calls use, kept fresh from the stored refresh token. */
class AccessTokenTest {
	private static final OAuthClient CLIENT = new OAuthClient("test-client-id", "test-client-secret");

	@TempDir
	Path configDir;
	FakeGoogle google;
	Instant now = Instant.parse("2026-11-24T18:00:00Z");
	final InstantSource clock = () -> now;

	@BeforeEach
	void startGoogle() throws Exception {
		google = new FakeGoogle();
	}

	@AfterEach
	void stopGoogle() {
		google.close();
	}

	private GoogleAccount newAccount() {
		return new GoogleAccount(configDir.resolve("google-token.json"), CLIENT, google.endpoints(), clock);
	}

	private GoogleAccount signedIn() throws Exception {
		GoogleAccount account = newAccount();
		account.signIn(FakeBrowser.approving(google)).result().get(5, TimeUnit.SECONDS);
		return account;
	}

	@Test
	void rightAfterSignInTheSignInsOwnAccessTokenIsUsed() throws Exception {
		assertEquals(FakeGoogle.ACCESS_TOKEN, signedIn().accessToken());
		assertEquals(0, google.refreshes.get());
	}

	@Test
	void afterAGameRestartAnAccessTokenIsFetchedWithTheStoredRefreshToken() throws Exception {
		signedIn();

		GoogleAccount afterRestart = newAccount();

		assertEquals("refreshed-1", afterRestart.accessToken());
		assertEquals("refreshed-1", afterRestart.accessToken());
	}

	@Test
	void anAccessTokenAboutToExpireIsReplaced() throws Exception {
		GoogleAccount account = signedIn();

		now = now.plus(Duration.ofMinutes(55));
		assertEquals(FakeGoogle.ACCESS_TOKEN, account.accessToken());

		now = now.plus(Duration.ofMinutes(4));
		assertEquals("refreshed-1", account.accessToken());
	}

	@Test
	void anAccessTokenDriveRejectedIsReplaced() throws Exception {
		GoogleAccount account = signedIn();

		account.accessTokenRejected(FakeGoogle.ACCESS_TOKEN);

		assertEquals("refreshed-1", account.accessToken());
	}

	@Test
	void aRevokedSignInDisconnectsThePlayer() throws Exception {
		signedIn();
		google.refreshTokenRevoked = true;
		GoogleAccount afterRestart = newAccount();

		assertThrows(GoogleAuthException.class, afterRestart::accessToken);

		assertEquals(Optional.empty(), afterRestart.connectedAccount());
		assertEquals(Optional.empty(), newAccount().connectedAccount());
	}

	@Test
	void googleBeingUnreachableDoesNotDisconnect() throws Exception {
		signedIn();
		google.close();
		GoogleAccount afterRestart = newAccount();

		assertThrows(GoogleAuthException.class, afterRestart::accessToken);

		assertEquals(Optional.of(FakeGoogle.ACCOUNT_EMAIL), afterRestart.connectedAccount());
	}

	@Test
	void noAccessTokenWithoutSigningIn() {
		assertThrows(GoogleAuthException.class, newAccount()::accessToken);
	}
}
