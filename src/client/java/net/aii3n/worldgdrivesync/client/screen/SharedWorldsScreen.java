package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.client.GoogleConnection;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.aii3n.worldgdrivesync.session.WorldStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Lists every Shared World on the account with its status. Reads Drive each time the screen is
 * opened (or returned to) and when Refresh is pressed.
 */
public class SharedWorldsScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.sharedWorlds.title");
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
	private @Nullable Button gearButton;
	private @Nullable WorldList list;
	private boolean shownConnected;

	/** Null while loading. */
	private @Nullable List<SharedWorld> worlds;
	private @Nullable String loadError;
	private int loadRequest;

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
		int request = ++loadRequest;
		worlds = null;
		loadError = null;
		SharedWorlds.get().run(coordinator -> coordinator.listSharedWorlds()).whenCompleteAsync((result, error) -> {
			if (request != loadRequest) return;
			if (error != null) {
				loadError = SharedWorlds.describe(error);
			} else {
				worlds = result.stream().sorted(Comparator.comparing(SharedWorld::name, String.CASE_INSENSITIVE_ORDER)).toList();
			}
			rebuildWidgets();
		}, screenExecutor);
		if (minecraft != null && width > 0) rebuildWidgets();
	}

	@Override
	protected void init() {
		shownConnected = GoogleConnection.get().account().isConnected();
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
			list = layout.addToContents(new WorldList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), worlds));
		}

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		LinearLayout row = footer.addChild(LinearLayout.horizontal().spacing(8));
		Button share = row.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.share"),
				b -> minecraft.gui.setScreen(new ShareWorldScreen(this))).width(150).build());
		Button refresh = row.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.refresh"),
				b -> refresh()).width(150).build());
		share.active = shownConnected;
		refresh.active = shownConnected && (worlds != null || loadError != null);
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());

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

	@Override
	public void tick() {
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
		};
	}

	private static final class WorldList extends ObjectSelectionList<WorldList.Entry> {
		WorldList(Minecraft minecraft, int width, int height, int y, List<SharedWorld> worlds) {
			super(minecraft, width, height, y, 36);
			for (SharedWorld world : worlds) addEntry(new Entry(minecraft, world));
		}

		@Override
		public int getRowWidth() {
			return 270;
		}

		static final class Entry extends ObjectSelectionList.Entry<Entry> {
			private final Minecraft minecraft;
			private final SharedWorld world;

			Entry(Minecraft minecraft, SharedWorld world) {
				this.minecraft = minecraft;
				this.world = world;
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
				graphics.text(minecraft.font, Component.translatable("world-gdrive-sync.sharedWorlds.revision", world.revision())
						.append(" · ").append(statusText(world.status())), x, y + 12, CommonColors.LIGHT_GRAY, false);
			}
		}
	}
}
