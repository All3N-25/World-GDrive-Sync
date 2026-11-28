package net.aii3n.worldgdrivesync.session;

/**
 * The player running this copy of the game: who takes the Lock when they press Play.
 *
 * @param id   stable and unique per player (the Minecraft profile UUID); decides "this Lock is mine"
 * @param name shown to friends, as in "Hosted by <name>"
 */
public record Player(String id, String name) {
}
