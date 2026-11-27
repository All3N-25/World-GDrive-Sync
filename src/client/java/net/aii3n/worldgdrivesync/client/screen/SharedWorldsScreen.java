package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.client.GoogleConnection;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.session.LaunchRetry;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.aii3n.worldgdrivesync.session.WorldStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Lists every Shared World on the account with its status. Reads Drive each time the screen is
 * opened (or returned to), when Refresh is pressed, and when the launch retry of a Pending Upload
 * ends. A world that retry is still working on shows its progress and can't be played.
 */
public class SharedWorldsScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.sharedWorlds.title");
	private static final Tooltip PLAY_WHILE_RETRYING =
			Tooltip.create(Component.translatable("world-gdrive-sync.sharedWorlds.playWhileRetrying"));
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
	private @Nullable Button gearButton;
	private @Nullable Button playButton;
	private @Nullable Button backupsButton;
	private @Nullable WorldList list;
	private boolean shownConnected;

	/** Null while loading. */
	private @Nullable List<SharedWorld> worlds;
	private @Nullable String loadError;
	private int loadRequest;
	/** {@link LaunchRetry#endedCount} when the latest list was asked for. */
	private long listRequestedAt;
	/** {@link #listRequestedAt} of the list on screen. */
	private long listedAt;

	public SharedWorldsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	public void added() {
		// Every time this screen becomes current: on opening, and on coming back from a child screen.
		refresh();
	}

	private void refresh() {
		if (!GoogleConnection.get().account().isConnected()) return;
		worlds = null;
		loadError = null;
		load();
		if (minecraft != null && width > 0) rebuildWidgets();
	}

	/** Reads the list again, leaving what is on screen until the answer arrives. */
	private void load() {
		int request = ++loadRequest;
		long requestedAt = SharedWorlds.get().launchRetry().endedCount();
		listRequestedAt = requestedAt;
		SharedWorlds.get().listSharedWorlds().whenCompleteAsync((result, error) -> {
			if (request != loadRequest) return;
			if (error != null) {
				loadError = SharedWorlds.describe(error);
			} else {
				loadError = null;
				listedAt = requestedAt;
				worlds = result.stream().sorted(Comparator.comparing(SharedWorld::name, String.CASE_INSENSITIVE_ORDER)).toList();
			}
			rebuildWidgets();
		}, screenExecutor);
	}

	@Override
	protected void init() {
		shownConnected = GoogleConnection.get().account().isConnected();
		String selectedId = list != null && list.getSelected() != null ? list.getSelected().world.id() : null;
		layout.removeChildren();
		list = null;
		layout.addTitleHeader(TITLE, font);

		if (!shownConnected) {
			LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(8));
			content.defaultCellSetting().alignHorizontallyCenter();
			content.addChild(text(Component.translatable("world-gdrive-sync.sharedWorlds.connectFirst")));
			content.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.openSettings"),
					b -> openSettings()).width(200).build());
		} else if (loadError != null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.sharedWorlds.loadFailed", loadError)
					.withStyle(ChatFormatting.RED)));
		} else if (worlds == null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.sharedWorlds.loading")));
		} else if (worlds.isEmpty()) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.sharedWorlds.empty")));
		} else {
			list = layout.addToContents(new WorldList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), worlds,
					this::play, () -> listedAt));
			list.select(selectedId);
		}

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		LinearLayout topRow = footer.addChild(LinearLayout.horizontal().spacing(8));
		playButton = topRow.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.play"),
				b -> playSelected()).width(150).build());
		Button share = topRow.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.share"),
				b -> minecraft.gui.setScreen(new ShareWorldScreen(this))).width(150).build());
		LinearLayout bottomRow = footer.addChild(LinearLayout.horizontal().spacing(8));
		Button refresh = bottomRow.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.refresh"),
				b -> refresh()).width(96).build());
		backupsButton = bottomRow.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.backups"),
				b -> openBackups()).width(96).build());
		bottomRow.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(100).build());
		share.active = shownConnected;
		refresh.active = shownConnected && (worlds != null || loadError != null);
		updatePlayButton();
		updateBackupsButton();

		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);

		gearButton = addRenderableWidget(Button.builder(Component.literal("⚙"), b -> openSettings())
				.tooltip(Tooltip.create(Component.translatable("world-gdrive-sync.settings.title")))
				.size(20, 20).build());
		positionGear();
	}

	private MultiLineTextWidget text(Component message) {
		return new MultiLineTextWidget(message, font).setMaxWidth(300).setCentered(true);
	}

	@Override
	protected void repositionElements() {
		if (list != null) list.updateSize(width, layout);
		layout.arrangeElements();
		positionGear();
	}

	private void updatePlayButton() {
		if (playButton == null) return;
		SharedWorld selected = list != null && list.getSelected() != null ? list.getSelected().world : null;
		boolean retrying = selected != null && blocksPlay(selected);
		playButton.active = selected != null && !retrying;
		playButton.setTooltip(retrying ? PLAY_WHILE_RETRYING : null);
		// A Pending Upload goes up before anyone plays on: Play becomes Retry for that world.
		playButton.setMessage(Component.translatable(selected != null && selected.pendingUpload()
				? "world-gdrive-sync.sharedWorlds.retry" : "world-gdrive-sync.sharedWorlds.play"));
	}

	private boolean blocksPlay(SharedWorld world) {
		return SharedWorlds.get().launchRetry().blocksPlay(world.id(), listedAt);
	}

	private void updateBackupsButton() {
		if (backupsButton != null) backupsButton.active = list != null && list.getSelected() != null;
	}

	private void openBackups() {
		if (list != null && list.getSelected() != null) minecraft.gui.setScreen(new BackupsScreen(this, list.getSelected().world));
	}

	private void playSelected() {
		if (list != null && list.getSelected() != null) play(list.getSelected().world);
	}

	/**
	 * Takes the Lock, downloads if needed and opens the world (see {@link PlayScreen}); or, for a
	 * Pending Upload, retries the upload (see {@link RetryUploadScreen}).
	 */
	private void play(SharedWorld world) {
		if (blocksPlay(world)) return;
		if (world.pendingUpload()) {
			minecraft.gui.setScreen(new RetryUploadScreen(this, world));
			return;
		}
		minecraft.gui.setScreen(new PlayScreen(this, world));
	}

	@Override
	public void tick() {
		// A launch retry ended: read how that world stands now.
		if (shownConnected && SharedWorlds.get().launchRetry().endedCount() != listRequestedAt) load();
		updatePlayButton();
		updateBackupsButton();
		// Connecting or disconnecting happens elsewhere (settings, or a revoked sign-in found mid-call).
		boolean connected = GoogleConnection.get().account().isConnected();
		if (connected != shownConnected) {
			if (connected) refresh();
			rebuildWidgets();
		}
	}

	private void positionGear() {
		if (gearButton == null) return;
		gearButton.setX(width - 24);
		gearButton.setY(6);
	}

	private void openSettings() {
		minecraft.gui.setScreen(new SharedWorldsSettingsScreen(this));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
	}

	static Component statusText(WorldStatus status) {
		return switch (status) {
			case WorldStatus.Free free -> Component.translatable("world-gdrive-sync.status.free").withStyle(ChatFormatting.GREEN);
			case WorldStatus.Hosted hosted -> Component.translatable("world-gdrive-sync.status.hosted",
					hosted.host(), duration(Duration.between(hosted.since(), Instant.now()))).withStyle(ChatFormatting.GOLD);
			case WorldStatus.Stale stale -> Component.translatable("world-gdrive-sync.status.stale",
					stale.host(), duration(Duration.between(stale.lastSeen(), Instant.now()))).withStyle(ChatFormatting.RED);
		};
	}

	/** "45s", "12m", "2h 5m". */
	static String duration(Duration duration) {
		long seconds = Math.max(0, duration.toSeconds());
		if (seconds < 60) return seconds + "s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m";
		return minutes / 60 + "h " + minutes % 60 + "m";
	}

	private static final class WorldList extends ObjectSelectionList<WorldList.Entry> {
		WorldList(Minecraft minecraft, int width, int height, int y, List<SharedWorld> worlds, Consumer<SharedWorld> play,
				LongSupplier listedAt) {
			super(minecraft, width, height, y, 36);
			for (SharedWorld world : worlds) addEntry(new Entry(minecraft, world, play, listedAt));
		}

		/** Selects the world with this id, if it is listed. */
		void select(@Nullable String worldId) {
			children().stream().filter(entry -> entry.world.id().equals(worldId)).findFirst().ifPresent(this::setSelected);
		}

		@Override
		public int getRowWidth() {
			return 270;
		}

		static final class Entry extends ObjectSelectionList.Entry<Entry> {
			private final Minecraft minecraft;
			private final SharedWorld world;
			private final Consumer<SharedWorld> play;
			private final LongSupplier listedAt;

			Entry(Minecraft minecraft, SharedWorld world, Consumer<SharedWorld> play, LongSupplier listedAt) {
				this.minecraft = minecraft;
				this.world = world;
				this.play = play;
				this.listedAt = listedAt;
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (doubleClick) play.accept(world);
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", world.name());
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 3;
				int y = getContentY() + 1;
				graphics.text(minecraft.font, world.name(), x, y, CommonColors.WHITE, false);
				// The list is older than the end of this world's retry: its Revision and status are out of date.
				boolean outOfDate = SharedWorlds.get().launchRetry().endedSince(world.id(), listedAt.getAsLong());
				Optional<LaunchRetry.State> retry = SharedWorlds.get().launchRetry().state(world.id());
				graphics.text(minecraft.font, Component.translatable("world-gdrive-sync.sharedWorlds.revision", world.revision())
						.append(" · ").append(statusText(world.status())), x, y + 12, CommonColors.LIGHT_GRAY, false);
				Component upload = retry.map(Entry::retryText).orElseGet(() -> {
					if (outOfDate) return Component.translatable("world-gdrive-sync.sharedWorlds.loading");
					return world.pendingUpload()
							? Component.translatable("world-gdrive-sync.sharedWorlds.pendingUpload").withStyle(ChatFormatting.RED)
							: null;
				});
				if (upload != null) graphics.text(minecraft.font, upload, x, y + 22, CommonColors.LIGHT_GRAY, false);
			}

			private static Component retryText(LaunchRetry.State state) {
				return (switch (state) {
					case LaunchRetry.State.Waiting waiting -> Component.translatable("world-gdrive-sync.sharedWorlds.retryWaiting");
					case LaunchRetry.State.Uploading uploading ->
							Component.translatable("world-gdrive-sync.sharedWorlds.retryUploading", uploading.percent());
				}).withStyle(ChatFormatting.YELLOW);
			}
		}
	}
}
