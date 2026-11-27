package net.aii3n.worldgdrivesync.google;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleAccountTest {
	private static final OAuthClient CLIENT = new OAuthClient("test-client-id", "test-client-secret");

	@TempDir
	Path configDir;
	FakeGoogle google;

	@BeforeEach
	void startGoogle() throws Exception {
		google = new FakeGoogle();
	}

	@AfterEach
	void stopGoogle() {
		google.close();
	}

	/** The whole page the loopback redirect should send the browser. */
	private static String page(String message) {
		return "<!doctype html><meta charset=utf-8><title>World GDrive Sync</title><p>" + message + "</p>";
	}

	private GoogleAccount newAccount() {
		return new GoogleAccount(configDir.resolve("google-token.json"), CLIENT, google.endpoints());
	}

	@Test
	void signingInThroughTheBrowserConnectsTheSharedAccount() throws Exception {
		GoogleAccount account = newAccount();
		FakeBrowser browser = FakeBrowser.approving(google);

		String connected = account.signIn(browser).result().get(5, TimeUnit.SECONDS);

		assertEquals(FakeGoogle.ACCOUNT_EMAIL, connected);
		assertEquals(Optional.of(FakeGoogle.ACCOUNT_EMAIL), account.connectedAccount());
		assertEquals(page("Signed in. You can close this tab and return to Minecraft."), browser.redirectPage.get(5, TimeUnit.SECONDS));
	}

	@Test
	void stillConnectedAfterAGameRestartWithoutSigningInAgain() throws Exception {
		newAccount().signIn(FakeBrowser.approving(google)).result().get(5, TimeUnit.SECONDS);

		GoogleAccount afterRestart = newAccount();

		assertEquals(Optional.of(FakeGoogle.ACCOUNT_EMAIL), afterRestart.connectedAccount());
	}

	@Test
	void disconnectRemovesTheStoredSignInAndRevokesItWithGoogle() throws Exception {
		GoogleAccount account = newAccount();
		account.signIn(FakeBrowser.approving(google)).result().get(5, TimeUnit.SECONDS);

		account.disconnect().get(5, TimeUnit.SECONDS);

		assertEquals(Optional.empty(), account.connectedAccount());
		assertEquals(Optional.empty(), newAccount().connectedAccount());
		assertEquals(List.of(FakeGoogle.REFRESH_TOKEN), google.revokedTokens);
	}

	@Test
	void disconnectStillForgetsTheSignInWhenGoogleIsUnreachable() throws Exception {
		GoogleAccount account = newAccount();
		account.signIn(FakeBrowser.approving(google)).result().get(5, TimeUnit.SECONDS);
		google.close();

		account.disconnect().get(5, TimeUnit.SECONDS);

		assertEquals(Optional.empty(), newAccount().connectedAccount());
	}

	@Test
	void signInAsksGoogleOnlyForTheNarrowDrivePermissionWithALoopbackRedirect() {
		FakeBrowser browser = FakeBrowser.idle();

		SignIn signIn = newAccount().signIn(browser);

		assertEquals(signIn.authorizationUrl(), browser.openedUri);
		assertEquals("https://www.googleapis.com/auth/drive.file", browser.openedQuery.get("scope"));
		assertEquals("test-client-id", browser.openedQuery.get("client_id"));
		assertTrue(browser.openedQuery.get("redirect_uri").startsWith("http://127.0.0.1:"));
		assertEquals("offline", browser.openedQuery.get("access_type"));
		signIn.cancel();
	}

	@Test
	void declinedInTheBrowserLeavesThePlayerDisconnected() throws Exception {
		FakeBrowser browser = FakeBrowser.denying();
		SignIn signIn = newAccount().signIn(browser);

		assertThrows(ExecutionException.class, () -> signIn.result().get(5, TimeUnit.SECONDS));
		assertEquals(Optional.empty(), newAccount().connectedAccount());
		assertEquals(page("Sign-in was cancelled. You can close this tab and return to Minecraft."), browser.redirectPage.get(5, TimeUnit.SECONDS));
	}

	@Test
	void anAnswerFromAnotherSignInAttemptIsRejected() throws Exception {
		FakeBrowser browser = FakeBrowser.forgingState(google);
		SignIn signIn = newAccount().signIn(browser);

		assertThrows(ExecutionException.class, () -> signIn.result().get(5, TimeUnit.SECONDS));
		assertEquals(Optional.empty(), newAccount().connectedAccount());
		assertEquals(page("This sign-in link is out of date. Start again from Minecraft."), browser.redirectPage.get(5, TimeUnit.SECONDS));
	}

	@Test
	void cancellingWhileWaitingForTheBrowserEndsTheSignIn() {
		GoogleAccount account = newAccount();
		SignIn signIn = account.signIn(FakeBrowser.idle());

		signIn.cancel();

		assertThrows(ExecutionException.class, () -> signIn.result().get(5, TimeUnit.SECONDS));
		assertEquals(Optional.empty(), account.connectedAccount());
	}

	@Test
	void signInReturnsBeforeTheBrowserAnswers() {
		SignIn signIn = newAccount().signIn(FakeBrowser.idle());

		assertFalse(signIn.result().isDone());
		signIn.cancel();
	}

	@Test
	void notConnectedBeforeAnySignIn() {
		assertEquals(Optional.empty(), newAccount().connectedAccount());
	}
}
