# Local Copies live in `sharedsaves/`, not the vanilla `saves/` folder

Each player's Local Copy of a Shared World is unzipped into a separate `sharedsaves/` folder, and the mod launches it with its own world-launch code instead of through the vanilla Singleplayer list. We did this so a Shared World never shows up in the normal Singleplayer list, where someone could open and play it without taking the Lock or downloading the newer Revision, which would quietly fork the world.

## Consequences

- The world-launch path is custom code that depends on Minecraft internals, so it's the part most likely to break when Minecraft updates. Don't "simplify" it by moving Local Copies into `saves/`: that brings back the bypass-the-Lock problem.
- Sharing an existing world reads from `saves/` once (to create Revision 1). After that, the Shared World lives only in `sharedsaves/`.
