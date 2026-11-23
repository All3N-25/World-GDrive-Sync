package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.session.Backup;
import net.aii3n.worldgdrivesync.session.CrashedSession;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.aii3n.worldgdrivesync.session.WorldStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/**
 * One Shared World's Backups, newest first, with their timestamps; Unmerged Backups are marked.
 * Restore makes the selected Backup the Current Save (after a confirmation), see {@link RestoreScreen}.
 */
public class BackupsScreen extends Screen {
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
			.withZone(ZoneId.systemDefault());

	private final Screen lastScreen;
	private final SharedWorld world;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
	private @Nullable BackupList list;
	private @Nullable Button restoreButton;

	/** Null while loading. */
	private @Nullable List<Backup> backups;
	private @Nullable String loadError;
	private boolean loading;

	public BackupsScreen(Screen lastScreen, SharedWorld world) {
		super(Component.translatable("world-gdrive-sync.backups.title", world.name()));
		this.lastScreen = lastScreen;
		this.world = world;
	}

	@Override
	public void added() {
		if (loading || backups != null) return;
		loading = true;
		SharedWorlds.get().run(coordinator -> coordinator.backups(world.id())).whenCompleteAsync((result, error) -> {
			loading = false;
			if (error != null) loadError = SharedWorlds.describe(error);
			else backups = result;
			rebuildWidgets();
		}, screenExecutor);
	}

	@Override
	protected void init() {
		layout.removeChildren();
		list = null;
		layout.addTitleHeader(title, font);

		if (loadError != null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.backups.loadFailed", loadError).withStyle(ChatFormatting.RED)));
		} else if (backups == null) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.sharedWorlds.loading")));
		} else if (backups.isEmpty()) {
			layout.addToContents(text(Component.translatable("world-gdrive-sync.backups.empty")));
		} else {
			list = layout.addToContents(new BackupList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), backups));
		}

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		if (!(world.status() instanceof WorldStatus.Free)) {
			footer.addChild(text(Component.translatable("world-gdrive-sync.backups.locked").withStyle(ChatFormatting.GOLD)));
		}
		LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(8));
		restoreButton = buttons.addChild(Button.builder(Component.translatable("world-gdrive-sync.backups.restore"),
				b -> confirmRestore()).width(150).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(150).build());
		updateRestoreButton();

		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);
	}

	private MultiLineTextWidget text(Component message) {
		return new MultiLineTextWidget(message, font).setMaxWidth(300).setCentered(true);
	}

	@Override
	protected void repositionElements() {
		if (list != null) list.updateSize(width, layout);
		layout.arrangeElements();
	}

	@Override
	public void tick() {
		updateRestoreButton();
	}

	private void updateRestoreButton() {
		if (restoreButton != null) restoreButton.active = selected() != null;
	}

	private @Nullable Backup selected() {
		return list != null && list.getSelected() != null ? list.getSelected().backup : null;
	}

	private void confirmRestore() {
		Backup backup = selected();
		if (backup == null) return;
		minecraft.gui.setScreen(new ConfirmScreen(yes -> minecraft.gui.setScreen(yes ? new RestoreScreen(lastScreen, this, world, backup) : this),
				Component.translatable("world-gdrive-sync.backups.confirm.title"),
				Component.translatable("world-gdrive-sync.backups.confirm", world.name(), backup.revision(), when(backup.savedAt()))));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
	}

	/** "24 Nov 2026, 18:00 (3h 5m ago)" in this computer's time zone. */
	static String when(Instant instant) {
		return WHEN.format(instant) + " (" + SharedWorldsScreen.duration(Duration.between(instant, Instant.now())) + " ago)";
	}

	private static final class BackupList extends ObjectSelectionList<BackupList.Entry> {
		BackupList(Minecraft minecraft, int width, int height, int y, List<Backup> backups) {
			super(minecraft, width, height, y, 36);
			for (Backup backup : backups) addEntry(new Entry(minecraft, backup));
		}

		@Override
		public int getRowWidth() {
			return 300;
		}

		static final class Entry extends ObjectSelectionList.Entry<Entry> {
			private final Minecraft minecraft;
			private final Backup backup;

			Entry(Minecraft minecraft, Backup backup) {
				this.minecraft = minecraft;
				this.backup = backup;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", headline());
			}

			private Component headline() {
				return Component.translatable("world-gdrive-sync.backups.entry", backup.revision(), when(backup.savedAt()));
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 3;
				int y = getContentY() + 1;
				graphics.text(minecraft.font, headline(), x, y, CommonColors.WHITE, false);
				if (backup.unmerged().isPresent()) {
					CrashedSession session = backup.unmerged().get();
					graphics.text(minecraft.font, Component.translatable("world-gdrive-sync.backups.unmerged",
									session.hostName(), WHEN.format(session.startedAt())).withStyle(ChatFormatting.GOLD),
							x, y + 12, CommonColors.LIGHT_GRAY, false);
				}
			}
		}
	}
}
