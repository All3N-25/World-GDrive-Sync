# Minecraft 26.3 integration notes

What the decompiled 26.3 sources say that memory of 1.21.x gets wrong. The mod uses Mojang names directly (26.x is unobfuscated). Check a class against the sources before using it: `./gradlew genSources` writes them under `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{common,clientOnly}-*/26.3/*-sources.jar`.

## Renamed or removed APIs

| 1.21.x | 26.3 |
|---|---|
| `minecraft.setScreen(s)` / `minecraft.screen` | `minecraft.gui.setScreen(s)` / `minecraft.gui.screen()` |
| `render(GuiGraphics, …)` | `extractRenderState(GuiGraphicsExtractor, int mouseX, int mouseY, float a)` |
| `renderBackground(…)` | `extractBackground(GuiGraphicsExtractor, …)` |
| `GuiGraphics.drawString` / `drawCenteredString` | `GuiGraphicsExtractor.text(…)` / `centeredText(…)`, `fill(x0, y0, x1, y1, argb)` |
| `ObjectSelectionList.Entry.render(…)` | `Entry.extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, a)`, geometry from `getContentX()/Y()/Width()/Height()` |
| Fabric `Screens.getButtons(screen)` | `Screens.getWidgets(screen)` |
| Fabric `ScreenEvents.beforeRender/afterRender` | `ScreenEvents.beforeExtract/afterExtract` |
| `Util.getPlatform().openUri(uri)` | removed: `com.mojang.blaze3d.Blaze3D.openUri(URI)` (client only) |
| `EditBox.setFilter` | removed: validate in the responder |
| key/mouse handlers taking ints | `keyPressed(KeyEvent)`, `mouseClicked(MouseButtonEvent, boolean doubleClick)` |
| `ResourceLocation` | `net.minecraft.resources.Identifier` |

`Screen.screenExecutor` runs a task on the client thread only while that screen is still showing, so it's the safe way to post an async result back to a screen.

## World lifecycle gotchas

- **Opening a world outside `saves/`** needs no mixin: `new WorldOpenFlows(mc, LevelStorageSource.createDefault(sharedsavesDir)).openWorld(levelId, onCancel)`. The integrated server then saves into that folder. See `SharedWorldLauncher`.
- **Save-and-quit** (`Minecraft.disconnectFromWorld`) blocks the client thread until the integrated server has fully stopped, then shows the title screen. After `ServerLifecycleEvents.SERVER_STOPPED` the world folder is complete and unlocked.
- **Closing the window or a crash** gives no chance to upload. That's why the Local Copy record carries a session marker, set on Play and cleared only after a successful final upload.
- **Saving while hosting:** `server.saveEverything(true, true, true)` must run on the server thread (`server.submit`). Copy the world folder on that same thread: chunk unloads keep writing region files, so a zip of the live folder taken off-thread can catch them half-written.
- **`session.lock`** is held with a `FileLock` while the world is open. Always leave it out of copies and zips.

## Threads and timers

- Network I/O never runs in a tick handler or on the render thread. Hand it to the mod's own executors. Heartbeats have a dedicated thread so a long upload can't starve them.
- Timers compare wall-clock time, not tick counts: the integrated server pauses while the pause menu is open.

## Unverified in game

Essential replaces the vanilla main menu. The Shared Worlds button comes from Fabric's `ScreenEvents.AFTER_INIT` on `TitleScreen` (`TitleScreenButton`); check that it still shows with Essential installed.
