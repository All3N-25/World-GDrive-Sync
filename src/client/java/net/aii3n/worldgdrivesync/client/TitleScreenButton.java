package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.client.screen.SharedWorldsScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.Optional;

/**
 * Adds the Shared Worlds button to the title screen. It takes the left half of the Realms row and
 * Realms shrinks to the right half (like Options/Quit), so nothing else has to move. Realms keeps
 * the row's right edge because vanilla draws its notification icons there at fixed coordinates.
 */
final class TitleScreenButton {
	private static final int GAP = 4;

	private TitleScreenButton() {
	}

	static void register() {
		ScreenEvents.AFTER_INIT.register(TitleScreenButton::afterInit);
	}

	private static void afterInit(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof TitleScreen)) return;
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		Button.Builder button = Button.builder(Component.translatable("world-gdrive-sync.menu.sharedWorlds"),
				b -> client.gui.setScreen(new SharedWorldsScreen(screen)));

		Optional<AbstractWidget> realms = find(widgets, "menu.online");
		if (realms.isPresent()) {
			AbstractWidget row = realms.get();
			int rowLeft = row.getX();
			int rowRight = row.getRight();
			int half = (row.getWidth() - GAP) / 2;
			row.setWidth(half);
			row.setX(rowRight - half);
			widgets.add(button.bounds(rowLeft, row.getY(), half, row.getHeight()).build());
			return;
		}
		// No Realms row (e.g. demo mode or another mod rearranged the menu): sit beside Singleplayer.
		Optional<AbstractWidget> singleplayer = find(widgets, "menu.singleplayer");
		int y = singleplayer.map(AbstractWidget::getY).orElse(height / 4 + 48);
		widgets.add(button.bounds(width / 2 + 104, y, 98, 20).build());
	}

	private static Optional<AbstractWidget> find(List<AbstractWidget> widgets, String translationKey) {
		return widgets.stream()
				.filter(w -> w.getMessage().getContents() instanceof TranslatableContents tc && tc.getKey().equals(translationKey))
				.findFirst();
	}
}
