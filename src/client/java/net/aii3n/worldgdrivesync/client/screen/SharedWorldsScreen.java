package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.client.GoogleConnection;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Lists Shared Worlds. For now it is empty apart from the connect prompt and the gear button. */
public class SharedWorldsScreen extends Screen {
	private static final Component TITLE = Component.translatable("world-gdrive-sync.sharedWorlds.title");
	private final Screen lastScreen;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private Button gearButton;
	private boolean shownConnected;

	public SharedWorldsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		shownConnected = GoogleConnection.get().account().isConnected();
		layout.removeChildren();
		layout.addTitleHeader(TITLE, font);

		LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(8));
		content.defaultCellSetting().alignHorizontallyCenter();
		if (shownConnected) {
			content.addChild(new MultiLineTextWidget(
					Component.translatable("world-gdrive-sync.sharedWorlds.empty"), font).setMaxWidth(300).setCentered(true));
		} else {
			content.addChild(new MultiLineTextWidget(
					Component.translatable("world-gdrive-sync.sharedWorlds.connectFirst"), font).setMaxWidth(300).setCentered(true));
			content.addChild(Button.builder(Component.translatable("world-gdrive-sync.sharedWorlds.openSettings"),
					b -> openSettings()).width(200).build());
		}

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.arrangeElements();
		layout.visitWidgets(this::addRenderableWidget);

		gearButton = addRenderableWidget(Button.builder(Component.literal("⚙"), b -> openSettings())
				.tooltip(Tooltip.create(Component.translatable("world-gdrive-sync.settings.title")))
				.size(20, 20).build());
		positionGear();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		positionGear();
	}

	@Override
	public void tick() {
		// Coming back from settings doesn't re-run init(), so notice a (dis)connect here.
		if (GoogleConnection.get().account().isConnected() != shownConnected) rebuildWidgets();
	}

	private void positionGear() {
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
}
