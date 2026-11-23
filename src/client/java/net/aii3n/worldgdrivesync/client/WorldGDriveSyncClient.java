package net.aii3n.worldgdrivesync.client;

import net.fabricmc.api.ClientModInitializer;

public class WorldGDriveSyncClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		GoogleConnection.init();
		SharedWorlds.init();
		TitleScreenButton.register();
		HostingSession.register();
		Heartbeats.register();
	}
}
