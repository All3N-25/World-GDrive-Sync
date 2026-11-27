# World GDrive Sync

A Minecraft mod that lets a group of friends take turns hosting one shared world, with the world living in cloud storage between sessions.

## Language

### The world

**Shared World**:
The single world a friend group plays together, stored in cloud storage rather than on any one player's machine. One storage folder holds exactly one Shared World.
_Avoid_: Server, save, map

**Current Save**:
The latest complete copy of the Shared World in cloud storage, stored as a single archive.
_Avoid_: Live world, latest

**Revision**:
A number that goes up by one each time a new Current Save is uploaded. Every local copy remembers the Revision it came from, so the mod can tell whether the local copy or the cloud copy is newer.
_Avoid_: Version, generation

**Local Copy**:
A player's own unzipped copy of the Shared World, kept apart from their ordinary singleplayer worlds.
_Avoid_: Local save, cache

**Local-only Data**:
Files in a Local Copy that are never uploaded, and that stay in the Local Copy when a newer Current Save replaces it. Today that is Distant Horizons' LOD cache (`DistantHorizons.sqlite` and its journal files), which each player's game rebuilds from the world's chunks.
_Avoid_: Cache, ignored files

**Pending Upload**:
A Local Copy that was supposed to become the new Current Save but hasn't been uploaded yet, usually because the upload failed. The Host keeps the Lock until it succeeds.

**Backup**:
A previous Current Save, kept with a timestamp after being replaced by a newer one. Only the most recent few are kept.
_Avoid_: Snapshot, version

**Unmerged Backup**:
A Backup made from a returning Host's Pending Upload after someone else Took Over and uploaded a newer Revision. It keeps that session's progress without overwriting the newer world.
_Avoid_: Conflict copy, orphan save

### People

**Host**:
The one player currently running the Shared World on their machine for others to join. Hosting rotates between players.
_Avoid_: Server, owner, admin

**Guest**:
A player who joins the Host's session. A Guest never touches cloud storage.
_Avoid_: Client, member

### Coordination

**Lock**:
A marker in cloud storage saying who is currently the Host. While a fresh Lock exists, no one else may start hosting the Shared World.
_Avoid_: Claim, reservation

**Heartbeat**:
The Host's periodic refresh of the Lock, proving they are still hosting.

**Stale Lock**:
A Lock whose Heartbeat has stopped for longer than the timeout, usually because the Host crashed or lost connection.
_Avoid_: Dead lock, expired lock

**Takeover**:
Starting to host despite a Stale Lock, from the last Current Save, accepting that the previous Host's unsaved progress may be lost. The alternative is to wait for the previous Host to return and save properly.
_Avoid_: Force-unlock, override
