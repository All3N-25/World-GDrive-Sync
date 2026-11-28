# Coding standards

## Client code only wires

Put code that decides something (what state hosting is in, whether to upload, release or retry) in a class without Minecraft types in the main source set (`src/main/java`), with tests. Client classes turn game events into calls on it and act on what it returns.

Why: JUnit only runs against the main source set, so a decision left in client code can't be tested. Fixing the failed-world-load bug first needed this split.

Pattern to copy: [`HostingSessionState`](src/main/java/net/aii3n/worldgdrivesync/session/HostingSessionState.java) decides ([tests](src/test/java/net/aii3n/worldgdrivesync/session/HostingSessionStateTest.java)); the client's [`HostingSession`](src/client/java/net/aii3n/worldgdrivesync/client/HostingSession.java) wires it to the game.
