package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** "Share an existing world": pick one of the player's normal singleplayer worlds from {@code saves/}. */
public class ShareWorldScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.share.title");
	private final SharedWorldsScreen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
	private @Nullable List<LevelSummary> levels;
	private @Nullable String loadError;
	private @Nullable LevelList list;
	private @Nullable Button shareButton;

	public ShareWorldScreen(SharedWorldsScreen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	public void added() {
		LevelStorageSource saves = minecraft.getLevelSource();
		try {
			saves.loadLevelSummaries(saves.findLevelCandidates()).whenCompleteAsync((result, error) -> {
				if (error != null) {
					WorldGDriveSync.LOGGER.warn("Couldn't list singleplayer worlds", error);
					loadError = error.getMessage();
				} else {
					levels = result.stream().filter(l -> !l.isDisabled()).sorted().toList();
				}
				rebuildWidgets();
			}, screenExecutor);
		} catch (LevelStorageException e) {
			loadError = e.getMessage();
		}
	}

	@Override
	protected void init() {
		layout.removeChildren();
		list = null;
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(TITLE, font));

		if (loadError != null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.share.loadFailed", loadError).withStyle(ChatFormatting.RED)));
		} else if (levels == null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.sharedWorlds.loading")));
		} else if (levels.isEmpty()) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.share.noWorlds")));
		} else {
			list = layout.addToContents(new LevelList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), levels, this));
		}

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		footer.addChild(text(Component.translatable("world-gdrive-sync.share.explain").withStyle(ChatFormatting.GRAY)));
		LinearLayout row = footer.addChild(LinearLayout.horizontal().spacing(8));
		shareButton = row.addChild(Button.builder(Component.translatable("world-gdrive-sync.share.confirm"), b -> shareSelected()).width(150).build());
		row.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(150).build());
		updateShareButton();

		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);
	}

	private MultiLineTextWidget text(Component message) {
		return new MultiLineTextWidget(message, font).setMaxWidth(310).setCentered(true);
	}

	void updateShareButton() {
		if (shareButton == null) return;
		LevelList.Entry selected = list == null ? null : list.getSelected();
		shareButton.active = selected != null && !selected.level.isLocked();
	}

	void shareSelected() {
		LevelList.Entry selected = list == null ? null : list.getSelected();
		if (selected == null || selected.level.isLocked()) return;
		LevelSummary level = selected.level;
		minecraft.gui.setScreen(new ShareProgressScreen(lastScreen,
				minecraft.getLevelSource().getLevelPath(level.getLevelId()), level.getLevelName()));
	}

	@Override
	protected void repositionElements() {
		if (list != null) list.updateSize(width, layout);
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
	}

	private static final class LevelList extends ObjectSelectionList<LevelList.Entry> {
		private final ShareWorldScreen screen;

		LevelList(Minecraft minecraft, int width, int height, int y, List<LevelSummary> levels, ShareWorldScreen screen) {
			super(minecraft, width, height, y, 36);
			this.screen = screen;
			for (LevelSummary level : levels) addEntry(new Entry(minecraft, level, screen));
		}

		@Override
		public void setSelected(@Nullable Entry selected) {
			super.setSelected(selected);
			screen.updateShareButton();
		}

		@Override
		public int getRowWidth() {
			return 270;
		}

		static final class Entry extends ObjectSelectionList.Entry<Entry> {
			private final Minecraft minecraft;
			final LevelSummary level;
			private final ShareWorldScreen screen;

			Entry(Minecraft minecraft, LevelSummary level, ShareWorldScreen screen) {
				this.minecraft = minecraft;
				this.level = level;
				this.screen = screen;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", level.getLevelName());
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (doubleClick) {
					screen.shareSelected();
				}
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 3;
				int y = getContentY() + 1;
				graphics.text(minecraft.font, level.getLevelName(), x, y, CommonColors.WHITE, false);
				Component detail = level.isLocked()
						? Component.translatable("world-gdrive-sync.share.worldOpen").withStyle(ChatFormatting.RED)
						: Component.literal(level.getLevelId());
				graphics.text(minecraft.font, detail, x, y + 12, CommonColors.LIGHT_GRAY, false);
			}
		}
	}
}
