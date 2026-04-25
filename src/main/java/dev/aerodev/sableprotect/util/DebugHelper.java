package dev.aerodev.sableprotect.util;

import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DebugHelper {

    private static final Set<UUID> debugPlayers = new HashSet<>();

    private DebugHelper() {}

    public static boolean isEnabled(final Player player) {
        return debugPlayers.contains(player.getUUID());
    }

    /**
     * Toggles debug mode for the given player.
     * @return true if debug is now enabled, false if disabled
     */
    public static boolean toggle(final Player player) {
        final UUID id = player.getUUID();
        if (debugPlayers.remove(id)) {
            return false;
        }
        debugPlayers.add(id);
        return true;
    }
}
