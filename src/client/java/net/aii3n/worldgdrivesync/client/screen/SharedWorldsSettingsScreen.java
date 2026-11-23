package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.GoogleConnection;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.google.SignIn;
import net.aii3n.worldgdrivesync.settings.Settings;
import net.aii3n.worldgdrivesync.settings.SettingsFile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;

/**
 * Mod settings: connect the shared Google account through the system browser, see which account
 * is connected, and disconnect it; and the Shared World settings (how many Backups to keep). The
 * settings are saved when the screen closes.
 */
public class SharedWorldsSettingsScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.settings.title");
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private Object shownState;
	/** The settings as edited on this screen; kept across rebuilds and saved on close. */
	private Settings edited = SharedWorlds.get().settings().current();

	public SharedWorldsSettingsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		GoogleConnection connection = GoogleConnection.get();
		shownState = currentState();
		layout.removeChildren();
		layout.addTitleHeader(TITLE, font);

		LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();
		SignIn signIn = connection.signInInProgress();
		var connected = connection.account().connectedAccount();

		if (signIn != null) {
			content.addChild(text(Component.translatable("world-gdrive-sync.settings.waitingForBrowser")));
			content.addChild(Button.builder(Component.translatable("world-gdrive-sync.settings.copyLink"),
					b -> minecraft.keyboardHandler.setClipboard(signIn.authorizationUrl().toString())).width(200).build());
			content.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> connection.cancelSignIn()).width(200).build());
		} else if (connected.isPresent()) {
			content.addChild(text(Component.translatable("world-gdrive-sync.settings.connectedAs",
					Component.literal(connected.get()).withStyle(ChatFormatting.GREEN))));
			content.addChild(Button.builder(Component.translatable("world-gdrive-sync.settings.disconnect"), b -> {
				connection.disconnect();
				rebuildWidgets();
			}).width(200).build());
		} else {
			content.addChild(text(Component.translatable("world-gdrive-sync.settings.notConnected")));
			Button connect = content.addChild(Button.builder(Component.translatable("world-gdrive-sync.settings.connect"), b -> {
				connection.startSignIn();
				rebuildWidgets();
			}).width(200).build());
			if (connection.isClientMissing()) {
				connect.active = false;
				content.addChild(text(Component.translatable("world-gdrive-sync.settings.clientMissing").withStyle(ChatFormatting.RED)));
			}
			if (connection.lastError() != null) {
				content.addChild(text(Component.translatable("world-gdrive-sync.settings.signInFailed", connection.lastError())
						.withStyle(ChatFormatting.RED)));
			}
		}

		content.addChild(new BackupCountSlider());

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);
	}

	private MultiLineTextWidget text(Component message) {
		return new MultiLineTextWidget(message, font).setMaxWidth(300).setCentered(true);
	}

	/** What the screen depends on; sign-in finishes on another thread, so check it every tick. */
	private Object currentState() {
		GoogleConnection connection = GoogleConnection.get();
		return java.util.Arrays.asList(connection.signInInProgress(), connection.account().connectedAccount(), connection.lastError());
	}

	@Override
	public void tick() {
		if (!currentState().equals(shownState)) rebuildWidgets();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		SettingsFile settings = SharedWorlds.get().settings();
		if (!edited.equals(settings.current())) {
			try {
				settings.save(edited);
			} catch (IOException e) {
				WorldGDriveSync.LOGGER.warn("Couldn't save settings", e);
			}
		}
		minecraft.gui.setScreen(lastScreen);
	}

	/** Backups kept per Shared World, from {@link Settings#MIN_BACKUP_COUNT} to {@link Settings#MAX_BACKUP_COUNT}. */
	private final class BackupCountSlider extends AbstractSliderButton {
		private static final int RANGE = Settings.MAX_BACKUP_COUNT - Settings.MIN_BACKUP_COUNT;

		BackupCountSlider() {
			super(0, 0, 200, DEFAULT_HEIGHT, Component.empty(), (double) (edited.backupCount() - Settings.MIN_BACKUP_COUNT) / RANGE);
			updateMessage();
		}

		private int count() {
			return Settings.MIN_BACKUP_COUNT + Mth.floor(value * RANGE + 0.5);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("world-gdrive-sync.settings.backupCount", count()));
		}

		@Override
		protected void applyValue() {
			edited = edited.withBackupCount(count());
		}
	}
}
