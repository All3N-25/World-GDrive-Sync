package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Shares one world: zips it and uploads it to Drive as Revision 1, showing progress. The work runs
 * on the network thread; this screen only reads the latest progress each frame.
 */
public class ShareProgressScreen extends Screen implements ProgressListener {
	private static final int BAR_WIDTH = 200;
	private final SharedWorldsScreen lastScreen;
	private final Path worldFolder;
	private final String name;
	private boolean started;

	private volatile Step step = Step.ZIPPING;
	private volatile long done;
	private volatile long total;
	private @Nullable String error;

	public ShareProgressScreen(SharedWorldsScreen lastScreen, Path worldFolder, String name) {
		super(Component.translatable("world-gdrive-sync.share.progressTitle", name));
		this.lastScreen = lastScreen;
		this.worldFolder = worldFolder;
		this.name = name;
	}

	@Override
	public void added() {
		if (started) return;
		started = true;
		SharedWorlds.get().run(coordinator -> coordinator.share(worldFolder, name, this)).whenCompleteAsync((shared, failure) -> {
			if (failure == null) {
				minecraft.gui.setScreen(lastScreen);
			} else {
				WorldGDriveSync.LOGGER.warn("Sharing world {} failed", name, failure);
				error = SharedWorlds.describe(failure);
				rebuildWidgets();
			}
		}, minecraft);
	}

	@Override
	public void onProgress(Step step, long done, long total) {
		this.step = step;
		this.done = done;
		this.total = total;
	}

	@Override
	protected void init() {
		if (error == null) return;
		addRenderableWidget(new MultiLineTextWidget(width / 2 - 150, height / 2 - 10,
				Component.translatable("world-gdrive-sync.share.failed", error).withStyle(ChatFormatting.RED), font)
				.setMaxWidth(300).setCentered(true));
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> minecraft.gui.setScreen(lastScreen))
				.bounds(width / 2 - 100, height / 2 + 30, 200, 20).build());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return error != null;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, height / 2 - 40, CommonColors.WHITE);
		if (error != null) return;

		long total = this.total;
		float fraction = total <= 0 ? 0 : Math.min(1f, (float) done / total);
		Component label = switch (step) {
			case ZIPPING -> Component.translatable("world-gdrive-sync.share.zipping");
			case UPLOADING -> Component.translatable("world-gdrive-sync.share.uploading",
					megabytes(done), megabytes(total));
		};
		graphics.centeredText(font, label, width / 2, height / 2 - 20, CommonColors.LIGHT_GRAY);

		int x = width / 2 - BAR_WIDTH / 2;
		int y = height / 2;
		graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + 6, CommonColors.LIGHT_GRAY);
		graphics.fill(x, y, x + BAR_WIDTH, y + 5, CommonColors.BLACK);
		graphics.fill(x, y, x + Math.round(BAR_WIDTH * fraction), y + 5, CommonColors.GREEN);
		graphics.centeredText(font, Component.translatable("world-gdrive-sync.share.dontClose").withStyle(ChatFormatting.GRAY),
				width / 2, y + 16, CommonColors.GRAY);
	}

	private static String megabytes(long bytes) {
		return String.format("%.1f", bytes / (1024.0 * 1024.0));
	}
}
