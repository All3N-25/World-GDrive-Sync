package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.GoogleConnection;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.google.SignIn;
import net.aii3n.worldgdrivesync.settings.SettingsFile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/**
 * Mod settings. For now: connect the shared Google account through the system browser, see which
 * account is connected, and disconnect it.
 */
public class SharedWorldsSettingsScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.settings.title");
	private static final List<Long> PERIODIC_UPLOAD_MINUTES = List.of(0L, 5L, 10L, 15L, 30L, 60L, 120L);
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private Object shownState;

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

		content.addChild(periodicUploadButton());

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);
	}

	/** Periodic upload interval while hosting, in minutes; 0 turns it off. Saved as soon as it changes. */
	private CycleButton<Long> periodicUploadButton() {
		SettingsFile settings = SharedWorlds.get().settings();
		long current = settings.current().periodicUploadInterval().toMinutes();
		TreeSet<Long> choices = new TreeSet<>(PERIODIC_UPLOAD_MINUTES);
		choices.add(current);
		return CycleButton.<Long>builder(minutes -> minutes == 0 ? CommonComponents.OPTION_OFF
						: Component.translatable("world-gdrive-sync.settings.periodicUpload.minutes", minutes), current)
				.withValues(List.copyOf(choices))
				.withTooltip(minutes -> Tooltip.create(Component.translatable("world-gdrive-sync.settings.periodicUpload.tooltip")))
				.create(0, 0, 200, 20, Component.translatable("world-gdrive-sync.settings.periodicUpload"), (button, minutes) -> {
					try {
						settings.update(settings.current().withPeriodicUploadInterval(Duration.ofMinutes(minutes)));
					} catch (IOException e) {
						WorldGDriveSync.LOGGER.warn("Couldn't save settings", e);
					}
				});
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
		minecraft.gui.setScreen(lastScreen);
	}
}
