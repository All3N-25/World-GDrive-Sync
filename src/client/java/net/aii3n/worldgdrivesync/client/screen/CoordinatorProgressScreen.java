package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.SharedWorlds;
import net.aii3n.worldgdrivesync.client.StorageMessages;
import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import org.jspecify.annotations.Nullable;

/**
 * Runs one long coordinator call (share, Play, final upload) on the network thread and shows its
 * progress. It can't be closed while the call runs. On failure it shows the error and a Back button.
 *
 * @param <T> what the call returns
 */
public abstract class CoordinatorProgressScreen<T> extends Screen implements ProgressListener {
	private static final int BAR_WIDTH = 200;
	private final boolean warnDontClose;
	private boolean started;

	private volatile @Nullable Step step;
	private volatile long done;
	private volatile long total;
	private @Nullable Component error;

	/** @param warnDontClose show "Don't close the game" under the bar (for anything that uploads) */
	protected CoordinatorProgressScreen(Component title, boolean warnDontClose) {
		super(title);
		this.warnDontClose = warnDontClose;
	}

	/** The coordinator call, run once on the network thread. */
	protected abstract T run(SessionCoordinator coordinator) throws Exception;

	/** Called on the client thread when {@link #run} returns. */
	protected abstract void onSuccess(T result);

	/** The message shown, in red, when {@link #run} throws. */
	protected abstract Component failureMessage(String error);

	/** Where Back (or Escape) goes after a failure. */
	protected abstract Screen afterFailure();

	/**
	 * The message shown instead of {@link #failureMessage} when Google Drive had no room.
	 *
	 * @param notEnoughSpace says so, and what to do about it
	 */
	protected Component notEnoughSpaceMessage(Component notEnoughSpace) {
		return notEnoughSpace;
	}

	@Override
	public void added() {
		if (started) return;
		started = true;
		SharedWorlds.get().run(this::run).whenCompleteAsync((result, failure) -> {
			if (failure == null) {
				onSuccess(result);
			} else {
				WorldGDriveSync.LOGGER.warn("{} failed", title.getString(), failure);
				error = StorageMessages.outOfSpace(failure)
						.map(this::notEnoughSpaceMessage)
						.orElseGet(() -> failureMessage(SharedWorlds.describe(failure)));
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
		addRenderableWidget(new MultiLineTextWidget(width / 2 - 150, height / 2 - 10, error.copy().withStyle(ChatFormatting.RED), font)
				.setMaxWidth(300).setCentered(true));
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(width / 2 - 100, height / 2 + 40, 200, 20).build());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return error != null;
	}

	@Override
	public void onClose() {
		if (error != null) minecraft.gui.setScreen(afterFailure());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, height / 2 - 40, CommonColors.WHITE);
		if (error != null) return;

		Step step = this.step;
		long done = this.done;
		long total = this.total;
		float fraction = step == null || total <= 0 ? 0 : Math.min(1f, (float) done / total);
		Component label = step == null ? Component.translatable("world-gdrive-sync.progress.starting") : switch (step) {
			case ZIPPING -> Component.translatable("world-gdrive-sync.share.zipping");
			case UPLOADING -> Component.translatable("world-gdrive-sync.share.uploading", megabytes(done), megabytes(total));
			case DOWNLOADING -> Component.translatable("world-gdrive-sync.progress.downloading", megabytes(done), megabytes(total));
			case UNZIPPING -> Component.translatable("world-gdrive-sync.progress.unzipping");
		};
		graphics.centeredText(font, label, width / 2, height / 2 - 20, CommonColors.LIGHT_GRAY);

		int x = width / 2 - BAR_WIDTH / 2;
		int y = height / 2;
		graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + 6, CommonColors.LIGHT_GRAY);
		graphics.fill(x, y, x + BAR_WIDTH, y + 5, CommonColors.BLACK);
		graphics.fill(x, y, x + Math.round(BAR_WIDTH * fraction), y + 5, CommonColors.GREEN);
		if (warnDontClose) {
			graphics.centeredText(font, Component.translatable("world-gdrive-sync.share.dontClose").withStyle(ChatFormatting.GRAY),
					width / 2, y + 16, CommonColors.GRAY);
		}
	}

	private static String megabytes(long bytes) {
		return String.format("%.1f", bytes / (1024.0 * 1024.0));
	}
}
